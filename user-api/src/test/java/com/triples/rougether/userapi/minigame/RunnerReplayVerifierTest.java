package com.triples.rougether.userapi.minigame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.minigame.error.MinigameErrorCode;
import com.triples.rougether.userapi.minigame.service.RunnerReplayVerifier;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RunnerReplayVerifierTest {
    private final RunnerReplayVerifier verifier = new RunnerReplayVerifier();

    @Test
    void 모바일과_동일한_JSON_픽스처로_5분_제한까지_전_구간을_검증한다() throws IOException {
        try (var input = getClass().getResourceAsStream("/minigame/runner-fixtures.json")) {
            var fixtures = JsonMapper.builder().build().readTree(input);
            for (var fixture : fixtures) {
                List<Integer> jumps = new java.util.ArrayList<>();
                fixture.get("jumpTicks").forEach(jump -> jumps.add(jump.intValue()));
                int seed = fixture.get("seed").intValue();
                int ticks = fixture.get("ticks").intValue();
                assertThat(verifier.verify(seed, 1, ticks, jumps))
                        .as(fixture.get("name").stringValue()).isEqualTo(fixture.get("score").intValue());
                assertError(seed, ticks - 1, jumps, MinigameErrorCode.RUN_NOT_FINISHED);
            }
        }
    }

    // 모바일 TypeScript 엔진을 독립 실행한 결과로 서버의 충돌 프레임을 교차 검증함.
    @Test
    void 세_seed의_무입력_플레이는_188틱에_충돌하고_31점이다() {
        for (int seed : new int[] {1, 42, Integer.MAX_VALUE}) {
            assertThat(verifier.verify(seed, 1, 188, List.of())).isEqualTo(31);
            assertError(seed, 187, List.of(), MinigameErrorCode.RUN_NOT_FINISHED);
            assertError(seed, 189, List.of(), MinigameErrorCode.INVALID_REPLAY);
        }
    }

    @Test
    void 장애물을_넘은_입력은_다음_장애물의_정확한_충돌_시점만_허용한다() {
        assertThat(verifier.verify(1, 1, 301, List.of(180))).isEqualTo(50);
        assertThat(verifier.verify(1, 1, 301, List.of(175))).isEqualTo(50);
        assertError(1, 300, List.of(180), MinigameErrorCode.RUN_NOT_FINISHED);
        assertError(1, 302, List.of(180), MinigameErrorCode.INVALID_REPLAY);
    }

    @Test
    void 공중_점프나_중복_역순_미래_입력은_거절한다() {
        for (List<Integer> jumps : List.of(List.of(180, 181), List.of(180, 180),
                List.of(181, 180), List.of(0), List.of(302))) {
            assertError(1, 301, jumps, MinigameErrorCode.INVALID_REPLAY);
        }
        assertError(1, 301, Arrays.asList(180, null), MinigameErrorCode.INVALID_REPLAY);
    }

    @Test
    void 전체_기록의_길이와_규칙_버전_경계를_검증한다() {
        assertError(1, 0, List.of(), MinigameErrorCode.INVALID_REPLAY);
        assertError(1, 18001, List.of(), MinigameErrorCode.INVALID_REPLAY);
        assertError(1, 18000, IntStream.rangeClosed(1, 601).boxed().toList(), MinigameErrorCode.INVALID_REPLAY);
        assertError(1, 188, null, MinigameErrorCode.INVALID_REPLAY);
        assertThatThrownBy(() -> verifier.verify(1, 2, 188, List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(MinigameErrorCode.INVALID_REPLAY));
    }

    private void assertError(int seed, int ticks, List<Integer> jumps, MinigameErrorCode code) {
        assertThatThrownBy(() -> verifier.verify(seed, 1, ticks, jumps))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
