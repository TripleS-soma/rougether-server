package com.triples.rougether.userapi.house.service;

import com.triples.rougether.domain.house.entity.HouseMissionType;

// 조회, 기여 응답과 보상 판정이 같은 DAILY 계산 규칙을 사용하게 함.
final class HouseMissionProgressCalculator {

    private HouseMissionProgressCalculator() {
    }

    // 오늘 기여 멤버 비율을 정수 내림으로 표시함. 멤버가 없으면 0을 반환함.
    static long ratePercent(long todayCount, int memberCount) {
        if (memberCount <= 0) {
            return 0;
        }
        return todayCount * 100 / memberCount;
    }

    // 표시값을 재사용하지 않고 정수 비율로 판정해 부동소수 오차를 피함.
    static boolean isDailyAchieved(long todayCount, int memberCount, int targetPercent) {
        return memberCount > 0 && todayCount * 100 >= (long) targetPercent * memberCount;
    }

    // 일일 미션인지 - 단위 테스트의 목 미션은 유형이 비어 있을 수 있어 null 이면 누적형(WEEKLY)으로 본다.
    static boolean isDaily(HouseMissionType type) {
        return type != null && type.isDaily();
    }

    // 일일 미션의 진행 수치 - RATE 는 오늘 달성률 %(내림), COUNT 는 오늘 기여한 멤버 수.
    static long dailyCurrentValue(HouseMissionType type, long todayCount, int memberCount) {
        return type == HouseMissionType.DAILY_MEMBER_COUNT ? todayCount : ratePercent(todayCount, memberCount);
    }

    // 일일 미션 달성 판정 - RATE 는 비율, COUNT 는 오늘 기여 멤버 수가 목표 인원 이상.
    static boolean isDailyAchieved(HouseMissionType type, long todayCount, int memberCount, int target) {
        return type == HouseMissionType.DAILY_MEMBER_COUNT
                ? todayCount >= target
                : isDailyAchieved(todayCount, memberCount, target);
    }
}
