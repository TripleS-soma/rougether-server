package com.triples.rougether.userapi.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.DevicePlatform;
import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.userapi.notification.error.DeviceTokenErrorCode;
import com.triples.rougether.userapi.notification.service.DeviceTokenService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeviceTokenServiceTest {

    @Mock private UserDeviceTokenRepository userDeviceTokenRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private DeviceTokenService deviceTokenService;

    private User userWithId(Long id) {
        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(id);
        return user;
    }

    @Test
    void 신규_토큰을_등록한다() {
        User user = userWithId(1L);
        when(userRepository.getReferenceById(1L)).thenReturn(user);
        when(userDeviceTokenRepository.findByToken("token-a")).thenReturn(Optional.empty());

        deviceTokenService.register(1L, "token-a", DevicePlatform.IOS);

        verify(userDeviceTokenRepository).save(any(UserDeviceToken.class));
    }

    @Test
    void 같은_user가_같은_token을_재등록하면_갱신만_하고_새로_저장하지_않는다() {
        User user = userWithId(1L);
        when(userRepository.getReferenceById(1L)).thenReturn(user);
        UserDeviceToken existing = UserDeviceToken.register(user, "token-a", DevicePlatform.IOS, Instant.EPOCH);
        when(userDeviceTokenRepository.findByToken("token-a")).thenReturn(Optional.of(existing));

        deviceTokenService.register(1L, "token-a", DevicePlatform.ANDROID);

        assertThat(existing.getPlatform()).isEqualTo(DevicePlatform.ANDROID);
        assertThat(existing.getUpdatedAt()).isAfter(Instant.EPOCH);
        verify(userDeviceTokenRepository, never()).save(any());
    }

    @Test
    void 다른_user가_등록했던_token이면_소유자를_이전한다() {
        User owner = userWithId(1L);
        User newUser = userWithId(2L);
        when(userRepository.getReferenceById(2L)).thenReturn(newUser);
        UserDeviceToken existing = UserDeviceToken.register(owner, "token-a", DevicePlatform.IOS, Instant.EPOCH);
        when(userDeviceTokenRepository.findByToken("token-a")).thenReturn(Optional.of(existing));

        deviceTokenService.register(2L, "token-a", DevicePlatform.IOS);

        assertThat(existing.getUser()).isEqualTo(newUser);
        assertThat(existing.isOwnedBy(2L)).isTrue();
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
}
