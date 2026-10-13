package com.triples.rougether.userapi.minigame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.minigame.dto.MinigameAction;
import com.triples.rougether.userapi.minigame.dto.MinigameDirection;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishRequest;
import com.triples.rougether.userapi.minigame.error.MinigameErrorCode;
import com.triples.rougether.userapi.minigame.service.MinigameCatalog;
import com.triples.rougether.userapi.minigame.service.TowerReplayVerifier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TowerReplayVerifierTest {
    private final TowerReplayVerifier verifier = new TowerReplayVerifier();

    @Test
    void 게임_코드와_시간_한도와_규칙_버전을_노출한다() {
        assertThat(verifier.gameCode()).isEqualTo(MinigameCatalog.TOWER);
        assertThat(verifier.maxTicks()).isEqualTo(18_000);
        assertThat(verifier.rulesVersion()).isEqualTo(3);
    }

    // 모바일 tower-engine.ts로 만든 픽스처를 그대로 재생해 점수·층수·종료 사유까지 맞춰 봄.
    @Test
    void 모바일과_동일한_JSON_픽스처를_점수와_종료_사유까지_재현한다() throws IOException {
        try (var input = getClass().getResourceAsStream("/minigame/tower-fixtures.json")) {
            var root = JsonMapper.builder().build().readTree(input);
            assertThat(root.get("rulesVersion").intValue()).isEqualTo(3);
            var rules = root.get("rules");
            assertThat(rules.get("maxTicks").intValue()).isEqualTo(TowerReplayVerifier.MAX_TICKS);
            assertThat(rules.get("maxDrops").intValue()).isEqualTo(TowerReplayVerifier.MAX_DROPS);
            int count = 0;
            for (var fixture : root.get("fixtures")) {
                String name = fixture.get("name").stringValue();
                List<Integer> drops = new ArrayList<>();
                fixture.get("dropTicks").forEach(drop -> drops.add(drop.intValue()));
                int seed = fixture.get("seed").intValue();
                int ticks = fixture.get("ticks").intValue();

                var result = verifier.verify(seed, 3, ticks, drops);
                assertThat(result.score()).as(name).isEqualTo(fixture.get("score").intValue());
                assertThat(result.layers()).as(name).isEqualTo(fixture.get("layers").intValue());
                assertThat(result.endReason()).as(name).isEqualTo(fixture.get("endReason").stringValue());
                assertThat(verifier.verify(seed, 3, new MinigameFinishRequest(ticks, drops)))
                        .as(name).isEqualTo(result.score());

                // 끝나기 전 틱에 제출하면 미완료, 끝난 뒤 틱을 덧붙이면 조작임.
                List<Integer> beforeEnd = drops.stream().filter(drop -> drop < ticks).toList();
                assertError(seed, ticks - 1, beforeEnd, MinigameErrorCode.RUN_NOT_FINISHED);
                if (ticks < TowerReplayVerifier.MAX_TICKS) {
                    assertError(seed, ticks + 1, drops, MinigameErrorCode.INVALID_REPLAY);
                }
                count++;
            }
            assertThat(count).isEqualTo(5);
        }
    }

    @Test
    void 실패_뒤에_떨어뜨린_입력을_덧붙이면_거절한다() {
        // every_40_ticks_until_miss: seed 7은 200틱 다섯 번째 상자에서 실패함.
        assertThat(verifier.verify(7, 3, 200, List.of(40, 80, 120, 160, 200)).endReason()).isEqualTo("miss");
        assertError(7, 240, List.of(40, 80, 120, 160, 200, 240), MinigameErrorCode.INVALID_REPLAY);
    }

    @Test
    void 쿨다운보다_짧은_간격이나_역순_중복_범위밖_입력은_거절한다() {
        for (List<Integer> drops : List.of(List.of(40, 45), List.of(80, 40), List.of(40, 40),
                List.of(0), List.of(-1), List.of(201))) {
            assertError(7, 200, drops, MinigameErrorCode.INVALID_REPLAY);
        }
        assertError(7, 200, Arrays.asList(40, null), MinigameErrorCode.INVALID_REPLAY);
        assertError(7, 200, null, MinigameErrorCode.INVALID_REPLAY);
        // 간격이 정확히 6이면 허용됨 (mash_every_tick 픽스처와 같은 입력).
        assertThat(verifier.verify(99, 3, 13, List.of(1, 7, 13)).score()).isEqualTo(2);
    }

    @Test
    void 시간_한도와_입력_개수_상한을_넘으면_거절한다() {
        assertError(1, 0, List.of(), MinigameErrorCode.INVALID_REPLAY);
        assertError(1, 18_001, List.of(), MinigameErrorCode.INVALID_REPLAY);
        List<Integer> tooMany = IntStream.rangeClosed(1, 3_001).map(index -> index * 6 - 5).boxed().toList();
        assertError(1, 18_000, tooMany, MinigameErrorCode.INVALID_REPLAY);
    }

    @Test
    void 방향_입력을_보내면_거절한다() {
        var request = new MinigameFinishRequest(200, null,
                List.of(new MinigameAction(40, MinigameDirection.LEFT)));
        assertThatThrownBy(() -> verifier.validateInput(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(MinigameErrorCode.INVALID_REPLAY));
        var both = new MinigameFinishRequest(200, List.of(40), List.of());
        assertThatThrownBy(() -> verifier.verify(7, 3, both))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(MinigameErrorCode.INVALID_REPLAY));
    }

    // 탑 쌓기는 v3에서 생겼으므로 다른 버전으로 발급된 세션은 재생하지 않음.
    @Test
    void 규칙_버전_3이_아니거나_seed가_양수가_아니면_거절한다() {
        for (int rulesVersion : new int[] {1, 2, 4}) {
            assertThatThrownBy(() -> verifier.verify(7, rulesVersion, 200, List.of(40, 80, 120, 160, 200)))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(MinigameErrorCode.INVALID_REPLAY));
        }
        assertError(0, 200, List.of(), MinigameErrorCode.INVALID_REPLAY);
    }

    private void assertError(int seed, int ticks, List<Integer> drops, MinigameErrorCode code) {
        assertThatThrownBy(() -> verifier.verify(seed, 3, ticks, drops))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
