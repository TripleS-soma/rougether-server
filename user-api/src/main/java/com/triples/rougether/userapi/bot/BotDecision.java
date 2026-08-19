package com.triples.rougether.userapi.bot;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;

// 동거 봇 활동 결정(#310) — 상태 없는 순수 함수 모음. 모든 판정은 (봇, KST 날짜, 대상, 틱)에서 만든
// 결정론적 난수로 뽑아 같은 입력이면 항상 같은 결정이 나오고(테스트 재현), 서버 재시작에도 흔들리지 않는다.
// 하루는 10분 틱 144개(0~143). "활동 창 안 틱"은 프로필(BotActivityProfile)의 [start, end) 창에 속한 틱이다.
public final class BotDecision {

    public static final int TICK_MINUTES = 10;

    public static final double REST_DAY_RATE = 0.15;
    public static final double ROUTINE_COMPLETION_RATE = 0.70;
    public static final double MISSION_CONTRIBUTION_RATE = 0.70;
    public static final double COBWEB_CLEAN_RATE_PER_TICK = 0.01;
    public static final double THURSDAY_GUESTBOOK_RATE = 0.50;
    public static final int CHEER_DELAY_MIN_MINUTES = 30;
    public static final int CHEER_DELAY_MAX_MINUTES = 80;
    public static final int CHEER_WINDOW_END_MINUTES = 90;
    public static final int CHEER_DAILY_LIMIT_PER_HUMAN = 2; // 사람 기준(집의 봇 전체 합산)

    // 프리셋 순환 기준 월요일 — 주차 인덱스로 프리셋을 정하므로 한 주를 건너뛰어도 순환이 어긋나지 않는다.
    private static final LocalDate PRESET_EPOCH_MONDAY = LocalDate.of(2026, 1, 5);
    private static final String[] CHEER_TYPES = {"great", "support", "best"};

    private BotDecision() {
    }

