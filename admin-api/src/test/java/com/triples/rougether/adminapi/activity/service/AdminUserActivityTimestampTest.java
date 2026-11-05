package com.triples.rougether.adminapi.activity.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class AdminUserActivityTimestampTest {
    @Test
    void 운영_구매_시각의_epoch를_UTC로보존하고_KST에는12시로표시한다() {
        Instant at = AdminUserActivityService.toInstant(new BigDecimal("1793850094.400351"));
        assertThat(at).isEqualTo(Instant.parse("2026-11-05T03:41:34.400351Z"));
        assertThat(at.atZone(ZoneId.of("Asia/Seoul")).getHour()).isEqualTo(12);
        assertThat(AdminUserActivityService.toInstant(null)).isNull();
    }
}
