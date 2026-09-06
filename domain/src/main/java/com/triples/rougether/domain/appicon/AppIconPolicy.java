package com.triples.rougether.domain.appicon;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

// 사용자 API와 미접속 알림이 공유하는 순수 상태 판정임. 스트릭은 유효성 보정된 값을 받음.
public final class AppIconPolicy {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private AppIconPolicy() {
    }

    public static AppIconState evaluate(Instant lastForegroundAt, int currentStreak,
                                        boolean completedToday, Instant now) {
        if (currentStreak >= 7) {
            return AppIconState.STREAK_CHAMPION;
        }
        if (completedToday) {
            return AppIconState.DAILY_SUCCESS;
        }
        return inactivityState(lastForegroundAt, now);
    }

    public static AppIconState inactivityState(Instant lastForegroundAt, Instant now) {
        // 새 활동 API를 호출하지 않은 구버전 사용자는 미접속 상태로 추정하지 않음.
        if (lastForegroundAt == null) {
            return AppIconState.NORMAL;
        }
        Duration elapsed = Duration.between(lastForegroundAt, now);
        if (elapsed.compareTo(Duration.ofDays(7)) >= 0) {
            return AppIconState.SOBBING;
        }
        if (elapsed.compareTo(Duration.ofDays(4)) >= 0) {
            return AppIconState.TEARY;
        }
        if (elapsed.compareTo(Duration.ofDays(2)) >= 0) {
            return AppIconState.MISSING_YOU;
        }
        return AppIconState.NORMAL;
    }

    // 시간 경과만으로 재평가가 필요한 시점임. 실제 OS 아이콘 변경 예약 시각은 아님.
    public static Instant nextEvaluationAt(AppIconState state, Instant lastForegroundAt, Instant now) {
        return switch (state) {
            case STREAK_CHAMPION, DAILY_SUCCESS ->
                    now.atZone(KST).toLocalDate().plusDays(1).atStartOfDay(KST).toInstant();
            case NORMAL -> lastForegroundAt == null ? null : lastForegroundAt.plus(Duration.ofDays(2));
            case MISSING_YOU -> lastForegroundAt.plus(Duration.ofDays(4));
            case TEARY -> lastForegroundAt.plus(Duration.ofDays(7));
            case SOBBING -> null;
        };
    }
}