    // 결정론적 난수 — 입력을 섞어 시드로 쓴다(SplitMix 스타일 혼합).
    public static Random random(long... parts) {
        long seed = 0x9E3779B97F4A7C15L;
        for (long part : parts) {
            seed ^= part + 0x9E3779B97F4A7C15L + (seed << 6) + (seed >>> 2);
            seed = mix(seed);
        }
        return new Random(seed);
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static int tickIndex(LocalTime time) {
        return time.toSecondOfDay() / 60 / TICK_MINUTES;
    }

    // 프로필 활동 창에 속한 틱 목록(오름차순).
    public static List<Integer> activeTicks(BotActivityProfile profile) {
        List<Integer> ticks = new ArrayList<>();
        for (BotActivityProfile.Window window : profile.windows()) {
            int from = window.start().toSecondOfDay() / 60 / TICK_MINUTES;
            int to = window.end().toSecondOfDay() / 60 / TICK_MINUTES; // [from, to)
            for (int tick = from; tick < to; tick++) {
                ticks.add(tick);
            }
        }
        return ticks;
    }

    // 쉬는 날(하루 15%) — 그날 루틴 완료·미션 기여를 전부 건너뛴다. 봇·날짜 단위로 하루에 한 번만 판정된다.
    public static boolean isRestDay(long botId, LocalDate date) {
        return random(botId, date.toEpochDay(), 1L).nextDouble() < REST_DAY_RATE;
    }

    // 루틴 하루 완료 확률 70%: 완료하는 날이면 활동 창 안 틱 중 하나를 목표 틱으로 고르고,
    // 그 틱 이후 첫 활동 창 틱에서 완료한다(스케줄러가 몇 틱을 놓쳐도 그날 안에 따라잡는다).
    public static OptionalInt routineCompletionTick(long botId, LocalDate date, long routineId,
                                                    BotActivityProfile profile) {
        return scheduledTick(random(botId, date.toEpochDay(), 2L, routineId), ROUTINE_COMPLETION_RATE, profile);
    }

    // 단체 미션 기여 하루 1회 70%(수동 기여 경로).
    public static OptionalInt missionContributionTick(long botId, LocalDate date, long missionId,
                                                      BotActivityProfile profile) {
        return scheduledTick(random(botId, date.toEpochDay(), 3L, missionId), MISSION_CONTRIBUTION_RATE, profile);
    }

    private static OptionalInt scheduledTick(Random random, double rate, BotActivityProfile profile) {
        if (random.nextDouble() >= rate) {
            return OptionalInt.empty();
        }
        List<Integer> ticks = activeTicks(profile);
        if (ticks.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(ticks.get(random.nextInt(ticks.size())));
    }

    // 목표 틱이 있고 현재 틱이 그 이후(포함)면 실행 시점.
    public static boolean isDue(OptionalInt targetTick, int currentTick) {
        return targetTick.isPresent() && currentTick >= targetTick.getAsInt();
    }

    // 거미줄 청소: 같은 집 사람 방에 활성 거미줄이 있으면 활동 창 틱마다 1%(2026-08-19 결정 — 10%면 사람이 복귀해
    // 스스로 청소할 기회(3코인)를 봇이 사실상 항상 선점하므로 낮춤. SPREAD 84틱 기준 하루 약 57%).
    public static boolean shouldCleanCobweb(long botId, LocalDate date, int tick, long roomUserId) {
        return random(botId, date.toEpochDay(), 4L, tick, roomUserId).nextDouble() < COBWEB_CLEAN_RATE_PER_TICK;
    }

    // 응원 지연(분): 완료 후 30~80분 사이 결정론적 지연. 10분 틱이므로 실제 발송은 완료 후 30~90분 창 안.
    public static int cheerDelayMinutes(long botId, LocalDate date, long targetUserId, long completedEpochSecond) {
        Random random = random(botId, date.toEpochDay(), 5L, targetUserId, completedEpochSecond);
        return CHEER_DELAY_MIN_MINUTES + random.nextInt(CHEER_DELAY_MAX_MINUTES - CHEER_DELAY_MIN_MINUTES + 1);
    }

    public static String cheerType(long botId, LocalDate date, long targetUserId, long completedEpochSecond) {
        Random random = random(botId, date.toEpochDay(), 6L, targetUserId, completedEpochSecond);
        return CHEER_TYPES[random.nextInt(CHEER_TYPES.length)];
    }

    // 방명록: 집당 주 1~2회 — 월요일은 항상, 목요일은 50%(기대 1.5회/주). 그날 쓸 봇·대상 방·틱은 집·날짜로 정한다.
    public static boolean isGuestbookDay(long houseId, LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.MONDAY) {
            return true;
        }
        return day == DayOfWeek.THURSDAY
                && random(houseId, date.toEpochDay(), 7L).nextDouble() < THURSDAY_GUESTBOOK_RATE;
    }

    // 집·날짜 기준으로 후보 중 하나를 고른다(작성 봇·대상 사람 방·템플릿 선택 공용).
    public static int pickIndex(long houseId, LocalDate date, long salt, int size) {
        return random(houseId, date.toEpochDay(), 8L, salt).nextInt(size);
    }

    // 방명록 작성 목표 틱: 작성 봇의 활동 창 안에서 하나.
    public static OptionalInt guestbookTick(long houseId, LocalDate date, BotActivityProfile profile) {
        List<Integer> ticks = activeTicks(profile);
        if (ticks.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(ticks.get(random(houseId, date.toEpochDay(), 9L).nextInt(ticks.size())));
    }

    // 방 레이아웃 프리셋: 주차 인덱스 순환(1→2→3). 월요일 활동 창 첫 틱(=그 주에 아직 안 바꿨을 때)에 적용한다.
    public static BotRoomPreset presetForWeek(LocalDate date) {
        LocalDate monday = date.with(DayOfWeek.MONDAY);
        long weeks = ChronoUnit.WEEKS.between(PRESET_EPOCH_MONDAY, monday);
        BotRoomPreset[] presets = BotRoomPreset.values();
        int index = (int) Math.floorMod(weeks, presets.length);
        return presets[index];
    }

    public static boolean isLayoutRotationDay(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.MONDAY;
    }
}
