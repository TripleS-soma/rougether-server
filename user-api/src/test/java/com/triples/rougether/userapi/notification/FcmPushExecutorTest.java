package com.triples.rougether.userapi.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import com.triples.rougether.userapi.notification.fcm.FcmSender;
import com.triples.rougether.userapi.notification.service.DeviceTokenService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FcmPushExecutorTest {

    @Mock private UserDeviceTokenRepository userDeviceTokenRepository;
    @Mock private DeviceTokenService deviceTokenService;
    @Mock private FcmSender fcmSender;
    @InjectMocks private FcmPushExecutor fcmPushExecutor;

    private UserDeviceToken tokenOf(String token) {
        UserDeviceToken deviceToken = mock(UserDeviceToken.class);
        when(deviceToken.getToken()).thenReturn(token);
        return deviceToken;
    }

    @Test
    void 사용자_토큰_전체로_멀티캐스트_발송하고_무효_토큰_삭제를_DeviceTokenService에_위임한다() {
        List<UserDeviceToken> tokens = List.of(tokenOf("token-1"), tokenOf("token-2"));
        when(userDeviceTokenRepository.findAllByUserId(1L)).thenReturn(tokens);
        when(fcmSender.send(List.of("token-1", "token-2"), "제목", "본문"))
                .thenReturn(List.of("token-2"));

        fcmPushExecutor.push(1L, "제목", "본문");

        verify(deviceTokenService).deleteAllByToken(1L, List.of("token-2"));
    }

    @Test
    void 등록된_토큰이_없으면_발송하지_않는다() {
        when(userDeviceTokenRepository.findAllByUserId(1L)).thenReturn(List.of());

        fcmPushExecutor.push(1L, "제목", "본문");

        verify(fcmSender, never()).send(any(), any(), any());
        verify(deviceTokenService, never()).deleteAllByToken(any(), any());
    }

    @Test
    void 무효_토큰이_없으면_삭제를_호출하지_않는다() {
        List<UserDeviceToken> tokens = List.of(tokenOf("token-1"));
        when(userDeviceTokenRepository.findAllByUserId(1L)).thenReturn(tokens);
        when(fcmSender.send(any(), any(), any())).thenReturn(List.of());

        fcmPushExecutor.push(1L, "제목", "본문");

        verify(deviceTokenService, never()).deleteAllByToken(any(), any());
    }
}
