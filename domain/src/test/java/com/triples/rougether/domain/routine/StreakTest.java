package com.triples.rougether.domain.routine;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.routine.entity.Streak;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class StreakTest {

    private static final LocalDate LAST_SUCCESS_DATE = LocalDate.of(2026, 8, 14);

    @Test
    void 마지막_성공일과_그_다음날까지는_현재_스트릭을_유지한다() {
        Streak streak = fourDayStreak();

        assertThat(streak.currentCountOn(LAST_SUCCESS_DATE)).isEqualTo(4);
        assertThat(streak.currentCountOn(LAST_SUCCESS_DATE.plusDays(1))).isEqualTo(4);
    }

    @Test
    void 하루를_완전히_건너뛰면_표시값만_0이고_저장값은_유지한다() {
        Streak streak = fourDayStreak();

        assertThat(streak.currentCountOn(LAST_SUCCESS_DATE.plusDays(2))).isZero();
        assertThat(streak.getCurrentCount()).isEqualTo(4);
        assertThat(streak.getLongestCount()).isEqualTo(4);
    }

    @Test
    void 마지막_성공일이_없으면_현재_스트릭은_0이다() {
        Streak streak = Streak.start(User.signUp(), LAST_SUCCESS_DATE);
        streak.rollback(LAST_SUCCESS_DATE);

        assertThat(streak.currentCountOn(LAST_SUCCESS_DATE)).isZero();
        assertThat(streak.getLastSuccessDate()).isNull();
    }

    private Streak fourDayStreak() {
        Streak streak = Streak.start(User.signUp(), LAST_SUCCESS_DATE.minusDays(3));
        streak.applySuccess(LAST_SUCCESS_DATE.minusDays(2));
        streak.applySuccess(LAST_SUCCESS_DATE.minusDays(1));
        streak.applySuccess(LAST_SUCCESS_DATE);
        return streak;
    }
}
