package com.triples.rougether.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.domain.market.entity.MarketAsset;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class MarketAssetTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @ParameterizedTest
    @ValueSource(ints = {0, 11})
    void 발행_수량은_1에서_10_사이여야_한다(int supply) {
        assertThatThrownBy(() -> MarketAsset.issue(1L, 2L, supply, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 제작자_여부는_탈퇴로_비워진_경우_아무도_해당하지_않는다() {
        MarketAsset asset = MarketAsset.issue(1L, 2L, 10, NOW);

        assertThat(asset.isCreator(2L)).isTrue();
        assertThat(asset.isCreator(3L)).isFalse();
        assertThat(asset.getUnissuedQuantity()).isEqualTo(9);
    }
}
