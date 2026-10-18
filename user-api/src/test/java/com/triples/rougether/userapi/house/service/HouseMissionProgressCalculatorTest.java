package com.triples.rougether.userapi.house.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HouseMissionProgressCalculatorTest {

    @Test
    void 표시_진행률은_소수점_이하를_내림한다() {
        assertThat(HouseMissionProgressCalculator.ratePercent(1L, 3)).isEqualTo(33);
    }

    @Test
    void 삼분의_이는_66퍼센트_목표를_달성하고_67퍼센트_목표는_달성하지_못한다() {
        assertThat(HouseMissionProgressCalculator.ratePercent(2L, 3)).isEqualTo(66);
        assertThat(HouseMissionProgressCalculator.isDailyAchieved(2L, 3, 66)).isTrue();
        assertThat(HouseMissionProgressCalculator.isDailyAchieved(2L, 3, 67)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 구성원이_없으면_진행률은_0이고_달성하지_못한다(int memberCount) {
        assertThat(HouseMissionProgressCalculator.ratePercent(1L, memberCount)).isZero();
        assertThat(HouseMissionProgressCalculator.isDailyAchieved(1L, memberCount, 50)).isFalse();
    }

    @Test
    void 정수_곱셈_범위를_넘는_인원도_long으로_계산한다() {
        int memberCount = Integer.MAX_VALUE;

        assertThat(HouseMissionProgressCalculator.ratePercent(memberCount, memberCount)).isEqualTo(100);
        assertThat(HouseMissionProgressCalculator.isDailyAchieved(memberCount, memberCount, 100)).isTrue();
        assertThat(HouseMissionProgressCalculator.isDailyAchieved((long) memberCount - 1, memberCount, 100))
                .isFalse();
    }
}
