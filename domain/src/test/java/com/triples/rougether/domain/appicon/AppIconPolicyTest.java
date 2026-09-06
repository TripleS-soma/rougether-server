package com.triples.rougether.domain.appicon;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.appicon.entity.UserAppActivity;
import com.triples.rougether.domain.member.entity.User;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AppIconPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-06T03:00:00Z");

    @ParameterizedTest
    @CsvSource({"0,NORMAL", "47,NORMAL", "48,MISSING_YOU", "95,MISSING_YOU",
            "96,TEARY", "167,TEARY", "168,SOBBING", "720,SOBBING"})
    void 미접속_경계는_정확히_48_96_168시간부터_올라간다(long hours, AppIconState expected) {
        assertThat(AppIconPolicy.evaluate(NOW.minus(Duration.ofHours(hours)), 0, false, NOW))
                .isEqualTo(expected);
    }

    @Test
    void 경계_직전과_직후를_구분한다() {
        assertThat(AppIconPolicy.inactivityState(NOW.minus(Duration.ofHours(48)).plusNanos(1), NOW))
                .isEqualTo(AppIconState.NORMAL);
        assertThat(AppIconPolicy.inactivityState(NOW.minus(Duration.ofHours(96)).plusNanos(1), NOW))
                .isEqualTo(AppIconState.MISSING_YOU);
        assertThat(AppIconPolicy.inactivityState(NOW.minus(Duration.ofHours(168)).plusNanos(1), NOW))
                .isEqualTo(AppIconState.TEARY);
    }

    @Test
    void 왕관과_오늘실천이_미접속보다_우선한다() {
        Instant old = NOW.minus(Duration.ofDays(30));
        assertThat(AppIconPolicy.evaluate(old, 7, true, NOW)).isEqualTo(AppIconState.STREAK_CHAMPION);
        assertThat(AppIconPolicy.evaluate(old, 7, false, NOW)).isEqualTo(AppIconState.STREAK_CHAMPION);
        assertThat(AppIconPolicy.evaluate(old, 6, true, NOW)).isEqualTo(AppIconState.DAILY_SUCCESS);
    }

    @Test
    void 기록이_없는_사용자와_미래_활동은_기본으로_판정한다() {
        assertThat(AppIconPolicy.evaluate(null, 0, false, NOW)).isEqualTo(AppIconState.NORMAL);
        assertThat(AppIconPolicy.inactivityState(NOW.plusSeconds(30), NOW)).isEqualTo(AppIconState.NORMAL);
    }

    @Test
    void 달성_상태의_재평가는_한국_자정이다() {
        Instant expected = Instant.parse("2026-09-06T15:00:00Z");
        assertThat(AppIconPolicy.nextEvaluationAt(AppIconState.DAILY_SUCCESS, NOW, NOW)).isEqualTo(expected);
        assertThat(AppIconPolicy.nextEvaluationAt(AppIconState.STREAK_CHAMPION, NOW, NOW)).isEqualTo(expected);
        assertThat(AppIconPolicy.nextEvaluationAt(AppIconState.TEARY, NOW, NOW))
                .isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(AppIconPolicy.nextEvaluationAt(AppIconState.SOBBING, NOW, NOW)).isNull();
        assertThat(AppIconPolicy.nextEvaluationAt(AppIconState.NORMAL, null, NOW)).isNull();
        assertThat(AppIconPolicy.nextEvaluationAt(AppIconState.MISSING_YOU, null, NOW)).isNull();
        assertThat(AppIconPolicy.nextEvaluationAt(AppIconState.TEARY, null, NOW)).isNull();
    }

    @Test
    void 복귀하면_단계와_이전_알림이_초기화되고_시각은_역행하지_않는다() {
        UserAppActivity activity = UserAppActivity.start(User.signUp(), NOW.minus(Duration.ofDays(8)));
        activity.recordNotification(AppIconState.SOBBING, 42L);
        activity.recordForeground(NOW);
        activity.recordForeground(NOW.minusSeconds(1));
        assertThat(activity.getLastForegroundAt()).isEqualTo(NOW);
        assertThat(activity.getLastNotifiedStage()).isZero();
        assertThat(activity.getLastNotificationId()).isNull();
    }
}
