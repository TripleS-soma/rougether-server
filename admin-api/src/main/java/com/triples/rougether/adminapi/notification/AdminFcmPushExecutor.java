package com.triples.rougether.adminapi.notification;

import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.infra.fcm.FcmDeliveryResult;
import com.triples.rougether.infra.fcm.FcmDelivery;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 관리자 답장은 빈도가 낮아 @Async 없이 동기로 발송함
// 커밋 후 리스너에서 호출되므로 DB 반영은 AdminPushResultService가 자체 커밋함
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminFcmPushExecutor {

    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final AdminPushResultService pushResultService;
    private final FcmDelivery fcmDelivery;

    public void push(Long notificationId, Long userId, String title, String body) {
        List<String> tokens = userDeviceTokenRepository.findAllByUserId(userId).stream()
                .map(UserDeviceToken::getToken)
                .toList();
        FcmDeliveryResult result = fcmDelivery.deliver(tokens, title, body, Map::of);
        if (result.status() == FcmDeliveryResult.Status.NO_TOKENS) {
            log.warn("FCM 발송 실패 - notificationId={}, userId={}, 등록된 디바이스 토큰 없음", notificationId, userId);
            pushResultService.markFailed(notificationId);
            return;
        }
        if (result.status() == FcmDeliveryResult.Status.SEND_FAILED) {
            log.warn("FCM 발송 실패 - notificationId={}", notificationId, result.cause());
            pushResultService.markFailed(notificationId);
            return;
        }
        pushResultService.record(notificationId, userId, result, tokens.size());
    }
}
