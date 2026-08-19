package com.triples.rougether.domain.support;

import java.time.Instant;

// (userId, 마지막 시각) 집계 프로젝션 — 사용자별 최근 완료 시각 조회에 쓴다.
public interface UserLatestInstant {

    Long getUserId();

    Instant getLatestAt();
}
