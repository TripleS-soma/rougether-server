package com.triples.rougether.domain.house.entity;

public enum HouseMissionType {
    DAILY_MEMBER_RATE,    // 오늘 멤버 N% 달성
    WEEKLY_MEMBER_COUNT,  // 주 N회
    STREAK_DAYS           // N일 연속 (MVP 범위 밖)
}
