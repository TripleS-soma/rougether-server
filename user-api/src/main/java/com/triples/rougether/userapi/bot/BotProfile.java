package com.triples.rougether.userapi.bot;

import java.util.List;

// 동거 봇 1명의 프로필. botKey 는 users.bot_key 와 1:1(시드 멱등 키), 나머지는 시드가 채우는 값.
// goalCodes 는 선호 목표 코드(1~2개) — 활성 목표 중 매칭되는 것을 쓰고 없으면 첫 활성 목표로 대체한다.
// characterCode 는 대표 캐릭터 코드 — 활성 캐릭터에 없으면 봇 인덱스 라운드로빈으로 대체한다(fail-open).
public record BotProfile(
        String botKey,
        String nickname,
        String bio,
        List<String> goalCodes,
        String characterCode,
        BotActivityProfile activity,
        String categoryName,
        String categoryColorHex,
        List<String> routineTitles) {

    public static final int ROUTINE_COUNT = 4;
    public static final int GOAL_MIN = 1;
    public static final int GOAL_MAX = 2;
    // users.nickname(30) / users.bio(100) 컬럼 길이 — 카탈로그 오타를 클래스 로딩 시점에 잡는다.
    public static final int NICKNAME_MAX = 30;
    public static final int BIO_MAX = 100;

    public BotProfile {
        if (nickname == null || nickname.isBlank() || nickname.length() > NICKNAME_MAX) {
            throw new IllegalArgumentException("봇 닉네임은 1~" + NICKNAME_MAX + "자여야 합니다: " + botKey);
        }
        if (bio != null && bio.length() > BIO_MAX) {
            throw new IllegalArgumentException("봇 bio 는 " + BIO_MAX + "자 이하여야 합니다: " + botKey);
        }
        if (routineTitles == null || routineTitles.size() != ROUTINE_COUNT) {
            throw new IllegalArgumentException("봇 루틴은 정확히 " + ROUTINE_COUNT + "개여야 합니다: " + botKey);
        }
        if (goalCodes == null || goalCodes.size() < GOAL_MIN || goalCodes.size() > GOAL_MAX) {
            throw new IllegalArgumentException("봇 목표는 " + GOAL_MIN + "~" + GOAL_MAX + "개여야 합니다: " + botKey);
        }
        if (characterCode == null || characterCode.isBlank()) {
            throw new IllegalArgumentException("봇 대표 캐릭터 코드가 비었습니다: " + botKey);
        }
        goalCodes = List.copyOf(goalCodes);
        routineTitles = List.copyOf(routineTitles);
    }
}
