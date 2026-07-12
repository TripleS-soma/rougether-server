package com.triples.rougether.userapi.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.infra.fcm.FcmSendResult;
import com.triples.rougether.infra.fcm.FcmSender;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import com.triples.rougether.userapi.notification.service.DeviceTokenService;
import com.triples.rougether.userapi.notification.service.NotificationPushStatusService;
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
    @Mock private NotificationPushStatusService notificationPushStatusService;
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
                .thenReturn(new FcmSendResult(1, List.of("token-2")));

        fcmPushExecutor.push(100L, 1L, "제목", "본문");

        verify(deviceTokenService).deleteAllByToken(1L, List.of("token-2"));
    }

    @Test
    void 하나_이상_성공하면_push_status를_SENT로_갱신한다() {
        List<UserDeviceToken> tokens = List.of(tokenOf("token-1"), tokenOf("token-2"));
        when(userDeviceTokenRepository.findAllByUserId(1L)).thenReturn(tokens);
        when(fcmSender.send(List.of("token-1", "token-2"), "제목", "본문"))
                .thenReturn(new FcmSendResult(1, List.of("token-2")));

        fcmPushExecutor.push(100L, 1L, "제목", "본문");

        verify(notificationPushStatusService).markSent(100L);
        verify(notificationPushStatusService, never()).markFailed(any());
    }

    @Test
    void 전부_실패하면_push_status를_FAILED로_갱신한다() {
        List<UserDeviceToken> tokens = List.of(tokenOf("token-1"), tokenOf("token-2"));
        when(userDeviceTokenRepository.findAllByUserId(1L)).thenReturn(tokens);
        when(fcmSender.send(List.of("token-1", "token-2"), "제목", "본문"))
                .thenReturn(new FcmSendResult(0, List.of("token-1", "token-2")));

        fcmPushExecutor.push(100L, 1L, "제목", "본문");

        verify(notificationPushStatusService).markFailed(100L);
        verify(notificationPushStatusService, never()).markSent(any());
    }

    // FCM 배치 호출 자체가 예외로 실패하면 successCount=0, invalidTokens=[]로 돌아온다(무효 토큰인지
    // 알 수 없으므로 삭제 대상에는 넣지 않음). 이 경우도 성공한 토큰이 없으므로 FAILED로 집계돼야 한다 —
    // invalidTokens 유무만으로 성공 여부를 추론하면 이 케이스를 SENT로 오판하는 회귀가 생긴다.
    @Test
    void 무효_토큰_없이_전부_실패해도_push_status를_FAILED로_갱신한다() {
        List<UserDeviceToken> tokens = List.of(tokenOf("token-1"), tokenOf("token-2"));
        when(userDeviceTokenRepository.findAllByUserId(1L)).thenReturn(tokens);
        when(fcmSender.send(List.of("token-1", "token-2"), "제목", "본문"))
                .thenReturn(FcmSendResult.empty());

        fcmPushExecutor.push(100L, 1L, "제목", "본문");

        verify(notificationPushStatusService).markFailed(100L);
        verify(notificationPushStatusService, never()).markSent(any());
        verify(deviceTokenService, never()).deleteAllByToken(any(), any());
    }

    @Test
    void 등록된_토큰이_없으면_발송하지_않고_push_status를_FAILED로_갱신한다() {
        when(userDeviceTokenRepository.findAllByUserId(1L)).thenReturn(List.of());

        fcmPushExecutor.push(100L, 1L, "제목", "본문");

        verify(fcmSender, never()).send(any(), any(), any());
        verify(deviceTokenService, never()).deleteAllByToken(any(), any());
        verify(notificationPushStatusService).markFailed(100L);
    }

    @Test
    void 발송중_예외가_나면_push_status를_FAILED로_갱신한다() {
        List<UserDeviceToken> tokens = List.of(tokenOf("token-1"));
        when(userDeviceTokenRepository.findAllByUserId(1L)).thenReturn(tokens);
        doThrow(new RuntimeException("fcm down")).when(fcmSender).send(any(), any(), any());

        fcmPushExecutor.push(100L, 1L, "제목", "본문");

        verify(notificationPushStatusService).markFailed(100L);
        verify(deviceTokenService, never()).deleteAllByToken(any(), any());
    }

    @Test
    void 무효_토큰이_없으면_삭제를_호출하지_않는다() {
        List<UserDeviceToken> tokens = List.of(tokenOf("token-1"));
        when(userDeviceTokenRepository.findAllByUserId(1L)).thenReturn(tokens);
        when(fcmSender.send(any(), any(), any())).thenReturn(new FcmSendResult(1, List.of()));

        fcmPushExecutor.push(100L, 1L, "제목", "본문");

        verify(deviceTokenService, never()).deleteAllByToken(any(), any());
    }
}
