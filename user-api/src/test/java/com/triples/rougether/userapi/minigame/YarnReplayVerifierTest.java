package com.triples.rougether.userapi.minigame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.minigame.dto.MinigameAction;
import com.triples.rougether.userapi.minigame.dto.MinigameDirection;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishRequest;
import com.triples.rougether.userapi.minigame.error.MinigameErrorCode;
import com.triples.rougether.userapi.minigame.service.MergeReplayVerifier;
import com.triples.rougether.userapi.minigame.service.MinigameCatalog;
import com.triples.rougether.userapi.minigame.service.MinigameReplayVerifier;
import com.triples.rougether.userapi.minigame.service.RunnerReplayVerifier;
import com.triples.rougether.userapi.minigame.service.StairsReplayVerifier;
import com.triples.rougether.userapi.minigame.service.TowerReplayVerifier;
import com.triples.rougether.userapi.minigame.service.YarnReplayVerifier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class YarnReplayVerifierTest {
    private static final int BASKET_CAP = 3;
    private static final int BASKET_SCORE = 10;
    private final YarnReplayVerifier verifier = new YarnReplayVerifier();

    @Test
    void 게임_코드와_집기_한도와_규칙_버전을_노출한다() {
        assertThat(verifier.gameCode()).isEqualTo(MinigameCatalog.YARN);
        // 모바일은 시작 응답의 maxTicks가 2000과 정확히 같아야 판을 시작함.
        assertThat(verifier.maxTicks()).isEqualTo(2_000);
        assertThat(verifier.rulesVersion()).isEqualTo(3);
    }

    // 모바일 yarn-engine.ts로 만든 픽스처를 그대로 재생해 점수·완성 바구니·종료 사유·대기열·색 수·칸 수까지 맞춰 봄.
    @Test
    void 모바일과_동일한_JSON_픽스처를_모든_기대값까지_재현한다() throws IOException {
        JsonNode root = fixtures();
        assertThat(root.get("rulesVersion").intValue()).isEqualTo(3);
        assertThat(root.get("rules").get("maxPicks").intValue()).isEqualTo(YarnReplayVerifier.MAX_PICKS);
        int count = 0;
        for (var fixture : root.get("fixtures")) {
            String name = fixture.get("name").stringValue();
            int seed = fixture.get("seed").intValue();
            List<Integer> picks = picks(fixture);
            var expected = fixture.get("expected");
            int ticks = Math.max(1, picks.size());

            var result = verifier.verify(seed, fixture.get("rulesVersion").intValue(), ticks, picks);
            assertThat(result.score()).as(name).isEqualTo(expected.get("score").intValue());
            assertThat(result.cleared()).as(name).isEqualTo(expected.get("cleared").intValue());
            assertThat(result.endReason()).as(name).isEqualTo(expected.get("endReason").stringValue());
            assertThat(result.waitingLength()).as(name).isEqualTo(expected.get("waitingLength").intValue());
            assertThat(result.palette()).as(name).isEqualTo(expected.get("palette").intValue());
            assertThat(result.capacity()).as(name).isEqualTo(expected.get("capacity").intValue());
            // 점수 불변식: 완성 바구니마다 13점(공 3 + 완성 10), 남은 점수는 열린 바구니에 담긴 공 수(0..2 × 3).
            int open = result.score() - result.cleared() * (BASKET_CAP + BASKET_SCORE);
            assertThat(open).as(name).isBetween(0, 3 * (BASKET_CAP - 1));
            assertThat(verifier.verify(seed, 3, MinigameFinishRequest.ofPicks(ticks, picks)))
                    .as(name).isEqualTo(result.score());

            // 끝난 판 뒤에 집기를 덧붙이면 조작임. 2,000회 한도 판은 DTO·검증기 상한에 먼저 걸림.
            if (!"saved".equals(result.endReason()) && picks.size() < YarnReplayVerifier.MAX_PICKS) {
                List<Integer> extra = new ArrayList<>(picks);
                extra.add(0);
                assertError(seed, extra.size(), extra);
            }
            count++;
        }
        assertThat(count).isEqualTo(9);
    }

    @Test
    void 대기열이_가득_차_끝난_판은_마지막_집기까지만_받는다() throws IOException {
        var fixture = fixture("random_until_full_b");
        int seed = fixture.get("seed").intValue();
        List<Integer> picks = picks(fixture);
        // 마지막 집기 직전은 아직 진행 중 → 직접 마친 판(saved)으로 처리됨.
        List<Integer> beforeEnd = picks.subList(0, picks.size() - 1);
        assertThat(verifier.verify(seed, 3, beforeEnd.size(), beforeEnd).endReason()).isEqualTo("saved");
        assertThat(verifier.verify(seed, 3, picks.size(), picks).endReason()).isEqualTo("full");
        List<Integer> extra = new ArrayList<>(picks);
        extra.addAll(List.of(1, 2));
        assertError(seed, extra.size(), extra);
    }

    @Test
    void 한_번도_안_집고_마친_판은_ticks_1에_0점이다() {
        var result = verifier.verify(1, 3, 1, List.of());
        assertThat(result.score()).isZero();
        assertThat(result.endReason()).isEqualTo("saved");
        assertError(1, 0, List.of());
    }

    @Test
    void 범위_밖_더미나_null_집기는_거절한다() {
        for (List<Integer> picks : List.of(List.of(6), List.of(-1), List.of(0, 7), List.of(Integer.MAX_VALUE))) {
            assertError(1, picks.size(), picks);
        }
        assertError(1, 2, Arrays.asList(0, null));
        assertError(1, 1, null);
    }

    @Test
    void ticks가_집은_횟수와_다르면_거절한다() {
        assertError(777, 3, List.of(2, 2));
        assertError(777, 1, List.of(2, 2));
        assertError(777, 2, List.of());
        assertThat(verifier.verify(777, 3, 2, List.of(2, 2)).endReason()).isEqualTo("saved");
    }

    @Test
    void 집기_한도_2000회를_넘으면_거절한다() {
        List<Integer> tooMany = new ArrayList<>();
        for (int index = 0; index < YarnReplayVerifier.MAX_PICKS + 1; index++) {
            tooMany.add(index % 6);
        }
        assertError(1, tooMany.size(), tooMany);
    }

    @Test
    void 점프나_방향_입력을_보내면_거절한다() {
        assertInvalid(() -> verifier.validateInput(new MinigameFinishRequest(3, List.of(1, 2, 3))));
        assertInvalid(() -> verifier.validateInput(new MinigameFinishRequest(3, null,
                List.of(new MinigameAction(1, MinigameDirection.LEFT)))));
        assertInvalid(() -> verifier.validateInput(new MinigameFinishRequest(1, List.of(), null, List.of())));
        assertInvalid(() -> verifier.validateInput(new MinigameFinishRequest(1, null, null, null)));
    }

    // 다른 네 게임은 picks 필드를 받지 않음. 각 게임의 정상 입력에 picks만 얹어도 거절함.
    @Test
    void 다른_게임에_picks를_보내면_거절한다() {
        List<MinigameReplayVerifier> others = List.of(new RunnerReplayVerifier(), new TowerReplayVerifier());
        for (MinigameReplayVerifier other : others) {
            assertInvalid(() -> other.validateInput(new MinigameFinishRequest(188, List.of(), null, List.of(0))));
        }
        List<MinigameAction> actions = List.of(new MinigameAction(10, MinigameDirection.LEFT));
        for (MinigameReplayVerifier other : List.of(new StairsReplayVerifier(), new MergeReplayVerifier())) {
            assertInvalid(() -> other.validateInput(new MinigameFinishRequest(30, null, actions, List.of(0))));
            assertInvalid(() -> other.validateInput(MinigameFinishRequest.ofPicks(1, List.of(0))));
        }
        assertInvalid(() -> new RunnerReplayVerifier().validateInput(MinigameFinishRequest.ofPicks(1, List.of(0))));
    }

    // 털실 정리는 v3에서 생겼으므로 다른 버전으로 발급된 세션은 재생하지 않음.
    @Test
    void 규칙_버전_3이_아니거나_seed가_양수가_아니면_거절한다() {
        for (int rulesVersion : new int[] {1, 2, 4}) {
            assertInvalid(() -> verifier.verify(777, rulesVersion, 2, List.of(2, 2)));
        }
        assertInvalid(() -> verifier.verify(0, 3, 1, List.of()));
        assertInvalid(() -> verifier.verify(-5, 3, 1, List.of()));
    }

    private JsonNode fixtures() throws IOException {
        try (var input = getClass().getResourceAsStream("/minigame/yarn-fixtures.json")) {
            return JsonMapper.builder().build().readTree(input);
        }
    }

    private JsonNode fixture(String name) throws IOException {
        for (var fixture : fixtures().get("fixtures")) {
            if (fixture.get("name").stringValue().equals(name)) {
                return fixture;
            }
        }
        throw new IllegalArgumentException(name);
    }

    private static List<Integer> picks(JsonNode fixture) {
        List<Integer> picks = new ArrayList<>();
        fixture.get("picks").forEach(pile -> picks.add(pile.intValue()));
        return picks;
    }

    private void assertError(int seed, int ticks, List<Integer> picks) {
        assertInvalid(() -> verifier.verify(seed, 3, ticks, picks));
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(MinigameErrorCode.INVALID_REPLAY));
    }
}
