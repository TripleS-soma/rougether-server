package com.triples.rougether.adminapi.notification;

import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.infra.fcm.FcmSendResult;
import com.triples.rougether.infra.fcm.FcmSender;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// user-api FcmPushExecutor 의 어드민 미러 (#348). 어드민 답장은 빈도가 낮아 @Async 없이 동기 발송
// - 답장 응답이 FCM 왕복만큼 늦어지는 것을 감수하고 실행기 풀 설정 스코프를 피한다.
// 트랜잭션 밖(AFTER_COMMIT 리스너)에서 호출되므로 DB 반영은 AdminPushResultService 가 자체 커밋한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminFcmPushExecutor {

    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final AdminPushResultService pushResultService;
    private final FcmSender fcmSender;

    public void push(Long notificationId, Long userId, String title, String body) {
        List<String> tokens = userDeviceTokenRepository.findAllByUserId(userId).stream()
                .map(UserDeviceToken::getToken)
                .toList();
        if (tokens.isEmpty()) {
            log.warn("FCM 발송 실패 - notificationId={}, userId={}, 등록된 디바이스 토큰 없음", notificationId, userId);
            pushResultService.markFailed(notificationId);
            return;
        }

        FcmSendResult result;
        try {
            result = fcmSender.send(tokens, title, body);
        } catch (Exception e) {
            log.warn("FCM 발송 실패 - notificationId={}", notificationId, e);
            pushResultService.markFailed(notificationId);
            return;
        }
        pushResultService.record(notificationId, userId, result, tokens.size());
    }
}
