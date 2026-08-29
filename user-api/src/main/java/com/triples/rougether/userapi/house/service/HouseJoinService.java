package com.triples.rougether.userapi.house.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseJoinRequest;
import com.triples.rougether.domain.house.entity.HouseJoinRequestStatus;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseJoinRequestRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import com.triples.rougether.userapi.house.dto.HouseJoinDetailResponse;
import com.triples.rougether.userapi.house.dto.HouseJoinRequestListResponse;
import com.triples.rougether.userapi.house.dto.HouseJoinRequestResponse;
import com.triples.rougether.userapi.house.dto.HouseJoinResponse;
import com.triples.rougether.userapi.house.dto.HousePreviewResponse;
import com.triples.rougether.userapi.house.error.ApplicantWithdrawnException;
import com.triples.rougether.userapi.bot.BotResidencyService;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.notification.message.NotificationMessages;
import com.triples.rougether.userapi.notification.service.NotificationService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 초대코드 참여(집 공용 코드 즉시가입 / 구성원 개인 코드 승인 대기) + 탐색 입주 신청/승인 + 참여 전 미리보기.
// 정원 검사와 구성원 수 증가는 house 행 락 아래 같은 트랜잭션에서 처리해 동시 참여 초과를 막음.
@Service
@RequiredArgsConstructor
public class HouseJoinService {

    private final HouseRepository houseRepository;
    private final HouseMemberRepository houseMemberRepository;
    private final HouseJoinRequestRepository houseJoinRequestRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final BotResidencyService botResidencyService;

    // 집 공용 코드(소유자 공유)는 즉시가입, 구성원 개인 코드는 방장 승인 대기 신청 생성.
    // 코드 조회는 집 코드 → 구성원 코드 순이며 두 네임스페이스는 발급 시점에 겹치지 않게 보장된다(InviteCodeGenerator).
    @Transactional
    public HouseJoinResponse joinByCode(Long userId, String rawInviteCode) {
        String inviteCode = normalizeCode(rawInviteCode);
        House byHouseCode = houseRepository.findWithLockByInviteCode(inviteCode)
                .filter(found -> !found.isDeleted())
                .orElse(null);
        if (byHouseCode != null) {
            if (byHouseCode.isInviteExpired()) {
                throw new BusinessException(HouseErrorCode.INVITE_CODE_EXPIRED);
            }
            return joinImmediately(byHouseCode, userId);
        }
        return joinByMemberCode(userId, inviteCode);
    }

    private HouseJoinResponse joinImmediately(House house, Long userId) {
        HouseMember member = join(house, userId);
        // 락 조회 - 개인 초대코드 경로의 사전 스냅샷에 가려진 대기 신청도 함께 종결한다.
        houseJoinRequestRepository.findWithLockByHouseIdAndUserId(house.getId(), userId)
                .filter(HouseJoinRequest::isPending)
                .ifPresent(HouseJoinRequest::accept);
        return HouseJoinResponse.joined(member.getId(), house.getId(), member.getStatus());
    }

