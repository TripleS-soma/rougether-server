package com.triples.rougether.userapi.routine.error;

import com.triples.rougether.common.error.ErrorCode;

public enum RoutineErrorCode implements ErrorCode {

    // 타인 소유도 404로 통일함(존재 노출 회피)
    ROUTINE_NOT_FOUND("ROUTINE_NOT_FOUND", "루틴을 찾을 수 없습니다.", 404),
    // BIWEEKLY는 startsOn이 속한 주를 1주차 기준으로 삼으므로 startsOn 필수
    BIWEEKLY_REQUIRES_STARTS_ON("BIWEEKLY_REQUIRES_STARTS_ON", "격주 반복은 시작일(startsOn)이 필요합니다.", 400),
    // 누락/빈 목록/MON~SUN 외 값이면 어느 날짜에도 매칭되지 않는 "죽은" 루틴이 생성되므로 필수값으로 막음
    BIWEEKLY_REQUIRES_WEEKDAYS("BIWEEKLY_REQUIRES_WEEKDAYS",
            "격주 반복은 repeatDays.daysOfWeek(MON~SUN 중 1개 이상)가 필요합니다.", 400),
    // 누락 시 어느 날짜에도 매칭되지 않는 "죽은" 루틴이 생성되므로 필수값으로 막음
    MONTHLY_REQUIRES_DAY_OF_MONTH("MONTHLY_REQUIRES_DAY_OF_MONTH",
            "매월 반복은 repeatDays.dayOfMonth(1~31)가 필요합니다.", 400),
    // 누락/범위 밖/2·30·4·31처럼 실존하지 않는 조합이면 어느 날짜에도 매칭되지 않는 "죽은" 루틴이 생성되므로 필수값으로 막음(2/29는 허용)
    YEARLY_REQUIRES_MONTH_AND_DAY("YEARLY_REQUIRES_MONTH_AND_DAY",
            "매년 반복은 repeatDays.month(1~12)와 day(1~31)의 실제 존재하는 날짜 조합이 필요합니다.", 400);

    private final String code;
    private final String message;
    private final int status;

    RoutineErrorCode(String code, String message, int status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public int status() {
        return status;
    }
}
