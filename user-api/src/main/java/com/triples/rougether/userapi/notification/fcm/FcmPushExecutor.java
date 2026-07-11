package com.triples.rougether.userapi.notification.fcm;

import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.userapi.notification.service.DeviceTokenService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

// Notification 저장 트랜잭션과 분리된 스레드(비동기)에서 실행됨 — push 실패해도 이미 저장된 알림 내역은 영향받지 않음(best-effort).
// 무효 토큰 삭제는 이 스레드에 주변 트랜잭션이 없어 직접 하지 않고 @Transactional이 걸린 DeviceTokenService에 위임한다.
@Component
@RequiredArgsConstructor
public class FcmPushExecutor {

    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final DeviceTokenService deviceTokenService;
    private final FcmSender fcmSender;

    @Async("notificationTaskExecutor")
    public void push(Long userId, String title, String body) {
        List<String> tokens = userDeviceTokenRepository.findAllByUserId(userId).stream()
                .map(UserDeviceToken::getToken)
                .toList();
        if (tokens.isEmpty()) {
            return;
        }

        List<String> invalidTokens = fcmSender.send(tokens, title, body);
        if (!invalidTokens.isEmpty()) {
            deviceTokenService.deleteAllByToken(userId, invalidTokens);
        }
    }
}
