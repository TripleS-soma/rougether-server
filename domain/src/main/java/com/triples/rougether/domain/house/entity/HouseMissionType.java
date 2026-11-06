package com.triples.rougether.domain.house.entity;

public enum HouseMissionType {
    DAILY_MEMBER_RATE,    // 오늘 멤버 N% 달성
    DAILY_MEMBER_COUNT,   // 오늘 멤버 N명 이상 달성 - 멤버 수가 바뀌어도 필요한 인원이 그대로다
    WEEKLY_MEMBER_COUNT,  // 주 N회
    STREAK_DAYS;          // N일 연속 (MVP 범위 밖)

    // 매일 반복하는 일일 미션인지 - 오늘(KST) 기여 멤버 수로 판정하고 하루 1회 claim, COMPLETED 전환 없음.
    public boolean isDaily() {
        return this == DAILY_MEMBER_RATE || this == DAILY_MEMBER_COUNT;
    }
}
