package com.triples.rougether.userapi.house.service;

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
}
