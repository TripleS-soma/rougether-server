package com.triples.rougether.userapi.minigame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.minigame.dto.MinigameAction;
import com.triples.rougether.userapi.minigame.dto.MinigameDirection;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishRequest;
import com.triples.rougether.userapi.minigame.error.MinigameErrorCode;
import com.triples.rougether.userapi.minigame.service.MergeReplayVerifier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MergeReplayVerifierTest {
    private final MergeReplayVerifier verifier = new MergeReplayVerifier();

    @Test
    void 모바일_TypeScript_픽스처와_동일한_점수를_계산한다() throws IOException {
        for (Fixture fixture : fixtures()) {
            assertThat(verifier.verify(fixture.seed(), 1, request(fixture.ticks(), fixture.actions())))
                    .as(fixture.name()).isEqualTo(fixture.score());
        }
    }

    @Test
    void 버전2_추가_생성과_가득_찬_보드_생략까지_모바일_픽스처와_일치한다() throws IOException {
        assertThat(verifier.rulesVersion()).isEqualTo(2);
        for (Fixture fixture : fixtures("merge-v2-fixtures.json")) {
            assertThat(verifier.verify(fixture.seed(), 2, request(fixture.ticks(), fixture.actions())))
                    .as(fixture.name()).isEqualTo(fixture.score());
        }
    }

    @Test
    void 저장된_규칙_버전으로_초기_보드와_점수를_구분한다() {
        List<MinigameAction> actions = List.of(new MinigameAction(1, MinigameDirection.UP));
        assertThat(verifier.verify(2, 1, request(1, actions))).isEqualTo(4);
        assertThat(verifier.verify(2, 2, request(1, actions))).isZero();
        assertThat(verifier.verify(1, 2, request(1, List.of()))).isZero();
    }

    @Test
    void 버전2_이동하지_않은_추가_입력은_거절한다() {
        List<MinigameAction> actions = List.of(new MinigameAction(1, MinigameDirection.UP),
                new MinigameAction(2, MinigameDirection.UP));
        assertInvalid(1, 2, request(2, actions));
    }

    @Test
    void 버전2도_같은_이동에서_합친_타일을_다시_합치지_않는다() {
        MinigameDirection[] cycle = {MinigameDirection.UP, MinigameDirection.RIGHT,
                MinigameDirection.DOWN, MinigameDirection.LEFT};
        List<MinigameAction> actions = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            actions.add(new MinigameAction(index + 1, cycle[index % 4]));
        }
        assertThat(verifier.verify(1, 2, request(17, actions))).isEqualTo(96);
        actions.add(new MinigameAction(18, MinigameDirection.LEFT));
        assertThat(verifier.verify(1, 2, request(18, actions))).isEqualTo(104);
    }

    @Test
    void 버전2도_막힌_이후의_입력은_거절한다() throws IOException {
        Fixture blocked = fixtures("merge-v2-fixtures.json").stream()
                .filter(fixture -> fixture.name().equals("blocked")).findFirst().orElseThrow();
        for (MinigameDirection direction : MinigameDirection.values()) {
            List<MinigameAction> extended = new ArrayList<>(blocked.actions());
            extended.add(new MinigameAction(blocked.ticks() + 1, direction));
            assertInvalid(blocked.seed(), 2, request(blocked.ticks() + 1, extended));
        }
    }

    @Test
    void 이동하지_않은_수동_종료와_시간_제한_종료는_0점이다() {
        for (int seed : new int[] {1, 42, Integer.MAX_VALUE}) {
            assertThat(verifier.verify(seed, 1, request(1, List.of()))).isZero();
            assertThat(verifier.verify(seed, 1, request(MergeReplayVerifier.MAX_TICKS, List.of())))
                    .isZero();
        }
    }

    @Test
    void 다른_방향으로_이동할_수_있어도_보드를_바꾸지_않는_입력은_거절한다() {
        // 모바일 엔진의 seed 7 초기 보드는 첫 행에 2, 4가 놓임.
        assertInvalid(7, 1, request(1, List.of(new MinigameAction(1, MinigameDirection.UP))));
        assertThat(verifier.verify(7, 1,
                request(1, List.of(new MinigameAction(1, MinigameDirection.RIGHT))))).isZero();
    }

    @Test
    void 같은_이동에서_새로_합친_타일을_다시_합치지_않는다() {
        // 모바일 seed 1에서 UP 두 번 뒤 첫 행은 [4, 2, 2, 0]이며 점수는 0임.
        // RIGHT로 2+2만 합쳐 [0, 0, 4, 4]가 됨. 연쇄 합치기로 8을 만들면 안 됨.
        List<MinigameAction> actions = List.of(new MinigameAction(10, MinigameDirection.UP),
                new MinigameAction(20, MinigameDirection.UP),
                new MinigameAction(30, MinigameDirection.RIGHT));
        assertThat(verifier.verify(1, 1, request(30, actions))).isEqualTo(4);
    }

    @Test
    void 이동_가능한_상태에서_수동_종료는_허용하고_추가_noop은_거절한다() {
        List<MinigameAction> actions = List.of(new MinigameAction(10, MinigameDirection.UP),
                new MinigameAction(20, MinigameDirection.UP),
                new MinigameAction(30, MinigameDirection.UP));
        assertThat(verifier.verify(1, 1, request(60, actions))).isEqualTo(8);
        List<MinigameAction> extended = new ArrayList<>(actions);
        extended.add(new MinigameAction(40, MinigameDirection.UP));
        assertInvalid(request(60, extended));
    }

    @Test
    void 막힌_보드의_마지막_이동은_인정하고_그_뒤_입력은_모두_거절한다() throws IOException {
        Fixture blocked = fixtures().stream().filter(fixture -> fixture.name().equals("blocked"))
                .findFirst().orElseThrow();
        assertThat(verifier.verify(blocked.seed(), 1, request(MergeReplayVerifier.MAX_TICKS, blocked.actions())))
                .isEqualTo(blocked.score());
        for (MinigameDirection direction : MinigameDirection.values()) {
            List<MinigameAction> extended = new ArrayList<>(blocked.actions());
            extended.add(new MinigameAction(blocked.ticks() + 1, direction));
            assertInvalid(blocked.seed(), 1, request(blocked.ticks() + 1, extended));
        }
    }

    @Test
    void 요청과_입력_종류의_누락이나_혼용을_거절한다() {
        assertInvalid(null);
        assertInvalid(new MinigameFinishRequest(null, null, List.of()));
        assertInvalid(new MinigameFinishRequest(1, null, null));
        assertInvalid(new MinigameFinishRequest(1, List.of(), List.of()));
        assertInvalid(new MinigameFinishRequest(1, List.of(1), List.of()));
    }

    @Test
    void 이동_시간은_1부터_종료_시간까지_엄격한_오름차순이어야_한다() {
        for (List<MinigameAction> actions : List.of(
                List.of(action(0)), List.of(action(-1)), List.of(action(11)),
                List.of(action(1), action(1)), List.of(action(2), action(1)))) {
            assertInvalid(request(10, actions));
        }
        assertInvalid(request(10, Arrays.asList(action(1), null)));
        assertInvalid(request(10, List.of(new MinigameAction(null, MinigameDirection.LEFT))));
        assertInvalid(request(10, List.of(new MinigameAction(1, null))));
    }

    @Test
    void 시간과_이동_횟수_제한을_넘은_입력을_거절한다() {
        assertInvalid(request(0, List.of()));
        assertInvalid(request(-1, List.of()));
        assertInvalid(request(MergeReplayVerifier.MAX_TICKS + 1, List.of()));
        List<MinigameAction> tooMany = IntStream.rangeClosed(1, MergeReplayVerifier.MAX_ACTIONS + 1)
                .mapToObj(MergeReplayVerifierTest::action).toList();
        assertThatCode(() -> verifier.validateInput(request(MergeReplayVerifier.MAX_TICKS,
                tooMany.subList(0, MergeReplayVerifier.MAX_ACTIONS)))).doesNotThrowAnyException();
        assertInvalid(request(MergeReplayVerifier.MAX_TICKS, tooMany));
    }

    @Test
    void 지원하지_않는_seed와_규칙_버전을_거절한다() {
        for (int seed : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertInvalid(seed, 1, request(1, List.of()));
        }
        for (int rulesVersion : new int[] {0, -1, 3, Integer.MAX_VALUE}) {
            assertInvalid(1, rulesVersion, request(1, List.of()));
        }
    }

    private static MinigameAction action(int tick) {
        return new MinigameAction(tick, MinigameDirection.LEFT);
    }

    private static MinigameFinishRequest request(int ticks, List<MinigameAction> actions) {
        return new MinigameFinishRequest(ticks, null, actions);
    }

    private void assertInvalid(MinigameFinishRequest request) {
        assertInvalid(1, 1, request);
    }

    private void assertInvalid(int seed, int rulesVersion, MinigameFinishRequest request) {
        assertThatThrownBy(() -> verifier.verify(seed, rulesVersion, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(MinigameErrorCode.INVALID_REPLAY));
    }

    private List<Fixture> fixtures() throws IOException {
        return fixtures("merge-fixtures.json");
    }

    private List<Fixture> fixtures(String filename) throws IOException {
        try (var input = getClass().getResourceAsStream("/minigame/" + filename)) {
            assertThat(input).isNotNull();
            var json = JsonMapper.builder().build().readTree(input);
            List<Fixture> fixtures = new ArrayList<>();
            for (var fixture : json) {
                List<MinigameAction> actions = new ArrayList<>();
                for (var action : fixture.get("actions")) {
                    actions.add(new MinigameAction(action.get("tick").intValue(),
                            MinigameDirection.valueOf(action.get("direction").stringValue())));
                }
                fixtures.add(new Fixture(fixture.get("name").stringValue(), fixture.get("seed").intValue(),
                        fixture.get("ticks").intValue(), actions, fixture.get("score").intValue()));
            }
            assertThat(fixtures).isNotEmpty();
            return fixtures;
        }
    }

    private record Fixture(String name, int seed, int ticks, List<MinigameAction> actions, int score) {}
}
