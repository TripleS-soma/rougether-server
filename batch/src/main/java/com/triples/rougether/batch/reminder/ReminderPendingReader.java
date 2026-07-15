package com.triples.rougether.batch.reminder;

import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.data.domain.PageRequest;

// ReminderCandidateReader와 동일한 이유로 offset 대신 id 커서(id > cursorId)로 페이징한다 - writer가
// 처리된 알림을 PENDING에서 빼내(push_status 갱신) 필터에서 빠지므로 offset이면 뒤 구간이 스킵된다
@RequiredArgsConstructor
class ReminderPendingReader implements ItemReader<Notification> {

    private static final int PAGE_SIZE = 200;

    private final NotificationRepository notificationRepository;

    private Iterator<Notification> currentBatch = Collections.emptyIterator();
    private Long cursorId = 0L;
    private boolean exhausted = false;

    @Override
    public Notification read() {
        if (!currentBatch.hasNext() && !exhausted) {
            List<Notification> batch = fetchNextBatch();
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

    private List<Notification> fetchNextBatch() {
        return notificationRepository.findByTypeAndPushStatusAndIdGreaterThanOrderByIdAsc(
                NotificationType.ROUTINE_REMINDER, PushStatus.PENDING, cursorId, PageRequest.of(0, PAGE_SIZE));
    }
}
