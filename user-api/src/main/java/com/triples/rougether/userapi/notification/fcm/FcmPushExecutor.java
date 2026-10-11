package com.triples.rougether.userapi.notification.fcm;

import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.infra.fcm.FcmSendResult;
import com.triples.rougether.infra.fcm.FcmSender;
import com.triples.rougether.userapi.notification.service.DeviceTokenService;
import com.triples.rougether.userapi.notification.service.NotificationPushStatusService;
import java.util.List;
import java.util.Map;
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
        deliver(notificationId, userId, title, body, Map.of());
    }

    @Async("notificationTaskExecutor")
    public void push(Long notificationId, Long userId, String title, String body, Map<String, String> data) {
        deliver(notificationId, userId, title, body, data);
    }

    // 알림함에 남기지 않는 푸시(집 채팅 #435). 내역 행이 없으므로 발송 상태도 기록하지 않음.
    // 호출 측(ChatPushNotifier)이 이미 알림 executor 에서 돌기 때문에 동기로 보냄 — 수신자마다 다시 큐에 넣으면
    // 피드·거래소 푸시와 함께 쓰는 큐(100)를 채팅이 빠르게 채움.
    public void pushWithoutRecord(Long userId, String title, String body, Map<String, String> data) {
        deliver(null, userId, title, body, data);
    }

    private void deliver(Long notificationId, Long userId, String title, String body, Map<String, String> data) {
        List<String> tokens = userDeviceTokenRepository.findAllByUserId(userId).stream()
                .map(UserDeviceToken::getToken)
                .toList();
        if (tokens.isEmpty()) {
            if (notificationId == null) {
                // 무기록 푸시(채팅)는 토큰 없는 구성원이 흔해 메시지마다 WARN 이 쌓이므로 낮춤
                log.debug("FCM 발송 생략 - userId={}, 등록된 디바이스 토큰 없음", userId);
            } else {
                log.warn("FCM 발송 실패 - notificationId={}, userId={}, 등록된 디바이스 토큰 없음", notificationId, userId);
            }
            markFailed(notificationId);
            return;
        }

        FcmSendResult result;
        try {
            result = data.isEmpty() ? fcmSender.send(tokens, title, body) : fcmSender.send(tokens, title, body, data);
        } catch (Exception e) {
            log.warn("FCM 발송 실패 - notificationId={}", notificationId, e);
            markFailed(notificationId);
            return;
        }

        if (!result.invalidTokens().isEmpty()) {
            deviceTokenService.deleteAllByToken(userId, result.invalidTokens());
        }

        if (result.successCount() > 0) {
            markSent(notificationId);
        } else {
            log.warn("FCM 발송 실패 - notificationId={}, userId={}, tokenCount={}, invalidTokenCount={}",
                    notificationId, userId, tokens.size(), result.invalidTokens().size());
            markFailed(notificationId);
        }
    }

    // notificationId 가 없으면(pushWithoutRecord) 기록할 행이 없음
    private void markFailed(Long notificationId) {
        if (notificationId != null) {
            notificationPushStatusService.markFailed(notificationId);
        }
    }

    private void markSent(Long notificationId) {
        if (notificationId != null) {
            notificationPushStatusService.markSent(notificationId);
        }
    }
}
