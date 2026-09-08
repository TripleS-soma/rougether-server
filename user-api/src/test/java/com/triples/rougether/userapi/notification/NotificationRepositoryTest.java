package com.triples.rougether.userapi.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.report.entity.WeeklyReport;
import com.triples.rougether.domain.report.repository.WeeklyReportRepository;
import com.triples.rougether.userapi.global.config.JpaConfig;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class NotificationRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private WeeklyReportRepository weeklyReportRepository;
    @Autowired
    private TestEntityManager entityManager;

    private Notification saveNotification(User user) {
        return notificationRepository.save(
                Notification.create(user, NotificationType.ROUTINE_REMINDER, "제목", "내용", null));
    }

    @Test
    void 커서_조회는_본인_알림만_최신순으로_커서_이전부터_가져온다() {
        User me = userRepository.save(User.signUp());
        User other = userRepository.save(User.signUp());
        Notification n1 = saveNotification(me);
        Notification n2 = saveNotification(me);
        Notification n3 = saveNotification(me);
        saveNotification(other);

        List<Notification> firstPage = notificationRepository.findPageByCursor(
                me.getId(), null, PageRequest.of(0, 2));
        assertThat(firstPage).extracting(Notification::getId)
                .containsExactly(n3.getId(), n2.getId());

        List<Notification> nextPage = notificationRepository.findPageByCursor(
                me.getId(), n2.getId(), PageRequest.of(0, 2));
        assertThat(nextPage).extracting(Notification::getId)
                .containsExactly(n1.getId());
    }

    @Test
    void 전체_읽음은_본인의_안읽은_알림만_바꾼다() {
        User me = userRepository.save(User.signUp());
        User other = userRepository.save(User.signUp());
        Notification unread1 = saveNotification(me);
        Notification unread2 = saveNotification(me);
        Notification alreadyRead = saveNotification(me);
        alreadyRead.markRead();
        Notification othersUnread = saveNotification(other);

        int updated = notificationRepository.markAllReadByUserId(me.getId());

        assertThat(updated).isEqualTo(2);
        // bulk update 는 영속성 컨텍스트를 우회하므로 clear 후 재조회.
        entityManager.flush();
        entityManager.clear();
        assertThat(notificationRepository.findById(unread1.getId()).orElseThrow().isRead()).isTrue();
        assertThat(notificationRepository.findById(unread2.getId()).orElseThrow().isRead()).isTrue();
        assertThat(notificationRepository.findById(alreadyRead.getId()).orElseThrow().isRead()).isTrue();
        assertThat(notificationRepository.findById(othersUnread.getId()).orElseThrow().isRead()).isFalse();
    }

    @Test
    void 커서_조회는_삭제된_알림을_제외한다() {
        User me = userRepository.save(User.signUp());
        Notification n1 = saveNotification(me);
        Notification deleted = saveNotification(me);
        Notification n3 = saveNotification(me);
        deleted.softDelete(Instant.parse("2026-09-08T03:00:00Z"));
        entityManager.flush();

        List<Notification> page = notificationRepository.findPageByCursor(
                me.getId(), null, PageRequest.of(0, 10));

        assertThat(page).extracting(Notification::getId)
                .containsExactly(n3.getId(), n1.getId());
    }

    @Test
    void 소유자_단건_조회는_삭제된_알림도_찾는다() {
        // 읽음·삭제 API 의 멱등 204 와 batch 의 삭제된 PENDING 종결(BLOCKED)이 이 동작에 기댐.
        User me = userRepository.save(User.signUp());
        Notification deleted = saveNotification(me);
        deleted.softDelete(Instant.parse("2026-09-08T03:00:00Z"));
        entityManager.flush();

        assertThat(notificationRepository.findByIdAndUserId(deleted.getId(), me.getId())).isPresent();
    }

    // soft delete 를 택한 근거를 계약으로 고정 - 아래 dedup 쿼리에 "일관성 있게" deletedAt is null 을 붙이면
    // 사용자가 지운 알림이 재발송된다. 이 테스트들이 그 회귀를 잡음.
    @Test
    void 삭제된_알림도_리마인드_당일_중복_판정과_본문_중복_판정에_잡힌다() {
        User me = userRepository.save(User.signUp());
        Notification deleted = notificationRepository.save(
                Notification.create(me, NotificationType.ROUTINE_REMINDER, "제목", "내용", 77L));
        deleted.softDelete(Instant.parse("2026-09-08T03:00:00Z"));
        entityManager.flush();
        Instant from = Instant.now().minus(Duration.ofDays(1));
        Instant to = Instant.now().plus(Duration.ofDays(1));

        assertThat(notificationRepository.existsByUserAndTypeAndRefIdSentBetween(
                me.getId(), NotificationType.ROUTINE_REMINDER, 77L, from, to)).isTrue();
        assertThat(notificationRepository.existsByUserAndTypeAndBodySince(
                me.getId(), NotificationType.ROUTINE_REMINDER, "내용", from)).isTrue();
    }

    @Test
    void 알림이_삭제돼도_주간_회고는_push_후보에_다시_잡히지_않는다() {
        User me = userRepository.save(User.signUp());
        LocalDate weekStart = LocalDate.of(2026, 8, 31);
        WeeklyReport report = weeklyReportRepository.save(WeeklyReport.fallback(
                me, weekStart, weekStart.plusDays(6), "{}", "요약", "[]", Instant.now()));
        Notification deleted = notificationRepository.save(
                Notification.create(me, NotificationType.WEEKLY_REPORT, "제목", "내용", report.getId()));
        deleted.softDelete(Instant.parse("2026-09-08T03:00:00Z"));
        entityManager.flush();

        List<WeeklyReport> candidates = weeklyReportRepository.findPushCandidates(
                weekStart, NotificationType.WEEKLY_REPORT, 0L, PageRequest.of(0, 10));

        assertThat(candidates).extracting(WeeklyReport::getId).doesNotContain(report.getId());
    }

    @Test
    void 전체_삭제는_본인의_미삭제_알림만_deleted_at_을_찍고_기존_삭제_시각은_보존한다() {
        Instant earlier = Instant.parse("2026-09-01T00:00:00Z");
        Instant now = Instant.parse("2026-09-08T03:00:00Z");
        User me = userRepository.save(User.signUp());
        User other = userRepository.save(User.signUp());
        Notification active1 = saveNotification(me);
        Notification active2 = saveNotification(me);
        Notification alreadyDeleted = saveNotification(me);
        alreadyDeleted.softDelete(earlier);
        Notification othersActive = saveNotification(other);
        entityManager.flush();

        int updated = notificationRepository.softDeleteAllByUserId(me.getId(), now);

        assertThat(updated).isEqualTo(2);
        entityManager.flush();
        entityManager.clear();
        assertThat(notificationRepository.findById(active1.getId()).orElseThrow().getDeletedAt()).isEqualTo(now);
        assertThat(notificationRepository.findById(active2.getId()).orElseThrow().getDeletedAt()).isEqualTo(now);
        assertThat(notificationRepository.findById(alreadyDeleted.getId()).orElseThrow().getDeletedAt())
                .isEqualTo(earlier);
        assertThat(notificationRepository.findById(othersActive.getId()).orElseThrow().getDeletedAt()).isNull();
        // 행 자체는 남아야 함(digest FK·중복 발송 판정 보존).
        assertThat(notificationRepository.findById(active1.getId())).isPresent();
    }

    @Test
    void 전체_읽음은_삭제된_알림을_건드리지_않는다() {
        User me = userRepository.save(User.signUp());
        Notification unread = saveNotification(me);
        Notification deletedUnread = saveNotification(me);
        deletedUnread.softDelete(Instant.parse("2026-09-08T03:00:00Z"));
        entityManager.flush();

        int updated = notificationRepository.markAllReadByUserId(me.getId());

        assertThat(updated).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();
        assertThat(notificationRepository.findById(unread.getId()).orElseThrow().isRead()).isTrue();
        assertThat(notificationRepository.findById(deletedUnread.getId()).orElseThrow().isRead()).isFalse();
    }
}
