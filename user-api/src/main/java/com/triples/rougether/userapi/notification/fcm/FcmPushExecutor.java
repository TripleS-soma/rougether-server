package com.triples.rougether.userapi.notification.fcm;

import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.infra.fcm.FcmSender;
import com.triples.rougether.userapi.notification.service.DeviceTokenService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

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
