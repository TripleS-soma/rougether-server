package com.triples.rougether.batch.eveningdigest;

import com.triples.rougether.domain.notification.entity.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.listener.SkipListener;

@Slf4j
final class EveningDigestPushSkipLogger implements SkipListener<Notification, Notification> {

    @Override
    public void onSkipInRead(Throwable t) {
        log.warn("저녁 미완료 알림 push reader skip", t);
    }

    @Override
    public void onSkipInProcess(Notification notification, Throwable t) {
        log.warn("저녁 미완료 알림 push process skip - notificationId={}", notification.getId(), t);
    }

    @Override
    public void onSkipInWrite(Notification notification, Throwable t) {
        log.warn("저녁 미완료 알림 push write skip - notificationId={}, userId={}",
                notification.getId(), notification.getUser().getId(), t);
    }
}
