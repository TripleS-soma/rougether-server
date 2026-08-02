package com.triples.rougether.batch.reminder;

import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.policy.NotificationPushPolicy;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.notification.repository.NotificationSettingRepository;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.infra.fcm.FcmSendResult;
import com.triples.rougether.infra.fcm.FcmSender;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class ReminderPushWriter implements ItemWriter<Notification> {

    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationSettingRepository notificationSettingRepository;
    private final FcmSender fcmSender;

    @Override
    public void write(Chunk<? extends Notification> chunk) {
        NotificationPushPolicy pushPolicy = loadPushPolicy(chunk);
        for (Notification notification : chunk) {
            if (!pushPolicy.isPushAllowed(notification.getUser().getId(), notification.getType())) {
                notificationRepository.updatePushStatus(notification.getId(), PushStatus.BLOCKED);
                continue;
            }
            push(notification);
        }
    }

    private NotificationPushPolicy loadPushPolicy(Chunk<? extends Notification> chunk) {
        Set<Long> userIds = new LinkedHashSet<>();
        for (Notification notification : chunk) {
            userIds.add(notification.getUser().getId());
        }
        return NotificationPushPolicy.of(notificationSettingRepository.findAllByUserIdIn(userIds));
    }

    private void push(Notification notification) {
        Long notificationId = notification.getId();
        Long userId = notification.getUser().getId();
        List<String> tokens = userDeviceTokenRepository.findAllByUserId(userId).stream()
                .map(UserDeviceToken::getToken)
                .toList();
        if (tokens.isEmpty()) {
            notificationRepository.updatePushStatus(notificationId, PushStatus.FAILED);
            return;
        }

        FcmSendResult result;
        try {
            result = fcmSender.send(tokens, notification.getTitle(), notification.getBody());
        } catch (Exception e) {
            log.warn("루틴 리마인드 FCM 발송 실패 - notificationId={}", notificationId, e);
            notificationRepository.updatePushStatus(notificationId, PushStatus.FAILED);
            return;
        }

        if (!result.invalidTokens().isEmpty()) {
            userDeviceTokenRepository.deleteAllByTokenInAndUserId(result.invalidTokens(), userId);
        }
        notificationRepository.updatePushStatus(notificationId,
                result.successCount() > 0 ? PushStatus.SENT : PushStatus.FAILED);
    }
}
