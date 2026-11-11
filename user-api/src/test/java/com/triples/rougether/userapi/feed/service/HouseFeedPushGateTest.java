package com.triples.rougether.userapi.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

// 집 피드 새 글 푸시의 배포 후 유예(#530). 설정 테이블 migration 적용 시각 + grace 가 지나야 푸시가 열림.
class HouseFeedPushGateTest {
    static final Instant DEPLOYED = Instant.parse("2026-11-12T03:00:00Z");
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    HouseFeedPushGate gate(Instant now, Duration grace) {
        return new HouseFeedPushGate(jdbc, Clock.fixed(now, ZoneOffset.UTC), grace);
    }
    void deployedAt(Instant at) {
        when(jdbc.queryForList(anyString(), eq(Timestamp.class), eq(HouseFeedPushGate.MIGRATION_DESCRIPTION)))
                .thenReturn(at == null ? List.of() : List.of(Timestamp.from(at)));
    }

    @Test void 배포_뒤_하루가_지나기_전에는_닫혀_있고_지나면_열린다() {
        deployedAt(DEPLOYED);
        Duration day = Duration.ofHours(24);
        assertThat(gate(DEPLOYED, day).isOpen()).isFalse();
        assertThat(gate(DEPLOYED.plus(Duration.ofHours(23)).plusSeconds(3599), day).isOpen()).isFalse();
        assertThat(gate(DEPLOYED.plus(day), day).isOpen()).isTrue();
        assertThat(gate(DEPLOYED.plus(Duration.ofDays(30)), day).isOpen()).isTrue();
    }

    @Test void 유예를_0으로_두면_이력을_읽지_않고_바로_열린다() {
        assertThat(gate(DEPLOYED, Duration.ZERO).isOpen()).isTrue();
        verify(jdbc, never()).queryForList(anyString(), eq(Timestamp.class), any());
    }

    @Test void 적용_시각은_한_번만_읽는다() {
        deployedAt(DEPLOYED);
        HouseFeedPushGate gate = gate(DEPLOYED.plus(Duration.ofDays(2)), Duration.ofHours(24));
        assertThat(gate.isOpen()).isTrue();
        assertThat(gate.isOpen()).isTrue();
        verify(jdbc, times(1)).queryForList(anyString(), eq(Timestamp.class), any());
    }

    @Test void 적용_시각을_알_수_없으면_보내지_않고_다음에_다시_읽는다() {
        deployedAt(null);
        HouseFeedPushGate gate = gate(DEPLOYED.plus(Duration.ofDays(2)), Duration.ofHours(24));
        assertThat(gate.isOpen()).isFalse();
        when(jdbc.queryForList(anyString(), eq(Timestamp.class), eq(HouseFeedPushGate.MIGRATION_DESCRIPTION)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        assertThat(gate.isOpen()).isFalse();
        // 이력을 읽을 수 있게 되면 그때부터 열림.
        org.mockito.Mockito.reset(jdbc);
        deployedAt(DEPLOYED);
        assertThat(gate.isOpen()).isTrue();
    }
}
