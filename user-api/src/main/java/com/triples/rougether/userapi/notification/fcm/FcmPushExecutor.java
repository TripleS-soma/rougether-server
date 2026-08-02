package com.triples.rougether.userapi.notification.fcm;

import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.infra.fcm.FcmSendResult;
import com.triples.rougether.infra.fcm.FcmSender;
import com.triples.rougether.userapi.notification.service.DeviceTokenService;
import com.triples.rougether.userapi.notification.service.NotificationPushStatusService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FcmPushExecutor {

    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final DeviceTokenService deviceTokenService;
    private final NotificationPushStatusService notificationPushStatusService;
    private final FcmSender fcmSender;

    @Async("notificationTaskExecutor")
    public void push(Long notificationId, Long userId, String title, String body) {
        List<String> tokens = userDeviceTokenRepository.findAllByUserId(userId).stream()
                .map(UserDeviceToken::getToken)
                .toList();
        if (tokens.isEmpty()) {
            log.warn("FCM 발송 실패 - notificationId={}, userId={}, 등록된 디바이스 토큰 없음", notificationId, userId);
            notificationPushStatusService.markFailed(notificationId);
            return;
        }

        FcmSendResult result;
        try {
            result = fcmSender.send(tokens, title, body);
        } catch (Exception e) {
            log.warn("FCM 발송 실패 - notificationId={}", notificationId, e);
            notificationPushStatusService.markFailed(notificationId);
            return;
        }

        if (!result.invalidTokens().isEmpty()) {
            deviceTokenService.deleteAllByToken(userId, result.invalidTokens());
        }

        if (result.successCount() > 0) {
            notificationPushStatusService.markSent(notificationId);
        } else {
            log.warn("FCM 발송 실패 - notificationId={}, userId={}, tokenCount={}, invalidTokenCount={}",
                    notificationId, userId, tokens.size(), result.invalidTokens().size());
            notificationPushStatusService.markFailed(notificationId);
        }
    }
}
