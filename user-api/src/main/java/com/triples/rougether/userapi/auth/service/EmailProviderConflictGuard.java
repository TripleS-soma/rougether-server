package com.triples.rougether.userapi.auth.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.OauthProvider;
import com.triples.rougether.domain.member.repository.OauthAccountRepository;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 소셜 최초 가입 직전 가드: 같은 이메일로 가입된 활성 계정이 **다른 provider** 로 있으면 새 계정을 만들지 않고
// 409(AUTH_EMAIL_LINKED_TO_OTHER_PROVIDER)로 안내함. 앱이 "이 이메일은 애플 로그인으로 가입되어 있어요"를 띄우고
// [애플로 로그인] 또는 [새 계정으로 계속](allowNewAccount=true 재요청)을 고르게 함 —
// 재설치 뒤 provider 를 잘못 골라 빈 계정이 생기는 사고를 막음.
// 검사는 provider 토큰 검증 뒤·가입 트랜잭션 앞(애플은 1회용 authorizationCode 교환 앞)에서 수행하고,
// provider 가 인증한 이메일(emailVerified)에만 적용해 미인증 이메일로 타인 계정의 존재·provider 를 캐지 못하게 함.
// 안내이지 유니크 제약이 아님 — 별도 readOnly 트랜잭션이라 같은 이메일의 동시 최초가입 2건은 둘 다 통과할 수 있고,
// 저장된 users.email 은 가입 시 검증 없이 들어온 값이라 안내가 사실과 다를 가능성도 남는다(후속: email_verified 저장).
@Component
@RequiredArgsConstructor
public class EmailProviderConflictGuard {

    private static final Map<OauthProvider, String> PROVIDER_LABELS = Map.of(
            OauthProvider.KAKAO, "카카오",
            OauthProvider.APPLE, "애플",
            OauthProvider.GOOGLE, "구글");

    private final OauthAccountRepository oauthAccountRepository;

    @Transactional(readOnly = true)
    public void ensureNewAccountAllowed(OauthProvider provider, String providerUserId, String email,
                                        boolean emailVerified, boolean allowNewAccount) {
        if (allowNewAccount || email == null || email.isBlank() || !emailVerified) {
            return;
        }
        // 이미 연동된 회원의 재로그인은 가입이 아니므로 대상 아님.
        if (oauthAccountRepository.findByProviderAndProviderUserId(provider, providerUserId).isPresent()) {
            return;
        }
        // enum 순서로 고정해 message·details 가 결정적이게 함.
        List<OauthProvider> otherProviders = oauthAccountRepository.findProvidersOfActiveUsersByEmail(email).stream()
                .filter(linked -> linked != provider)
                .sorted()
                .toList();
        if (otherProviders.isEmpty()) {
            return;
        }
        throw new BusinessException(AuthErrorCode.EMAIL_LINKED_TO_OTHER_PROVIDER, message(otherProviders),
                Map.of("providers", otherProviders.stream().map(Enum::name).toList()));
    }

    static String message(List<OauthProvider> providers) {
        // 라벨 미등록 provider 가 생겨도 "null 로그인" 이 되지 않게 enum 이름으로 폴백함.
        String labels = providers.stream()
                .map(p -> PROVIDER_LABELS.getOrDefault(p, p.name()))
                .collect(Collectors.joining("·"));
        return "이 이메일은 " + labels + " 로그인으로 가입되어 있어요.";
    }
}
