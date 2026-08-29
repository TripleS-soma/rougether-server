package com.triples.rougether.batch.eveningdigest;

import com.triples.rougether.batch.reminder.ReminderPushWriter;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

// Step1이 23:59에 시작해도 자정을 넘긴 전날 digest는 실제 FCM 발송 직전에 만료시킨다.
@RequiredArgsConstructor
class EveningDigestPushWriter implements ItemWriter<Notification> {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ReminderPushWriter delegate;
    private final DailyIncompleteDigestRepository digestRepository;
    private final Clock clock;

    @Override
    public void write(Chunk<? extends Notification> chunk) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        Chunk<Notification> eligible = new Chunk<>();
        for (Notification notification : chunk) {
            if (notification.getRefId() == null) {
                continue;
            }
            digestRepository.findById(notification.getRefId())
                    .filter(digest -> digest.getDigestDate().equals(today))
                    .ifPresent(ignored -> eligible.add(notification));
        }
        if (!eligible.isEmpty()) {
            delegate.write(eligible);
        }
    }
}
