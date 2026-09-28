package com.triples.rougether.domain.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.market.entity.MarketEngineLease;
import java.lang.reflect.Constructor;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MarketEngineLeaseTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Test
    void 인수할_때마다_펜싱_번호가_오르고_반납해도_번호는_유지된다() throws Exception {
        MarketEngineLease lease = emptyLease();
        assertThat(lease.isExpired(NOW)).isTrue();

        long first = lease.takeOver("a", NOW.plusSeconds(10));
        assertThat(lease.isOwnedBy("a")).isTrue();
        assertThat(lease.isExpired(NOW.plusSeconds(9))).isFalse();
        assertThat(lease.isExpired(NOW.plusSeconds(10))).isTrue(); // 유효기간 끝 시각은 만료로 봄

        lease.release();
        assertThat(lease.isOwnedBy("a")).isFalse();
        assertThat(lease.isExpired(NOW)).isTrue();
        assertThat(lease.getFencingToken()).isEqualTo(first);

        assertThat(lease.takeOver("b", NOW.plusSeconds(10))).isEqualTo(first + 1);
    }

    @Test
    void 연장하면_유효기간만_바뀌고_순번은_이어진다() throws Exception {
        MarketEngineLease lease = emptyLease();
        lease.takeOver("a", NOW.plusSeconds(10));
        lease.extend(NOW.plusSeconds(20));

        assertThat(lease.isExpired(NOW.plusSeconds(15))).isFalse();
        assertThat(lease.nextSeq()).isEqualTo(1);
        assertThat(lease.nextSeq()).isEqualTo(2);
    }

    private static MarketEngineLease emptyLease() throws Exception {
        Constructor<MarketEngineLease> constructor = MarketEngineLease.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}
