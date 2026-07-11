package com.triples.rougether.userapi.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.notification.entity.DevicePlatform;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.userapi.notification.error.DeviceTokenErrorCode;
import com.triples.rougether.userapi.notification.service.DeviceTokenService;
import java.time.Instant;
import java.util.List;
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

    // 삭제는 findByToken 후 PK delete가 아니라, 소유권 조건이 포함된 단일 DELETE 한 방으로 끝나야 함.
    // (조회~삭제 사이 upsert 소유권 이전 경합 방지 — 실제 원자성은 UserDeviceTokenRepositoryTest에서 검증)
    @Test
    void 본인_토큰을_소유권_조건이_포함된_단일_DELETE로_삭제한다() {
        when(userDeviceTokenRepository.deleteByTokenAndUserId("token-a", 1L)).thenReturn(1);

        deviceTokenService.delete(1L, "token-a");

        verify(userDeviceTokenRepository).deleteByTokenAndUserId("token-a", 1L);
        verify(userDeviceTokenRepository, never()).findByToken(any());
    }

    @Test
    void 삭제된_행이_없으면_NOT_FOUND를_던진다() {
        when(userDeviceTokenRepository.deleteByTokenAndUserId("token-a", 2L)).thenReturn(0);

        assertThatThrownBy(() -> deviceTokenService.delete(2L, "token-a"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(DeviceTokenErrorCode.DEVICE_TOKEN_NOT_FOUND));
    }

    @Test
    void 무효_토큰_목록을_발송_대상_사용자_스코프로_bulk_삭제한다() {
        deviceTokenService.deleteAllByToken(1L, List.of("token-a", "token-b"));

        verify(userDeviceTokenRepository).deleteAllByTokenInAndUserId(List.of("token-a", "token-b"), 1L);
    }
}
