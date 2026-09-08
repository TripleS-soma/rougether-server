package com.triples.rougether.userapi.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

// 날짜를 판정하는 서비스들이 주입받는 시계의 존 — 이 값이 바뀌면 KST 자정 직후 날짜가 통째로 틀어진다
// (spec api.md "날짜와 시각": 모든 날짜·당일 판정은 Asia/Seoul). DateBoundaryContractTest도 이 존을 이어받아 검증한다
class SchedulingConfigTest {

    @Test
    void kstClock은_Asia_Seoul_존이다() {
        assertThat(new SchedulingConfig().kstClock().getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }
}
