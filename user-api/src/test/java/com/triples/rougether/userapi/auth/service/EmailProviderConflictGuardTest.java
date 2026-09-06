package com.triples.rougether.userapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.OauthAccount;
import com.triples.rougether.domain.member.entity.OauthProvider;
import com.triples.rougether.domain.member.repository.OauthAccountRepository;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 같은 이메일 타 provider 계정 안내(409) 판정 규칙만 검증함. 실제 DB 경로는 KakaoLoginIntegrationTest 가 검증함.
@ExtendWith(MockitoExtension.class)
class EmailProviderConflictGuardTest {

    @Mock
    private OauthAccountRepository oauthAccountRepository;

    @InjectMocks
    private EmailProviderConflictGuard guard;

    @Test
    void 같은_이메일의_활성_계정이_다른_provider_로_있으면_409_와_providers_를_던진다() {
        when(oauthAccountRepository.findByProviderAndProviderUserId(OauthProvider.KAKAO, "k1"))
                .thenReturn(Optional.empty());
        when(oauthAccountRepository.findProvidersOfActiveUsersByEmail("a@b.com"))
                .thenReturn(List.of(OauthProvider.GOOGLE, OauthProvider.APPLE));

        assertThatThrownBy(() -> guard.ensureNewAccountAllowed(OauthProvider.KAKAO, "k1", "a@b.com", true, false))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.EMAIL_LINKED_TO_OTHER_PROVIDER);
                    // enum 순서(KAKAO, APPLE, GOOGLE)로 고정.
                    assertThat(e.getMessage()).isEqualTo("이 이메일은 애플·구글 로그인으로 가입되어 있어요.");
                    assertThat(e.getDetails()).containsEntry("providers", List.of("APPLE", "GOOGLE"));
                });
    }

    @Test
    void allowNewAccount_면_조회_없이_통과한다() {
        assertThatCode(() -> guard.ensureNewAccountAllowed(OauthProvider.KAKAO, "k1", "a@b.com", true, true))
                .doesNotThrowAnyException();
        verifyNoInteractions(oauthAccountRepository);
    }

    @Test
    void 이메일이_없거나_미인증이면_조회_없이_통과한다() {
        assertThatCode(() -> guard.ensureNewAccountAllowed(OauthProvider.KAKAO, "k1", null, false, false))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.ensureNewAccountAllowed(OauthProvider.KAKAO, "k1", "  ", true, false))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.ensureNewAccountAllowed(OauthProvider.KAKAO, "k1", "a@b.com", false, false))
                .doesNotThrowAnyException();
        verifyNoInteractions(oauthAccountRepository);
    }

    @Test
    void 이미_연동된_회원의_재로그인은_대상이_아니다() {
        when(oauthAccountRepository.findByProviderAndProviderUserId(OauthProvider.APPLE, "a1"))
                .thenReturn(Optional.of(mock(OauthAccount.class)));

        assertThatCode(() -> guard.ensureNewAccountAllowed(OauthProvider.APPLE, "a1", "a@b.com", true, false))
                .doesNotThrowAnyException();
        verify(oauthAccountRepository, never()).findProvidersOfActiveUsersByEmail(any());
    }

    @Test
    void 같은_provider_만_있거나_아무_계정도_없으면_통과한다() {
        when(oauthAccountRepository.findByProviderAndProviderUserId(OauthProvider.KAKAO, "k1"))
                .thenReturn(Optional.empty());
        when(oauthAccountRepository.findProvidersOfActiveUsersByEmail("a@b.com"))
                .thenReturn(List.of(OauthProvider.KAKAO))
                .thenReturn(List.of());

        assertThatCode(() -> guard.ensureNewAccountAllowed(OauthProvider.KAKAO, "k1", "a@b.com", true, false))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.ensureNewAccountAllowed(OauthProvider.KAKAO, "k1", "a@b.com", true, false))
                .doesNotThrowAnyException();
    }
}
