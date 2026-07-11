package com.triples.rougether.userapi.routine.reminder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.agenda.DailyAgendaAssembler;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.notification.service.NotificationService;
import com.triples.rougether.userapi.routine.dto.RepeatDays;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class RoutineReminderSchedulerIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime NINE = LocalTime.of(9, 0);

    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private UserRepository userRepository;

    private NotificationService notificationService;
    private RoutineReminderScheduler scheduler;

    private User user;
    private LocalDate today;
    private String todayToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        // 오늘(KST) 09:00에 고정
        today = LocalDate.now(KST);
        Clock clock = Clock.fixed(today.atTime(NINE).atZone(KST).toInstant(), KST);
        scheduler = new RoutineReminderScheduler(routineRepository, routineLogRepository,
                notificationRepository, new DailyAgendaAssembler(), notificationService, clock);

        user = userRepository.save(User.signUp());
        todayToken = today.getDayOfWeek().name().substring(0, 3);
        otherToken = today.getDayOfWeek().plus(3).name().substring(0, 3);
    }

    @Test
    void 예약시각_도래한_미완료_루틴에_리마인드를_발송한다() {
        Routine routine = persistRoutine("아침 운동", "DAILY", null, NINE);

        scheduler.sendDueReminders();

        verify(notificationService).send(user.getId(), NotificationType.ROUTINE_REMINDER,
                ReminderMessage.TITLE, ReminderMessage.body("아침 운동"), routine.getId());
    }

    @Test
    void 오늘_요일이_반복요일에_없으면_발송하지_않는다() {
        persistRoutine("스트레칭", "WEEKLY", weekdays(otherToken), NINE);

        scheduler.sendDueReminders();

        verifyNoReminderSent();
    }

    @Test
    void 반복요일에_오늘이_포함되면_발송한다() {
        Routine routine = persistRoutine("스트레칭", "WEEKLY", weekdays(todayToken), NINE);

        scheduler.sendDueReminders();

        verify(notificationService).send(eq(user.getId()), eq(NotificationType.ROUTINE_REMINDER),
                any(), any(), eq(routine.getId()));
    }

    @Test
    void 오늘_이미_완료한_루틴은_발송하지_않는다() {
        Routine routine = persistRoutine("아침 운동", "DAILY", null, NINE);
        routineLogRepository.save(RoutineLog.complete(routine, today, Instant.now(), CurrencyType.COIN, 0));

        scheduler.sendDueReminders();

        verifyNoReminderSent();
    }

    @Test
    void 오늘_이미_리마인드를_보냈으면_다시_발송하지_않는다() {
        Routine routine = persistRoutine("아침 운동", "DAILY", null, NINE);
        notificationRepository.save(Notification.create(user, NotificationType.ROUTINE_REMINDER,
                ReminderMessage.TITLE, ReminderMessage.body("아침 운동"), routine.getId()));

        scheduler.sendDueReminders();

        verifyNoReminderSent();
    }

    @Test
    void 삭제된_루틴은_발송하지_않는다() {
        Routine routine = persistRoutine("아침 운동", "DAILY", null, NINE);
        routine.softDelete(Instant.now());
        routineRepository.save(routine);

        scheduler.sendDueReminders();

        verifyNoReminderSent();
    }

    @Test
    void 예약시각이_현재_분과_다르면_발송하지_않는다() {
        persistRoutine("아침 운동", "DAILY", null, LocalTime.of(10, 0));

        scheduler.sendDueReminders();

        verifyNoReminderSent();
    }

    @Test
    void 개별_루틴_발송_실패가_나머지_루틴_발송을_막지_않는다() {
        Routine failing = persistRoutine("실패 루틴", "DAILY", null, NINE);
        Routine succeeding = persistRoutine("정상 루틴", "DAILY", null, NINE);
        doThrow(new RuntimeException("push down"))
                .when(notificationService).send(eq(user.getId()), any(), any(), any(), eq(failing.getId()));

        scheduler.sendDueReminders();

        // 실패한 루틴도 시도되고, 나머지 루틴은 정상 발송돼야 함
        verify(notificationService).send(eq(user.getId()), any(), any(), any(), eq(failing.getId()));
        verify(notificationService).send(eq(user.getId()), eq(NotificationType.ROUTINE_REMINDER),
                eq(ReminderMessage.TITLE), eq(ReminderMessage.body("정상 루틴")), eq(succeeding.getId()));
    }

    private Routine persistRoutine(String title, String repeatType, String repeatDays, LocalTime scheduledTime) {
        return routineRepository.save(Routine.create(user, null, title, AuthType.CHECK,
                repeatType, repeatDays, scheduledTime, null, null));
    }

    private String weekdays(String... tokens) {
        return new RepeatDays(List.of(tokens)).toJson();
    }

    private void verifyNoReminderSent() {
        verify(notificationService, never()).send(any(), any(), any(), any(), any());
    }
}
