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

// PENDING 알림 chunk 를 설정 게이트(NotificationPushPolicy) → FCM 발송 → push_status 갱신으로 종결한다.
// 알림 타입에 의존하지 않는 범용 발송 writer 라 weeklyReportPushJob 도 재사용함(public 인 이유)
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderPushWriter implements ItemWriter<Notification> {

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
            // 리마인드 외 타입(주간 회고 등)도 이 writer 를 지나므로 타입을 함께 남김
            log.warn("알림 FCM 발송 실패 - notificationId={}, type={}", notificationId, notification.getType(), e);
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
