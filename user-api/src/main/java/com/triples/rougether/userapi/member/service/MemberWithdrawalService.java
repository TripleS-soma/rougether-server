package com.triples.rougether.userapi.member.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.OauthAccount;
import com.triples.rougether.domain.member.entity.OauthProvider;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.OauthAccountRepository;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
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
    private final UserDeviceTokenRepository userDeviceTokenRepository;
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
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
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
        // 개인 전용 데이터(루틴·투두·카테고리) 연쇄 soft delete. 완료 이력·스트릭은 보존함(집 통계 의존).
        // 루틴 soft delete로 리마인더 후보에서도 자연 제외됨.
        routineRepository.softDeleteAllByUserId(userId, now);
        todoRepository.softDeleteAllByUserId(userId, now);
        categoryRepository.softDeleteAllByUserId(userId, now);
        // FCM 토큰 삭제 — 잔여 push 경로 차단. clearAutomatically 라 반드시 마지막 순서 유지:
        // 위 bulk soft delete 이후 PC에 남은 stale 루틴/투두/카테고리를 여기서 함께 비움.
        userDeviceTokenRepository.deleteAllByUserId(userId);

        eventPublisher.publishEvent(new MemberWithdrawnEvent(userId, revocations, profileImageKey));
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
