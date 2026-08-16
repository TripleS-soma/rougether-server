package com.triples.rougether.domain.support;

import java.time.LocalDate;

// 날짜별 건수 집계 projection. 월 캘린더처럼 기간 내 날짜마다 개수만 필요한 조회가 공유함
public interface DailyCount {

    LocalDate getTargetDate();

    long getItemCount();
}
