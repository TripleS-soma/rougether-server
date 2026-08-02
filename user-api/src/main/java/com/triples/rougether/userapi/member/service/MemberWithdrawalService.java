package com.triples.rougether.userapi.member.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseJoinRequest;
import com.triples.rougether.domain.house.entity.HouseJoinRequestStatus;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseJoinRequestRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.OauthAccount;
import com.triples.rougether.domain.member.entity.OauthProvider;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.goal.repository.UserGoalRepository;
import com.triples.rougether.domain.member.repository.OauthAccountRepository;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.notification.repository.NotificationSettingRepository;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.userapi.auth.client.AppleRevokeClient;
import com.triples.rougether.userapi.auth.client.KakaoUnlinkClient;
import com.triples.rougether.userapi.auth.service.AppleRefreshTokenCipher;
import com.triples.rougether.userapi.global.storage.AssetStorageService;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 회원탈퇴(soft delete). users.deleted_at 세팅 + refresh 전량 폐기 + oauth 연동 삭제를 한 트랜잭션으로 처리하고,
// provider 측 연동 해제(카카오 unlink·애플 revoke)는 커밋 이후 best-effort로 호출함(NotificationService push 컨벤션과 동일).
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberWithdrawalService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OauthAccountRepository oauthAccountRepository;
    private final HouseRepository houseRepository;
    private final HouseMemberRepository houseMemberRepository;
    private final HouseJoinRequestRepository houseJoinRequestRepository;
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationSettingRepository notificationSettingRepository;
    private final UserGoalRepository userGoalRepository;
    private final RoutineRepository routineRepository;
    private final TodoRepository todoRepository;
    private final CategoryRepository categoryRepository;
    private final AppleRefreshTokenCipher appleRefreshTokenCipher;
    private final KakaoUnlinkClient kakaoUnlinkClient;
    private final AppleRevokeClient appleRevokeClient;
    private final AssetStorageService assetStorageService;
    private final ApplicationEventPublisher eventPublisher;

    // 주의: 내부 bulk delete(clearAutomatically)가 영속성 컨텍스트 전체를 비움 —
    // 외부 트랜잭션에 참여시켜 호출하면 호출자 엔티티의 이후 dirty 변경이 유실될 수 있음.
    @Transactional
    public void withdraw(Long userId) {
        // 이미 탈퇴한 회원의 재요청은 404 — 인증이 살아있는 30분 내 중복 호출 케이스.
        User user = userRepository.findByIdForUpdate(userId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));

        // 삭제 전에 provider 목록·revoke 재료를 스냅샷함(row는 아래에서 삭제됨).
        List<OauthAccount> accounts = oauthAccountRepository.findAllByUser(user);
        List<ProviderRevocation> revocations = accounts.stream()
                .map(a -> new ProviderRevocation(
                        a.getProvider(), a.getProviderUserId(), a.getAppleRefreshTokenEncrypted()))
                .toList();
        // 익명화가 key를 지우므로 커밋 후 S3 원본 삭제용으로 먼저 스냅샷함.
        String profileImageKey = user.getProfileImageKey();

        Instant now = Instant.now();
        user.softDelete(now);
        // 개인정보 즉시 파기(익명화). 이하 bulk 연산의 flush에 함께 실려 나감.
        user.anonymize();
        refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(userId)
                .forEach(token -> token.revoke(now));
        // 연동 삭제로 (provider, provider_user_id) unique가 풀려 재로그인 = 신규 가입(재가입 허용).
        oauthAccountRepository.deleteAllByUser(user);
        // 집 도메인 정리(엔티티 dirty 단계) — 신청 철회·멤버십 LEFT·정원 감소·소유권 승계/집 해체.
        List<Long> cleanedHouseIds = cleanupHouseDomain(userId);
        // 떠난 집들과의 연동 해제(bulk) — 강퇴·집 나가기의 부수 정리와 동일. 이하 bulk 단계.
        for (Long houseId : cleanedHouseIds) {
            routineRepository.clearHouseMissionLinksOfMember(userId, houseId);
            categoryRepository.clearHouseLinkOfMember(userId, houseId);
        }
        // 개인 전용 데이터(루틴·투두·카테고리) 연쇄 soft delete. 완료 이력·스트릭은 보존함(집 통계 의존).
        // 루틴 soft delete로 리마인더 후보에서도 자연 제외됨.
        routineRepository.softDeleteAllByUserId(userId, now);
        todoRepository.softDeleteAllByUserId(userId, now);
        categoryRepository.softDeleteAllByUserId(userId, now);
        // 본인 전용 데이터(알림 수신함·알림 설정·온보딩 목표)는 row 수가 적어 유예 없이 즉시 파기함.
        // 대량 데이터(로그·인증사진 row 등)는 purge 배치(batch 모듈)가 하드 삭제함.
        notificationRepository.deleteAllByUserId(userId);
        notificationSettingRepository.deleteAllByUserId(userId);
        userGoalRepository.deleteAllByUserId(userId);
        // FCM 토큰 삭제 — 잔여 push 경로 차단. clearAutomatically 라 반드시 마지막 순서 유지:
        // 위 bulk soft delete 이후 PC에 남은 stale 루틴/투두/카테고리를 여기서 함께 비움.
        userDeviceTokenRepository.deleteAllByUserId(userId);

        eventPublisher.publishEvent(new MemberWithdrawnEvent(userId, revocations, profileImageKey));
    }

    // 집·초대 도메인 정리. 반환값은 연동 해제(bulk)가 필요한 집 id 목록.
    // 정리 후 잔여 PENDING 신청(동시 신청 race)은 승인 경로의 탈퇴 가드가 거절 처리함.
    private List<Long> cleanupHouseDomain(Long userId) {
        houseJoinRequestRepository.findAllByUserIdAndStatus(userId, HouseJoinRequestStatus.PENDING)
                .forEach(HouseJoinRequest::reject);

        // house 행 락은 id 오름차순으로 잡음 — 다른 탈퇴·가입 트랜잭션과의 락 순서 불변식.
        List<Long> houseIds = houseMemberRepository
                .findHouseIdsByUserIdAndStatus(userId, HouseMemberStatus.ACTIVE).stream()
                .sorted()
                .toList();
        List<Long> cleanedHouseIds = new ArrayList<>();
        for (Long houseId : houseIds) {
            House house = houseRepository.findWithLockById(houseId)
                    .filter(found -> !found.isDeleted())
                    .orElse(null);
            if (house == null) {
                continue;
            }
            // 락 조회(current read) — 스냅샷 조회는 락 획득 전 커밋된 강퇴·가입을 못 봄(정원 이중 감소 방지).
            List<HouseMember> activeMembers = houseMemberRepository
                    .findAllWithLockByHouseIdAndStatus(houseId, HouseMemberStatus.ACTIVE);
            HouseMember leaver = activeMembers.stream()
                    .filter(member -> member.getUser().getId().equals(userId))
                    .findFirst()
                    .orElse(null);
            if (leaver == null) {
                // 락 대기 중 강퇴 등으로 이미 비활성 — 이 집은 정리할 것 없음.
                continue;
            }

            leaver.leave();
            house.decreaseMemberCount();
            if (leaver.isOwner()) {
                transferOwnershipOrDissolve(house, leaver, activeMembers);
            }
            cleanedHouseIds.add(houseId);
        }
        return cleanedHouseIds;
    }

    // 소유 집 승계 — 남은 ACTIVE 멤버 중 가입일 최선임(동률 시 membership id 오름차순, 조회 정렬이 보장).
    // 남은 멤버가 없으면 집 해체(soft delete) — 초대코드 가입 경로도 deleted 필터로 함께 막힘.
    private void transferOwnershipOrDissolve(House house, HouseMember leaver,
                                             List<HouseMember> activeMembers) {
        HouseMember successor = activeMembers.stream()
                .filter(member -> !member.getId().equals(leaver.getId()))
                .findFirst()
                .orElse(null);
        if (successor == null) {
            house.softDelete();
            return;
        }
        leaver.demoteToMember();
        successor.promoteToOwner();
        house.changeOwner(successor.getUser());
    }

    // 커밋 이후 provider 연동 해제·프로필 이미지 원본 파기. 실패해도 탈퇴는 이미 확정 — 로그만 남기고 재시도 없음(MVP).
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onMemberWithdrawn(MemberWithdrawnEvent event) {
        for (ProviderRevocation revocation : event.revocations()) {
            try {
                revokeProvider(revocation);
            } catch (Exception e) {
                // providerUserId 는 탈퇴자의 소셜 식별자라 로그에 남기지 않음(userId로 추적).
                log.warn("탈퇴 후 {} 연동 해제 실패 - userId={}", revocation.provider(), event.userId(), e);
            }
        }
        if (event.profileImageKey() != null) {
            try {
                assetStorageService.delete(event.profileImageKey());
            } catch (Exception e) {
                log.warn("탈퇴 후 프로필 이미지 원본 삭제 실패 - userId={}", event.userId(), e);
            }
        }
    }

    private void revokeProvider(ProviderRevocation revocation) {
        switch (revocation.provider()) {
            case KAKAO -> kakaoUnlinkClient.unlink(revocation.providerUserId());
            case APPLE -> {
                // authorizationCode 교환 도입 전에 가입한 연동은 저장된 토큰이 없어 revoke를 건너뜀.
                if (revocation.appleRefreshTokenEncrypted() == null) {
                    log.warn("애플 refresh token 미보유로 revoke 생략");
                    return;
                }
                appleRevokeClient.revoke(
                        appleRefreshTokenCipher.decrypt(revocation.appleRefreshTokenEncrypted()));
            }
            // 구글은 id_token만 사용해 서버가 보유한 토큰이 없음 — 연동 row 삭제로 충족.
            case GOOGLE -> { }
        }
    }

    public record MemberWithdrawnEvent(Long userId, List<ProviderRevocation> revocations,
                                       String profileImageKey) {
    }

    public record ProviderRevocation(OauthProvider provider, String providerUserId,
                                     String appleRefreshTokenEncrypted) {
    }
}
