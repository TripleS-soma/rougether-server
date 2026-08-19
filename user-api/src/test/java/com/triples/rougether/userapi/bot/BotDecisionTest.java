package com.triples.rougether.userapi.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import org.junit.jupiter.api.Test;

// 동거 봇 결정 함수(#310) 단위 테스트 — 결정론(같은 입력 = 같은 결정), 확률 근사, 활동 창 밖 무행동, 순환 규칙.
class BotDecisionTest {

    private static final LocalDate BASE = LocalDate.of(2026, 8, 17); // 월요일

    @Test
    void 같은_봇_날짜_틱이면_같은_결정이_나온다() {
        for (int i = 0; i < 50; i++) {
            long botId = 100 + i;
            LocalDate date = BASE.plusDays(i);
            assertThat(BotDecision.isRestDay(botId, date)).isEqualTo(BotDecision.isRestDay(botId, date));
            assertThat(BotDecision.routineCompletionTick(botId, date, 7L, BotActivityProfile.MORNING))
                    .isEqualTo(BotDecision.routineCompletionTick(botId, date, 7L, BotActivityProfile.MORNING));
            assertThat(BotDecision.missionContributionTick(botId, date, 9L, BotActivityProfile.EVENING))
                    .isEqualTo(BotDecision.missionContributionTick(botId, date, 9L, BotActivityProfile.EVENING));
            assertThat(BotDecision.shouldCleanCobweb(botId, date, 60, 5L))
                    .isEqualTo(BotDecision.shouldCleanCobweb(botId, date, 60, 5L));
            assertThat(BotDecision.cheerDelayMinutes(botId, date, 5L, 1_000L))
                    .isEqualTo(BotDecision.cheerDelayMinutes(botId, date, 5L, 1_000L));
            assertThat(BotDecision.cheerType(botId, date, 5L, 1_000L))
                    .isEqualTo(BotDecision.cheerType(botId, date, 5L, 1_000L));
        }
        // 입력이 하나라도 다르면 시드가 달라진다(전부 같은 값으로 붕괴하지 않는지)
        long distinctDelays = java.util.stream.LongStream.range(0, 200)
                .map(i -> BotDecision.cheerDelayMinutes(1L, BASE, 2L, i)).distinct().count();
        assertThat(distinctDelays).isGreaterThan(20);
    }

    @Test
    void 쉬는_날은_약_15퍼센트_루틴_완료는_약_70퍼센트로_수렴한다_1000일_시뮬레이션() {
        int days = 1000;
        for (long botId : List.of(1L, 42L, 9_999L)) {
            int rest = 0;
            int completed = 0;
            for (int d = 0; d < days; d++) {
                LocalDate date = BASE.plusDays(d);
                if (BotDecision.isRestDay(botId, date)) {
                    rest++;
                }
                if (BotDecision.routineCompletionTick(botId, date, 77L, BotActivityProfile.SPREAD).isPresent()) {
                    completed++;
                }
            }
            assertThat(rest / (double) days).isCloseTo(BotDecision.REST_DAY_RATE, within(0.04));
            assertThat(completed / (double) days).isCloseTo(BotDecision.ROUTINE_COMPLETION_RATE, within(0.05));
        }
    }

    @Test
    void 완료_목표_틱은_항상_활동_창_안의_틱이고_창_밖_시각은_비활성이다() {
        for (BotActivityProfile profile : BotActivityProfile.values()) {
            List<Integer> ticks = BotDecision.activeTicks(profile);
            assertThat(ticks).isNotEmpty().isSorted();
            for (int d = 0; d < 300; d++) {
                OptionalInt tick = BotDecision.routineCompletionTick(3L, BASE.plusDays(d), 1L, profile);
                tick.ifPresent(t -> assertThat(ticks).contains(t));
                OptionalInt mission = BotDecision.missionContributionTick(3L, BASE.plusDays(d), 1L, profile);
                mission.ifPresent(t -> assertThat(ticks).contains(t));
                OptionalInt guestbook = BotDecision.guestbookTick(3L, BASE.plusDays(d), profile);
                assertThat(guestbook).isPresent();
                assertThat(ticks).contains(guestbook.getAsInt());
            }
        }
        // MORNING 06–09·12–13 → 틱 36~53, 72~77 (24개)
        assertThat(BotDecision.activeTicks(BotActivityProfile.MORNING)).hasSize(24)
                .startsWith(36).endsWith(77).contains(53, 72).doesNotContain(54, 71);
        assertThat(BotActivityProfile.MORNING.isActiveAt(LocalTime.of(5, 59))).isFalse();
        assertThat(BotActivityProfile.MORNING.isActiveAt(LocalTime.of(9, 0))).isFalse();
        assertThat(BotActivityProfile.MORNING.isActiveAt(LocalTime.of(12, 30))).isTrue();
        assertThat(BotActivityProfile.EVENING.isActiveAt(LocalTime.of(23, 0))).isFalse();
        assertThat(BotActivityProfile.SPREAD.isActiveAt(LocalTime.of(21, 59))).isTrue();
        assertThat(BotDecision.tickIndex(LocalTime.of(21, 59))).isEqualTo(131);
        assertThat(BotDecision.isDue(OptionalInt.of(50), 49)).isFalse();
        assertThat(BotDecision.isDue(OptionalInt.of(50), 50)).isTrue();
        assertThat(BotDecision.isDue(OptionalInt.empty(), 143)).isFalse();
    }

