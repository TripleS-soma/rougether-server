package com.triples.rougether.userapi.minigame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.minigame.error.MinigameErrorCode;
import com.triples.rougether.userapi.minigame.service.MinigameCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MinigameCatalogTest {
    private final MinigameCatalog catalog = new MinigameCatalog();

    @Test
    void 기본_목록은_v1을_유지하고_명시적으로_v2를_선택할_수_있다() {
        assertThat(catalog.list().items()).hasSize(3).allSatisfy(item -> assertThat(item.rulesVersion()).isEqualTo(1));
        assertThat(catalog.list(2).items()).hasSize(3).allSatisfy(item -> assertThat(item.rulesVersion()).isEqualTo(2));
        assertThat(catalog.list(1).items()).isEqualTo(catalog.list().items());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 3, 99, Integer.MAX_VALUE})
    void 알_수_없는_버전은_목록부터_거부한다(int rulesVersion) {
        assertThatThrownBy(() -> catalog.list(rulesVersion)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(MinigameErrorCode.RULES_VERSION_NOT_SUPPORTED));
    }
}
