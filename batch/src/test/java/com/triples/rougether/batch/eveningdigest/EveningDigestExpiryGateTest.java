package com.triples.rougether.batch.eveningdigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.triples.rougether.batch.reminder.ReminderPushWriter;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigest;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;

class EveningDigestExpiryGateTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TARGET_DATE = LocalDate.of(2030, 9, 1);
    private static final Clock AFTER_MIDNIGHT = Clock.fixed(
            Instant.parse("2030-09-01T15:00:00Z"), KST);

    @Test
    void push_reader는_job_도중_자정을_넘기면_전날_PENDING을_조회하지_않는다() {
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        EveningDigestPendingReader reader = new EveningDigestPendingReader(
                notificationRepository, TARGET_DATE, AFTER_MIDNIGHT);

        assertThat(reader.read()).isNull();
        verifyNoInteractions(notificationRepository);
    }

    @Test
    void push_writer는_reader가_자정_직전에_읽은_전날_알림도_FCM으로_넘기지_않는다() throws Exception {
        ReminderPushWriter delegate = mock(ReminderPushWriter.class);
        DailyIncompleteDigestRepository digestRepository = mock(DailyIncompleteDigestRepository.class);
        Notification notification = mock(Notification.class);
        DailyIncompleteDigest digest = mock(DailyIncompleteDigest.class);
        when(notification.getRefId()).thenReturn(1L);
        when(digest.getDigestDate()).thenReturn(TARGET_DATE);
        when(digestRepository.findById(1L)).thenReturn(Optional.of(digest));
        EveningDigestPushWriter writer = new EveningDigestPushWriter(delegate, digestRepository, AFTER_MIDNIGHT);

        writer.write(new Chunk<>(notification));

        verifyNoInteractions(delegate);
    }
}
