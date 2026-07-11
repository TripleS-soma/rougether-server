package com.triples.rougether.userapi.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.notification.entity.DevicePlatform;
import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.userapi.notification.error.DeviceTokenErrorCode;
import com.triples.rougether.userapi.notification.service.DeviceTokenService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeviceTokenServiceTest {

    @Mock private UserDeviceTokenRepository userDeviceTokenRepository;
    @InjectMocks private DeviceTokenService deviceTokenService;

    private User userWithId(Long id) {
        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(id);
        return user;
    }

    @Test
    void 등록은_DB_upsert로_원자적으로_처리된다() {
        deviceTokenService.register(1L, "token-a", DevicePlatform.IOS);

        ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(userDeviceTokenRepository).upsert(eq(1L), eq("token-a"), eq("IOS"), nowCaptor.capture());
        assertThat(nowCaptor.getValue()).isNotNull();
    }

    // check-then-insert 없이 단일 upsert 호출로 끝나므로, 같은 token으로 동시에 register()가 두 번 불려도
    // 각각 독립적인 upsert 쿼리만 나갈 뿐 findByToken 재조회·조건 분기가 없어 경합 자체가 없다.
    @Test
    void 다른_user가_같은_token으로_등록해도_각각_upsert만_호출한다() {
        deviceTokenService.register(1L, "token-a", DevicePlatform.IOS);
        deviceTokenService.register(2L, "token-a", DevicePlatform.ANDROID);

        verify(userDeviceTokenRepository).upsert(eq(1L), eq("token-a"), eq("IOS"), any());
        verify(userDeviceTokenRepository).upsert(eq(2L), eq("token-a"), eq("ANDROID"), any());
    }

    @Test
    void 본인_토큰을_삭제한다() {
        User user = userWithId(1L);
        UserDeviceToken existing = UserDeviceToken.register(user, "token-a", DevicePlatform.IOS, Instant.EPOCH);
        when(userDeviceTokenRepository.findByToken("token-a")).thenReturn(Optional.of(existing));

        deviceTokenService.delete(1L, "token-a");

        verify(userDeviceTokenRepository).delete(existing);
    }

    @Test
    void 타인_토큰_삭제는_거부한다() {
        User owner = userWithId(1L);
        UserDeviceToken existing = UserDeviceToken.register(owner, "token-a", DevicePlatform.IOS, Instant.EPOCH);
        when(userDeviceTokenRepository.findByToken("token-a")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> deviceTokenService.delete(2L, "token-a"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(DeviceTokenErrorCode.DEVICE_TOKEN_NOT_FOUND));
        verify(userDeviceTokenRepository, never()).delete(any());
    }

    @Test
    void 존재하지_않는_토큰_삭제는_거부한다() {
        when(userDeviceTokenRepository.findByToken("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceTokenService.delete(1L, "missing"))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(DeviceTokenErrorCode.DEVICE_TOKEN_NOT_FOUND));
    }

    @Test
    void 무효_토큰_목록을_bulk_삭제한다() {
        deviceTokenService.deleteAllByToken(List.of("token-a", "token-b"));

        verify(userDeviceTokenRepository).deleteAllByTokenIn(List.of("token-a", "token-b"));
    }
}