    // 구성원 개인 코드 참여 - 초대자가 현재 소유자면 집 코드와 동일하게 즉시가입,
    // 일반 구성원이면 탐색 입주 신청과 같은 PENDING 신청을 만들고 방장 수락으로 확정한다.
    // 초대자 판정(활성·역할·만료·코드 일치)은 house 락 이후의 락 재조회(current read)로 한다 -
    // 락 대기 중 커밋된 초대자의 탈퇴·강퇴·코드 회전·소유권 변경을 스냅샷 조회는 못 보기 때문.
    private HouseJoinResponse joinByMemberCode(Long userId, String inviteCode) {
        HouseMemberRepository.InviteJoinTarget target = houseMemberRepository
                .findJoinTargetByInviteCode(inviteCode)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.INVITE_CODE_INVALID));

        // 정원 검사·신청 생성은 다른 참여 경로와 같은 house 행 락 아래에서 처리한다.
        House house = findHouseWithLock(target.getHouseId());
        HouseMember inviter = houseMemberRepository
                .findWithLockByHouseIdAndUserId(house.getId(), target.getUserId())
                .filter(HouseMember::isActive)
                .filter(found -> inviteCode.equals(found.getInviteCode()))
                .orElseThrow(() -> new BusinessException(HouseErrorCode.INVITE_CODE_INVALID));
        if (inviter.isInviteExpired()) {
            throw new BusinessException(HouseErrorCode.INVITE_CODE_EXPIRED);
        }
        if (inviter.isOwner()) {
            return joinImmediately(house, userId);
        }
        HouseJoinRequest request = createOrReopenPendingRequest(house, house.getId(), userId);
        return HouseJoinResponse.pending(house.getId(), request.getId());
    }

    // 탐색 목록에서는 즉시가입하지 않고 방장 승인을 기다리는 입주 신청만 생성함.
    @Transactional
    public HouseJoinRequestResponse requestJoin(Long userId, Long houseId) {
        House house = houseRepository.findWithLockById(houseId)
                .filter(found -> !found.isDeleted() && found.isPublic())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
        return HouseJoinRequestResponse.of(createOrReopenPendingRequest(house, houseId, userId));
    }

    // 공용 신청 판정: 중복(active)/강퇴 이력 -> 중복 신청 -> 정원 -> 신규 생성 또는 거절 이력 재오픈.
    // 호출자는 house 를 행 락으로 조회한 상태여야 한다(수락 시 정원 재검사와 직렬화).
    // 멤버십·신청 판정은 락 조회(current read)로 한다 - 개인 초대코드 참여는 house 락 이전의
    // 스냅샷 조회로 REPEATABLE READ read view 가 잡혀 있어, 일반 조회는 락 대기 중 커밋된
    // 즉시가입(ACTIVE)·신청 row 를 못 보고 중복 신청을 만들거나 unique 충돌 500 이 나기 때문.
    private HouseJoinRequest createOrReopenPendingRequest(House house, Long houseId, Long userId) {
        // 탈퇴 계정 가드 - join() 과 동일(#236 INVALID_TOKEN 컨벤션). 잔여 access token 의 재신청이 탈퇴 정리로
        // REJECTED 된 신청을 reopen 으로 되살리고 방장 알림까지 내보내는 것을 차단한다. user 행 락(current read)이라
        // 동시 탈퇴와도 직렬화되며, 여기서 읽은 엔티티를 신청 행과 알림 본문(닉네임)에 재사용한다.
        User applicant = userRepository.findByIdForUpdate(userId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));
        HouseMember existingMember = houseMemberRepository
                .findWithLockByHouseIdAndUserId(houseId, userId)
                .orElse(null);
        if (existingMember != null && existingMember.isActive()) {
            throw new BusinessException(HouseErrorCode.HOUSE_ALREADY_MEMBER);
        }
        if (existingMember != null && existingMember.isKicked()) {
            throw new BusinessException(HouseErrorCode.HOUSE_KICKED_MEMBER);
        }
        HouseJoinRequest request = houseJoinRequestRepository
                .findWithLockByHouseIdAndUserId(houseId, userId)
                .orElse(null);
        if (request != null && request.isPending()) {
            throw new BusinessException(HouseErrorCode.HOUSE_JOIN_REQUEST_ALREADY_PENDING);
        }
        // 동거 봇(#309): 봇이 채운 만석은 신청을 받는다 — 방장이 수락하면 그때 봇이 자리를 비운다.
        if (!botResidencyService.hasSeatForHuman(house)) {
            throw new BusinessException(HouseErrorCode.HOUSE_FULL);
        }
        if (request == null) {
            request = houseJoinRequestRepository.save(HouseJoinRequest.create(house, applicant));
        } else {
            request.reopen();
        }
        notifyJoinRequestCreated(request, house, applicant);
        return request;
    }

    // 기존 서비스 단위 테스트·내부 픽스처용 즉시가입 진입점. 외부 HTTP 경로에는 노출하지 않음.
    @Transactional
    public HouseJoinDetailResponse join(Long userId, Long houseId) {
        House house = findHouseWithLock(houseId);
        HouseMember member = join(house, userId);
        return HouseJoinDetailResponse.of(member, houseId, userId);
    }

    @Transactional(readOnly = true)
    public HouseJoinRequestListResponse getPendingRequests(Long ownerUserId, Long houseId) {
        House house = findHouse(houseId);
        requireOwner(house, ownerUserId);
        List<HouseJoinRequestResponse> items = houseJoinRequestRepository
                .findByHouseIdAndStatusWithUser(houseId, HouseJoinRequestStatus.PENDING).stream()
                .map(HouseJoinRequestResponse::of)
                .toList();
        return new HouseJoinRequestListResponse(items);
    }

    @Transactional(noRollbackFor = ApplicantWithdrawnException.class)
    public HouseJoinDetailResponse acceptRequest(Long ownerUserId, Long houseId, Long requestId) {
        House house = findHouseWithLock(houseId);
        requireOwner(house, ownerUserId);
        HouseJoinRequest request = findPendingRequestWithLock(houseId, requestId);
        if (request.getUser().isDeleted()) {
            request.reject();
            throw new ApplicantWithdrawnException();
        }

        HouseMember member = join(house, request.getUser().getId());
        request.accept();
        notifyJoinRequestAccepted(request, house);
        return HouseJoinDetailResponse.of(member, houseId, request.getUser().getId());
    }

    @Transactional
    public void rejectRequest(Long ownerUserId, Long houseId, Long requestId) {
        House house = findHouseWithLock(houseId);
        requireOwner(house, ownerUserId);
        HouseJoinRequest request = findPendingRequestWithLock(houseId, requestId);
        request.reject();
        notifyJoinRequestRejected(request, house);
    }

    // 신청자 본인의 철회 - 행을 삭제해 UNIQUE(house_id, user_id) 하에서 재신청이 신규 생성으로 동작함.
    // 미존재와 타인 신청은 /me 스코프상 동일하게 404 로 숨긴다. 락 조회로 소유자의 동시 accept 와
    // 직렬화 - accept 가 먼저 커밋되면 PENDING 이 아니므로 409.
    @Transactional
    public void withdrawRequest(Long userId, Long requestId) {
        HouseJoinRequest request = houseJoinRequestRepository.findWithLockById(requestId)
                .filter(found -> found.getUser().getId().equals(userId))
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_JOIN_REQUEST_NOT_FOUND));
        if (!request.isPending()) {
            throw new BusinessException(HouseErrorCode.HOUSE_JOIN_REQUEST_NOT_PENDING);
        }
        houseJoinRequestRepository.delete(request);
    }

    // 공용 참여 판정: 중복(active)/강퇴 이력 -> 정원 -> 재활성화 또는 신규 등록 -> 구성원 수 증가.
    // 호출자는 house 를 행 락으로 조회한 상태여야 한다.
    private HouseMember join(House house, Long userId) {
        // 탈퇴 계정 가드 - 잔여 access token(최대 30분)의 참여로 탈퇴 트랜잭션의 멤버십 정리(LEFT·정원 감소)가
        // 되돌아가는 것을 차단함(#236 INVALID_TOKEN 컨벤션). user 행 락(current read)이라 동시 탈퇴와도 직렬화됨.
        User joiner = userRepository.findByIdForUpdate(userId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));
        // 락 조회(current read) - 개인 초대코드 경로는 house 락 이전에 스냅샷이 잡혀 있어
        // 일반 조회로는 락 대기 중 커밋된 기존 멤버십을 못 보고 중복 등록(unique 충돌)이 난다.
        HouseMember existing = houseMemberRepository
                .findWithLockByHouseIdAndUserId(house.getId(), userId)
                .orElse(null);
        if (existing != null && existing.isActive()) {
            throw new BusinessException(HouseErrorCode.HOUSE_ALREADY_MEMBER);
        }
        if (existing != null && existing.isKicked()) {
            // 강퇴 이력은 재가입 불가 - LEFT 재활성화와 구분.
            throw new BusinessException(HouseErrorCode.HOUSE_KICKED_MEMBER);
        }
        // 동거 봇(#309): 만석이어도 비켜줄 봇이 있으면 가장 나중에 들어온 봇이 자리를 비운다(사람만으로 만석이면 HOUSE_FULL).
        if (!botResidencyService.yieldSeat(house)) {
            throw new BusinessException(HouseErrorCode.HOUSE_FULL);
        }

        // 수신 대상은 신규 멤버를 뺀 기존 활성 멤버 - 재활성화/신규 등록 전에 확정해야 본인이 섞이지 않음.
        List<HouseMember> recipients = houseMemberRepository
                .findByHouseIdAndStatusWithUser(house.getId(), HouseMemberStatus.ACTIVE);

        HouseMember member;
        if (existing != null) {
            // LEFT 이력 재가입 - uq_house_member 제약상 새 row 대신 재활성화.
            existing.reactivate();
            member = existing;
        } else {
            member = houseMemberRepository.save(
                    HouseMember.create(house, joiner, HouseMemberRole.MEMBER));
        }
        house.increaseMemberCount();
        notifyMemberJoined(recipients, member);
        return member;
    }

    private House findHouse(Long houseId) {
        return houseRepository.findById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    private House findHouseWithLock(Long houseId) {
        return houseRepository.findWithLockById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    private void requireOwner(House house, Long userId) {
        if (!house.getOwner().getId().equals(userId)) {
            throw new BusinessException(HouseErrorCode.HOUSE_NOT_OWNER);
        }
    }

    private HouseJoinRequest findPendingRequestWithLock(Long houseId, Long requestId) {
        return houseJoinRequestRepository.findWithLockByIdAndHouseId(requestId, houseId)
                .filter(HouseJoinRequest::isPending)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_JOIN_REQUEST_NOT_PENDING));
    }

    // 코드는 대문자 집합으로만 발급된다(InviteCodeGenerator). 링크·수기 입력의 소문자·양끝 공백을 흡수하고
    // (친구 초대 InviteService 와 동일 규칙), MySQL ci collation 에서는 소문자 입력이 조회는 통과하되
    // 개인 코드 경로의 equals 재검증만 실패해 코드 종류·DB 환경에 따라 성패가 갈리던 것도 함께 막는다.
    private String normalizeCode(String rawCode) {
        return rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
    }

    // 같은 (방장, 신청자 닉네임, 집) 조합 알림의 재발송 억제 창. 철회→재신청(새 행)·거절→재신청(reopen)을
    // 반복해 방장에게 push 를 무제한 밀어넣는 증폭을 막는다. 신청 자체의 반복 제한은 spec open question.
    private static final Duration JOIN_REQUEST_NOTIFY_SUPPRESS_WINDOW = Duration.ofHours(1);

    // 신청 도착 알림 - 수락 권한자인 방장에게만 감. 방장이 신청을 몰라 수락(입주 확정)이 늦어지는 걸 막는다.
    // 재오픈도 방장 입장에선 새 신청이라 동일하게 알리되, 억제 창 안의 반복 신청은 중복 발송하지 않는다.
    // refId 는 승인·거절 알림과 대칭으로 신청(request) 자체.
    private void notifyJoinRequestCreated(HouseJoinRequest request, House house, User applicant) {
        var content = NotificationMessages.houseJoinRequestCreated(
                applicant.getNickname(), house.getName());
        notificationService.sendUnlessDuplicatedSince(house.getOwner().getId(), content, request.getId(),
                Instant.now().minus(JOIN_REQUEST_NOTIFY_SUPPRESS_WINDOW));
    }

    // 입주 알림 - 가입과 같은 트랜잭션에서 동기 저장(응원 #174 패턴). push 만 커밋 후 비동기로 나감.
    private void notifyMemberJoined(List<HouseMember> recipients, HouseMember joined) {
        if (recipients.isEmpty()) {
            return;
        }
        var content = NotificationMessages.houseMemberJoined(joined.getUser().getNickname());
        recipients.forEach(recipient -> notificationService.send(
                recipient.getUser().getId(), content, joined.getId()));
    }

    // 거절 알림 - 신청자 본인에게만 감. refId 는 신청(request) 자체.
    private void notifyJoinRequestRejected(HouseJoinRequest request, House house) {
        var content = NotificationMessages.houseJoinRequestRejected(house.getName());
        notificationService.send(request.getUser().getId(), content, request.getId());
    }

    // 승인 알림 - 신청자 본인에게만 감(기존 멤버 알림은 join() 내부 notifyMemberJoined 가 별도 처리).
    // refId 는 거절 알림과 대칭으로 신청(request) 자체.
    private void notifyJoinRequestAccepted(HouseJoinRequest request, House house) {
        var content = NotificationMessages.houseJoinRequestAccepted(house.getName());
        notificationService.send(request.getUser().getId(), content, request.getId());
    }

    // 만료 여부·승인 필요 여부는 코드 종류(집 공용/구성원 개인)에 따라 각자의 만료 시각·초대자 역할로 판정한다.
    @Transactional(readOnly = true)
    public HousePreviewResponse preview(String rawInviteCode) {
        String inviteCode = normalizeCode(rawInviteCode);
        House byHouseCode = houseRepository.findByInviteCode(inviteCode)
                .filter(found -> !found.isDeleted())
                .orElse(null);
        if (byHouseCode != null) {
            return HousePreviewResponse.of(byHouseCode, byHouseCode.isInviteExpired(), false);
        }
        HouseMember inviter = houseMemberRepository.findByInviteCodeWithHouse(inviteCode)
                .filter(HouseMember::isActive)
                .filter(found -> !found.getHouse().isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.INVITE_CODE_INVALID));
        return HousePreviewResponse.of(
                inviter.getHouse(), inviter.isInviteExpired(), !inviter.isOwner());
    }
}
