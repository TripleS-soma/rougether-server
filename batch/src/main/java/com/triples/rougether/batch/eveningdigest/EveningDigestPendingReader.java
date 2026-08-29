package com.triples.rougether.batch.eveningdigest;

import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.data.domain.PageRequest;

@RequiredArgsConstructor
class EveningDigestPendingReader implements ItemReader<Notification> {

    private static final int PAGE_SIZE = 200;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final NotificationRepository notificationRepository;
    private final LocalDate targetDate;
    private final Clock clock;

    private Iterator<Notification> currentBatch = Collections.emptyIterator();
    private long cursorId;
    private boolean exhausted;

    @Override
    public Notification read() {
        if (!targetDate.equals(LocalDate.now(clock.withZone(KST)))) {
            exhausted = true;
            return null;
        }
        if (!currentBatch.hasNext() && !exhausted) {
            List<Notification> batch = notificationRepository.findDailyDigestPending(
                    NotificationType.DAILY_INCOMPLETE_DIGEST,
                    PushStatus.PENDING,
                    targetDate,
                    cursorId,
                    PageRequest.of(0, PAGE_SIZE));
            if (batch.isEmpty()) {
                exhausted = true;
            } else {
                currentBatch = batch.iterator();
            }
        }
        if (!currentBatch.hasNext()) {
            return null;
        }
        Notification next = currentBatch.next();
        cursorId = next.getId();
        return next;
    }
}
