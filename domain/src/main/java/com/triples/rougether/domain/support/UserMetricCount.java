package com.triples.rougether.domain.support;

// 관리자 관측 화면의 페이지 단위 집계 projection. 유저별 원문 데이터 대신 건수만 전달함.
public interface UserMetricCount {

    Long getUserId();

    long getMetricCount();
}
