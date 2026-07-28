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
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseJoinDetailResponse;
import com.triples.rougether.userapi.house.dto.HouseJoinRequestListResponse;
import com.triples.rougether.userapi.house.dto.HouseJoinRequestResponse;
import com.triples.rougether.userapi.house.dto.HouseJoinResponse;
import com.triples.rougether.userapi.house.dto.HousePreviewResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.notification.message.NotificationMessages;
import com.triples.rougether.userapi.notification.service.NotificationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 초대코드 즉시가입 + 탐색 입주 신청/승인 + 참여 전 미리보기.
// 정원 검사와 구성원 수 증가는 house 행 락 아래 같은 트랜잭션에서 처리해 동시 참여 초과를 막음.
@Service
@RequiredArgsConstructor
public class HouseJoinService {

    private final HouseRepository houseRepository;
    private final HouseMemberRepository houseMemberRepository;
    private final HouseJoinRequestRepository houseJoinRequestRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Transactional
    public HouseJoinResponse joinByCode(Long userId, String inviteCode) {
        House house = houseRepository.findWithLockByInviteCode(inviteCode)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.INVITE_CODE_INVALID));
        if (house.isInviteExpired()) {
            throw new BusinessException(HouseErrorCode.INVITE_CODE_EXPIRED);
        }

        HouseMember member = join(house, userId);
        houseJoinRequestRepository.findByHouseIdAndUserId(house.getId(), userId)
                .filter(HouseJoinRequest::isPending)
                .ifPresent(HouseJoinRequest::accept);
        return new HouseJoinResponse(member.getId(), house.getId(), member.getStatus());
    }

    // 탐색 목록에서는 즉시가입하지 않고 방장 승인을 기다리는 입주 신청만 생성함.
    @Transactional
    public HouseJoinRequestResponse requestJoin(Long userId, Long houseId) {
        House house = houseRepository.findWithLockById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));

        HouseMember existingMember = houseMemberRepository.findByHouseIdAndUserId(houseId, userId)
                .orElse(null);
        if (existingMember != null && existingMember.isActive()) {
            throw new BusinessException(HouseErrorCode.HOUSE_ALREADY_MEMBER);
        }
        if (existingMember != null && existingMember.isKicked()) {
            throw new BusinessException(HouseErrorCode.HOUSE_KICKED_MEMBER);
        }
        HouseJoinRequest request = houseJoinRequestRepository.findByHouseIdAndUserId(houseId, userId)
                .orElse(null);
        if (request != null && request.isPending()) {
            throw new BusinessException(HouseErrorCode.HOUSE_JOIN_REQUEST_ALREADY_PENDING);
        }
        if (house.isFull()) {
            throw new BusinessException(HouseErrorCode.HOUSE_FULL);
        }
        if (request == null) {
            request = houseJoinRequestRepository.save(
                    HouseJoinRequest.create(house, userRepository.getReferenceById(userId)));
        } else {
            request.reopen();
        }
        return HouseJoinRequestResponse.of(request);
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

    @Transactional
    public HouseJoinDetailResponse acceptRequest(Long ownerUserId, Long houseId, Long requestId) {
        House house = findHouseWithLock(houseId);
        requireOwner(house, ownerUserId);
        HouseJoinRequest request = findPendingRequestWithLock(houseId, requestId);

        HouseMember member = join(house, request.getUser().getId());
        request.accept();
        return HouseJoinDetailResponse.of(member, houseId, request.getUser().getId());
    }

    @Transactional
    public void rejectRequest(Long ownerUserId, Long houseId, Long requestId) {
        House house = findHouseWithLock(houseId);
        requireOwner(house, ownerUserId);
        findPendingRequestWithLock(houseId, requestId).reject();
    }

    // 공용 참여 판정: 중복(active)/강퇴 이력 -> 정원 -> 재활성화 또는 신규 등록 -> 구성원 수 증가.
    // 호출자는 house 를 행 락으로 조회한 상태여야 한다.
    private HouseMember join(House house, Long userId) {
        HouseMember existing = houseMemberRepository.findByHouseIdAndUserId(house.getId(), userId)
                .orElse(null);
        if (existing != null && existing.isActive()) {
            throw new BusinessException(HouseErrorCode.HOUSE_ALREADY_MEMBER);
        }
        if (existing != null && existing.isKicked()) {
            // 강퇴 이력은 재가입 불가 - LEFT 재활성화와 구분.
            throw new BusinessException(HouseErrorCode.HOUSE_KICKED_MEMBER);
        }
        if (house.isFull()) {
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
                    HouseMember.create(house, userRepository.getReferenceById(userId), HouseMemberRole.MEMBER));
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

    // 입주 알림 - 가입과 같은 트랜잭션에서 동기 저장(응원 #174 패턴). push 만 커밋 후 비동기로 나감.
    private void notifyMemberJoined(List<HouseMember> recipients, HouseMember joined) {
        if (recipients.isEmpty()) {
            return;
        }
        var content = NotificationMessages.houseMemberJoined(joined.getUser().getNickname());
        recipients.forEach(recipient -> notificationService.send(
                recipient.getUser().getId(), content, joined.getId()));
    }

    @Transactional(readOnly = true)
    public HousePreviewResponse preview(String inviteCode) {
        House house = houseRepository.findByInviteCode(inviteCode)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.INVITE_CODE_INVALID));
        return HousePreviewResponse.of(house);
    }
}
