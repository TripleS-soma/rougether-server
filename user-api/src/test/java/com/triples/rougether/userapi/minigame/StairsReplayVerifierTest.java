package com.triples.rougether.userapi.minigame;

import static com.triples.rougether.userapi.minigame.dto.MinigameDirection.LEFT;
import static com.triples.rougether.userapi.minigame.dto.MinigameDirection.RIGHT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.minigame.dto.MinigameAction;
import com.triples.rougether.userapi.minigame.dto.MinigameDirection;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishRequest;
import com.triples.rougether.userapi.minigame.error.MinigameErrorCode;
import com.triples.rougether.userapi.minigame.service.StairsReplayVerifier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StairsReplayVerifierTest {
    private final StairsReplayVerifier verifier = new StairsReplayVerifier();

    @Test
    void 모바일_JSON_픽스처의_난수와_종료_시점_점수를_독립_재생한다() throws IOException {
        try (var input = getClass().getResourceAsStream("/minigame/stairs-fixtures.json")) {
            var document = JsonMapper.builder().build().readTree(input);
            assertThat(document.get("rulesVersion").intValue()).isEqualTo(verifier.rulesVersion());
            for (var fixture : document.get("fixtures")) {
                List<MinigameAction> actions = new ArrayList<>();
                fixture.get("actions").forEach(action -> actions.add(new MinigameAction(
                        action.get("tick").intValue(),
                        MinigameDirection.valueOf(action.get("direction").stringValue()))));
                int seed = fixture.get("seed").intValue();
                int ticks = fixture.get("ticks").intValue();
                assertThat(verifier.verify(seed, 1, request(ticks, actions)))
                        .as(fixture.get("name").stringValue())
                        .isEqualTo(fixture.get("score").intValue());
                if (ticks > 1) {
                    var earlier = actions.stream().filter(action -> action.tick() < ticks).toList();
                    assertError(seed, request(ticks - 1, earlier), MinigameErrorCode.RUN_NOT_FINISHED);
                }
                if (ticks < StairsReplayVerifier.MAX_TICKS) {
                    assertError(seed, request(ticks + 1, actions), MinigameErrorCode.INVALID_REPLAY);
                }
            }
        }
    }

    @Test
    void 무입력은_seed와_무관하게_180틱에_0점으로_종료한다() {
        for (int seed : new int[] {1, 42, Integer.MAX_VALUE}) {
            assertThat(verifier.verify(seed, 1, request(180, List.of()))).isZero();
            assertError(seed, request(179, List.of()), MinigameErrorCode.RUN_NOT_FINISHED);
            assertError(seed, request(181, List.of()), MinigameErrorCode.INVALID_REPLAY);
        }
    }

    @Test
    void 첫_틱부터_입력할_수_있고_잘못된_방향은_즉시_종료한다() {
        assertThat(verifier.verify(1, 1, request(1, List.of(new MinigameAction(1, RIGHT))))).isZero();
        assertThat(verifier.verify(1, 1,
                request(7, List.of(new MinigameAction(1, LEFT), new MinigameAction(7, RIGHT)))))
                .isEqualTo(1);
        assertError(1, request(8, List.of(new MinigameAction(1, LEFT), new MinigameAction(7, RIGHT))),
                MinigameErrorCode.INVALID_REPLAY);
        assertError(1, request(13, List.of(new MinigameAction(1, LEFT), new MinigameAction(7, RIGHT),
                new MinigameAction(13, LEFT))), MinigameErrorCode.INVALID_REPLAY);
    }

    @Test
    void 정답은_타이머를_초기화하고_5번째_정답부터_제한시간을_줄인다() {
        assertThat(verifier.verify(1, 1, request(181, List.of(new MinigameAction(1, LEFT)))))
                .isEqualTo(1);
        assertThat(verifier.verify(1, 1, request(359, List.of(new MinigameAction(179, LEFT)))))
                .isEqualTo(1);
        var fiveCorrect = steps("LLLRL");
        assertThat(verifier.verify(1, 1, request(199, fiveCorrect))).isEqualTo(5);
        assertError(1, request(198, fiveCorrect), MinigameErrorCode.RUN_NOT_FINISHED);
        assertError(1, request(200, fiveCorrect), MinigameErrorCode.INVALID_REPLAY);
    }

    @Test
    void 만료_틱에는_정답이나_오답을_기록할_수_없다() {
        assertError(1, request(180, List.of(new MinigameAction(180, LEFT))),
                MinigameErrorCode.INVALID_REPLAY);
        assertError(1, request(180, List.of(new MinigameAction(180, RIGHT))),
                MinigameErrorCode.INVALID_REPLAY);
        assertError(1, request(181, List.of(new MinigameAction(1, LEFT), new MinigameAction(181, LEFT))),
                MinigameErrorCode.INVALID_REPLAY);
    }

    @Test
    void 벽에서_방향을_강제해도_난수를_소비하며_32비트_seed를_보존한다() {
        assertThat(verifier.verify(1, 1, request(271, steps("LLLRLRLRLRRLRRRLRLRR"))))
                .isEqualTo(20);
        assertThat(verifier.verify(Integer.MAX_VALUE, 1,
                request(271, steps("RRRLRLRLLRLRLLLLLRRR"))))
                .isEqualTo(20);
    }

    @Test
    void 입력_간격은_6틱부터_허용하며_중복_역순_미래_음수_입력을_거절한다() {
        assertThatCode(() -> verifier.validateInput(request(7,
                List.of(new MinigameAction(1, LEFT), new MinigameAction(7, LEFT)))))
                .doesNotThrowAnyException();
        for (List<MinigameAction> actions : List.of(
                List.of(new MinigameAction(1, LEFT), new MinigameAction(6, LEFT)),
                List.of(new MinigameAction(1, LEFT), new MinigameAction(1, LEFT)),
                List.of(new MinigameAction(7, LEFT), new MinigameAction(1, LEFT)),
                List.of(new MinigameAction(-1, LEFT)),
                List.of(new MinigameAction(0, LEFT)),
                List.of(new MinigameAction(181, LEFT)))) {
            assertError(1, request(180, actions), MinigameErrorCode.INVALID_REPLAY);
        }
    }

    @Test
    void 입력_개수와_전체_틱의_상한을_검증한다() {
        var maximumActions = IntStream.range(0, 1200)
                .mapToObj(index -> new MinigameAction(1 + index * 6, LEFT)).toList();
        assertThatCode(() -> verifier.validateInput(request(7200, maximumActions)))
                .doesNotThrowAnyException();
        var tooManyActions = new ArrayList<>(maximumActions);
        tooManyActions.add(new MinigameAction(7200, LEFT));
        assertError(1, request(7200, tooManyActions), MinigameErrorCode.INVALID_REPLAY);
        for (Integer ticks : Arrays.asList(null, -1, 0, 7201, Integer.MAX_VALUE)) {
            assertError(1, request(ticks, List.of()), MinigameErrorCode.INVALID_REPLAY);
        }
    }

    @Test
    void null과_러너_입력_다른_게임의_방향을_거절한다() {
        for (MinigameFinishRequest request : Arrays.asList(null,
                request(180, null),
                request(180, Arrays.asList((MinigameAction) null)),
                request(180, List.of(new MinigameAction(null, LEFT))),
                request(180, List.of(new MinigameAction(1, null))),
                request(180, List.of(new MinigameAction(1, MinigameDirection.UP))),
                request(180, List.of(new MinigameAction(1, MinigameDirection.DOWN))),
                new MinigameFinishRequest(180, List.of(), List.of()),
                new MinigameFinishRequest(180, List.of(1), List.of()))) {
            assertError(1, request, MinigameErrorCode.INVALID_REPLAY);
        }
    }

    @Test
    void seed와_규칙_버전을_검증한다() {
        for (int seed : new int[] {Integer.MIN_VALUE, -1, 0}) {
            assertError(seed, request(180, List.of()), MinigameErrorCode.INVALID_REPLAY);
        }
        for (int version : new int[] {-1, 0, 2, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> verifier.verify(1, version, request(180, List.of())))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(MinigameErrorCode.INVALID_REPLAY));
        }
    }

    private static List<MinigameAction> steps(String directions) {
        return IntStream.range(0, directions.length())
                .mapToObj(index -> new MinigameAction(1 + index * 6,
                        directions.charAt(index) == 'L' ? LEFT : RIGHT)).toList();
    }

    private static MinigameFinishRequest request(Integer ticks, List<MinigameAction> actions) {
        return new MinigameFinishRequest(ticks, null, actions);
    }

    private void assertError(int seed, MinigameFinishRequest request, MinigameErrorCode code) {
        assertThatThrownBy(() -> verifier.verify(seed, 1, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
