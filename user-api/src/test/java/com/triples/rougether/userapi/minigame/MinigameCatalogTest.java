package com.triples.rougether.userapi.minigame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.minigame.dto.MinigameListResponse;
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
        assertThat(catalog.list(3).items()).hasSize(5).allSatisfy(item -> assertThat(item.rulesVersion()).isEqualTo(3));
        assertThat(catalog.list(1).items()).isEqualTo(catalog.list().items());
    }

    // 탑 쌓기는 v3에서 생겨 구 앱(v1·v2) 목록에는 보이지 않고 그 버전으로 시작할 수도 없음.
    @Test
    void 탑_쌓기는_v3_목록에만_보이고_v3_미만으로는_시작할_수_없다() {
        for (int rulesVersion : new int[] {1, 2}) {
            assertThat(catalog.list(rulesVersion).items()).extracting(MinigameListResponse.Item::gameCode)
                    .containsExactly(MinigameCatalog.RUNNER, MinigameCatalog.STAIRS, MinigameCatalog.MERGE);
            assertThatThrownBy(() -> catalog.requireRulesVersion(MinigameCatalog.TOWER, rulesVersion))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(MinigameErrorCode.RULES_VERSION_NOT_SUPPORTED));
            catalog.requireRulesVersion(MinigameCatalog.RUNNER, rulesVersion);
        }
        assertThat(catalog.list(3).items()).extracting(MinigameListResponse.Item::gameCode)
                .containsExactly(MinigameCatalog.RUNNER, MinigameCatalog.STAIRS, MinigameCatalog.MERGE,
                        MinigameCatalog.TOWER, MinigameCatalog.YARN);
        assertThat(catalog.list(3).items().get(3)).satisfies(item -> {
            assertThat(item.name()).isEqualTo("고양이 탑 쌓기");
            assertThat(item.description()).isEqualTo("흔들리는 상자를 딱 맞춰 쌓아 올려요.");
        });
        catalog.requireRulesVersion(MinigameCatalog.TOWER, 3);
        assertThatThrownBy(() -> catalog.requireRulesVersion(MinigameCatalog.TOWER, 4))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(MinigameErrorCode.RULES_VERSION_NOT_SUPPORTED));
        assertThatThrownBy(() -> catalog.requireRulesVersion("unknown", 3))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(MinigameErrorCode.GAME_NOT_FOUND));
    }

    // 털실 정리(mobile #1563)도 v3에서 생겨 구 앱 목록에는 없고 v3로만 시작함.
    @Test
    void 털실_정리는_v3_목록의_마지막에만_보이고_v3로만_시작할_수_있다() {
        for (int rulesVersion : new int[] {1, 2}) {
            assertThat(catalog.list(rulesVersion).items()).extracting(MinigameListResponse.Item::gameCode)
                    .doesNotContain(MinigameCatalog.YARN);
            assertThatThrownBy(() -> catalog.requireRulesVersion(MinigameCatalog.YARN, rulesVersion))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(MinigameErrorCode.RULES_VERSION_NOT_SUPPORTED));
        }
        assertThat(catalog.list(3).items()).last().satisfies(item -> {
            assertThat(item.gameCode()).isEqualTo("cat-yarn");
            assertThat(item.name()).isEqualTo("고양이 털실 정리");
            assertThat(item.description()).isEqualTo("털실을 같은 색 바구니에 담아 방 그림을 채워요.");
            assertThat(item.rulesVersion()).isEqualTo(3);
        });
        catalog.requireGame(MinigameCatalog.YARN);
        catalog.requireRulesVersion(MinigameCatalog.YARN, 3);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 4, 99, Integer.MAX_VALUE})
    void 알_수_없는_버전은_목록부터_거부한다(int rulesVersion) {
        assertThatThrownBy(() -> catalog.list(rulesVersion)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(MinigameErrorCode.RULES_VERSION_NOT_SUPPORTED));
    }
}
