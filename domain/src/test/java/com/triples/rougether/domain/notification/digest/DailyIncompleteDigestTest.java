package com.triples.rougether.domain.notification.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigest;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigestTarget;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigestTargetType;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationSettingType;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DailyIncompleteDigestTest {

    @Test
    void 저녁_digest는_REMINDER_설정_그룹에_속한다() {
        assertThat(NotificationType.DAILY_INCOMPLETE_DIGEST.settingType())
                .isEqualTo(NotificationSettingType.REMINDER);
    }

    @Test
    void 미완료가_없는_digest는_만들_수_없다() {
        User user = mock(User.class);
        LocalDate date = LocalDate.of(2026, 8, 29);

        assertThatThrownBy(() -> DailyIncompleteDigest.create(user, date, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notification을_연결하고_SENT일_때만_sentAt을_기록한다() {
        User user = mock(User.class);
        DailyIncompleteDigest digest = DailyIncompleteDigest.create(user, LocalDate.of(2026, 8, 29), 2, 1);
        Notification notification = Notification.create(user, NotificationType.DAILY_INCOMPLETE_DIGEST,
                "title", "body", 1L);

        digest.linkNotification(notification);
        digest.updatePushStatus(PushStatus.FAILED, Instant.parse("2026-08-29T12:00:00Z"));
        assertThat(digest.getNotification()).isSameAs(notification);
        assertThat(digest.getPushStatus()).isEqualTo(PushStatus.FAILED);
        assertThat(digest.getSentAt()).isNull();

        Instant sentAt = Instant.parse("2026-08-29T12:05:00Z");
        digest.updatePushStatus(PushStatus.SENT, sentAt);

        assertThat(digest.getPushStatus()).isEqualTo(PushStatus.SENT);
        assertThat(digest.getSentAt()).isEqualTo(sentAt);
    }

    @Test
    void 루틴은_계보_id_투두는_todo_id를_target으로_저장한다() {
        DailyIncompleteDigest digest = DailyIncompleteDigest.create(
                mock(User.class), LocalDate.of(2026, 8, 29), 1, 1);

        DailyIncompleteDigestTarget routine = DailyIncompleteDigestTarget.routine(digest, 101L);
        DailyIncompleteDigestTarget todo = DailyIncompleteDigestTarget.todo(digest, 201L);

        assertThat(routine.getDigest()).isSameAs(digest);
        assertThat(routine.getTargetType()).isEqualTo(DailyIncompleteDigestTargetType.ROUTINE);
        assertThat(routine.getTargetId()).isEqualTo(101L);
        assertThat(todo.getTargetType()).isEqualTo(DailyIncompleteDigestTargetType.TODO);
        assertThat(todo.getTargetId()).isEqualTo(201L);
    }
}
