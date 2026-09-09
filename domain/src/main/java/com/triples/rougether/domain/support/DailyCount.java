package com.triples.rougether.domain.support;

import java.time.LocalDate;

// 날짜별 전체·완료 건수 집계 projection. 월 캘린더의 분모와 분자를 같은 소싱 조건으로 조회함
public interface DailyCount {

    LocalDate getTargetDate();

    long getItemCount();

    long getCompletedCount();
}
