package com.triples.rougether.batch.weeklyreport;

import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.data.domain.PageRequest;

// 주간 회고 push 발송 reader(#330 리뷰 반영). ReminderPendingReader 와 같은 id 커서 패턴이지만, refId 를
// weekly_reports 로 되짚어 대상 주의 회고 알림만 읽는다 — 이전 주에 적재되고 발송 전에 중단된 잔존 PENDING 이
// 다음 주 발송 스텝에 섞이면 "뒤늦은 지난 주 push 없음" 계약이 깨진다(범위 밖 잔존분은 PENDING 인 채 만료).
@RequiredArgsConstructor
class WeeklyReportPushPendingReader implements ItemReader<Notification> {

    private static final int PAGE_SIZE = 200;

    private final NotificationRepository notificationRepository;
    private final LocalDate weekStart;

    private Iterator<Notification> currentBatch = Collections.emptyIterator();
    private Long cursorId = 0L;
    private boolean exhausted = false;

    @Override
    public Notification read() {
        if (!currentBatch.hasNext() && !exhausted) {
            List<Notification> batch = notificationRepository.findWeeklyReportPendingInWeek(
                    NotificationType.WEEKLY_REPORT, PushStatus.PENDING, weekStart, cursorId,
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
