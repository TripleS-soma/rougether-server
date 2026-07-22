package com.triples.rougether.userapi.routine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.global.config.JpaConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class TodoReminderCandidateRepositoryTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime DUE_TIME = LocalTime.of(9, 0);

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private NotificationRepository notificationRepository;

    // 기발송 판정은 createdAt(auditing, 실제 현재 시각) 기준 당일 윈도우라 대상일도 실제 오늘로 고정
    private final LocalDate date = LocalDate.now(KST);
    private final Instant dayStart = LocalDate.now(KST).atStartOfDay(KST).toInstant();
    private final Instant dayEndExclusive = LocalDate.now(KST).plusDays(1).atStartOfDay(KST).toInstant();

    private Todo saveTodo(User user, LocalDate dueDate, LocalTime dueTime) {
        return todoRepository.save(Todo.create(user, null, "장보기", null, dueDate, dueTime));
    }

    private List<Todo> findCandidates() {
        return todoRepository.findReminderCandidates(TodoStatus.PENDING, date, DUE_TIME,
                NotificationType.TODO_REMINDER, dayStart, dayEndExclusive, 0L, PageRequest.of(0, 200));
    }

    @Test
    void 대상일_대상분의_PENDING_투두만_조회한다() {
        User user = userRepository.save(User.signUp());
        Todo target = saveTodo(user, date, DUE_TIME);
        saveTodo(user, date.plusDays(1), DUE_TIME); // 다른 날짜
        saveTodo(user, date, DUE_TIME.plusMinutes(5)); // 다른 분
        saveTodo(user, date, null); // 시각 없음

        assertThat(findCandidates()).extracting(Todo::getId).containsExactly(target.getId());
    }

    @Test
    void 완료된_투두는_제외한다() {
        User user = userRepository.save(User.signUp());
        Todo completed = saveTodo(user, date, DUE_TIME);
        completed.complete(CurrencyType.COIN, 0, Instant.now());
        todoRepository.save(completed);

        assertThat(findCandidates()).isEmpty();
    }

    @Test
    void dueDate_없이_dueTime만_있는_투두는_제외한다() {
        User user = userRepository.save(User.signUp());
        saveTodo(user, null, DUE_TIME);

        assertThat(findCandidates()).isEmpty();
    }

    @Test
    void 삭제된_투두는_제외한다() {
        User user = userRepository.save(User.signUp());
        Todo deleted = saveTodo(user, date, DUE_TIME);
        deleted.softDelete(Instant.now());
        todoRepository.save(deleted);

        assertThat(findCandidates()).isEmpty();
    }

    @Test
    void 당일_기발송된_투두는_제외한다() {
        User user = userRepository.save(User.signUp());
        Todo sent = saveTodo(user, date, DUE_TIME);
        notificationRepository.save(Notification.create(user, NotificationType.TODO_REMINDER,
                "투두 리마인드", "『장보기』 마감 시간이에요!", sent.getId()));

        assertThat(findCandidates()).isEmpty();
    }

    @Test
    void 다른_타입의_당일_알림은_기발송으로_치지_않는다() {
        User user = userRepository.save(User.signUp());
        Todo target = saveTodo(user, date, DUE_TIME);
        notificationRepository.save(Notification.create(user, NotificationType.ROUTINE_REMINDER,
                "루틴 리마인드", "『장보기』 할 시간이에요!", target.getId()));

        assertThat(findCandidates()).extracting(Todo::getId).containsExactly(target.getId());
    }
}