    @Test
    void 응원_지연은_30에서_80분_사이_타입은_세_가지_중_하나다() {
        for (int i = 0; i < 500; i++) {
            int delay = BotDecision.cheerDelayMinutes(11L, BASE, 22L, i);
            assertThat(delay).isBetween(BotDecision.CHEER_DELAY_MIN_MINUTES, BotDecision.CHEER_DELAY_MAX_MINUTES);
            assertThat(BotDecision.cheerType(11L, BASE, 22L, i)).isIn("great", "support", "best");
        }
        assertThat(BotDecision.CHEER_DELAY_MAX_MINUTES + BotDecision.TICK_MINUTES)
                .isLessThanOrEqualTo(BotDecision.CHEER_WINDOW_END_MINUTES);
    }

    @Test
    void 방명록은_월요일_항상_목요일_약_절반_나머지_요일엔_쓰지_않는다() {
        int thursdays = 0;
        int thursdayWrites = 0;
        for (long houseId = 1; houseId <= 400; houseId++) {
            for (int d = 0; d < 7; d++) {
                LocalDate date = BASE.plusDays(d);
                boolean day = BotDecision.isGuestbookDay(houseId, date);
                switch (date.getDayOfWeek()) {
                    case MONDAY -> assertThat(day).isTrue();
                    case THURSDAY -> {
                        thursdays++;
                        if (day) {
                            thursdayWrites++;
                        }
                    }
                    default -> assertThat(day).isFalse();
                }
            }
        }
        assertThat(thursdayWrites / (double) thursdays).isCloseTo(BotDecision.THURSDAY_GUESTBOOK_RATE, within(0.08));
    }

    @Test
    void 레이아웃_프리셋은_월요일마다_1_2_3_순환하고_한_주를_건너뛰어도_어긋나지_않는다() {
        LocalDate monday = BASE;
        BotRoomPreset first = BotDecision.presetForWeek(monday);
        assertThat(BotDecision.presetForWeek(monday.plusWeeks(1))).isEqualTo(first.next());
        assertThat(BotDecision.presetForWeek(monday.plusWeeks(2))).isEqualTo(first.next().next());
        assertThat(BotDecision.presetForWeek(monday.plusWeeks(3))).isEqualTo(first);
        // 같은 주 안의 다른 요일은 같은 프리셋
        assertThat(BotDecision.presetForWeek(monday.plusDays(3))).isEqualTo(first);
        assertThat(BotDecision.isLayoutRotationDay(monday)).isTrue();
        assertThat(BotDecision.isLayoutRotationDay(monday.plusDays(1))).isFalse();
        assertThat(monday.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    void 방명록_템플릿은_30개이고_치환_후_자리표시자가_남지_않는다() {
        assertThat(BotGuestbookTemplates.TEMPLATE_COUNT).isEqualTo(30);
        assertThat(BotGuestbookTemplates.all()).hasSize(30).doesNotHaveDuplicates();
        for (int i = 0; i < 200; i++) {
            String zero = BotGuestbookTemplates.render(new Random(i), "루미", DayOfWeek.MONDAY, 0);
            String some = BotGuestbookTemplates.render(new Random(i), null, DayOfWeek.THURSDAY, 3);
            assertThat(zero).doesNotContain("{").doesNotContain("}").doesNotContain("0개").contains("루미");
            assertThat(some).doesNotContain("{").doesNotContain("}").contains("집 친구");
            if (some.contains("요일")) {
                assertThat(some).contains("목요일");
            }
            assertThat(zero.length()).isBetween(1, 500);
            assertThat(some.length()).isBetween(1, 500);
        }
    }
}
