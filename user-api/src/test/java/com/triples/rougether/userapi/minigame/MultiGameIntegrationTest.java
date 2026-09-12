package com.triples.rougether.userapi.minigame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.common.error.ErrorCode;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.minigame.entity.MinigameBestScore;
import com.triples.rougether.domain.minigame.entity.MinigameRun;
import com.triples.rougether.domain.minigame.repository.MinigameBestScoreRepository;
import com.triples.rougether.domain.minigame.repository.MinigameRunRepository;
import com.triples.rougether.userapi.minigame.dto.MinigameAction;
import com.triples.rougether.userapi.minigame.dto.MinigameDirection;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishRequest;
import com.triples.rougether.userapi.minigame.dto.MinigameLeaderboardResponse.Entry;
import com.triples.rougether.userapi.minigame.dto.MinigameListResponse;
import com.triples.rougether.userapi.minigame.error.MinigameErrorCode;
import com.triples.rougether.userapi.minigame.service.MinigameCatalog;
import com.triples.rougether.userapi.minigame.service.MinigameCommandService;
import com.triples.rougether.userapi.minigame.service.MinigameQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class MultiGameIntegrationTest {
    private static final String RUNNER = MinigameCatalog.RUNNER;
    private static final String STAIRS = MinigameCatalog.STAIRS;
    private static final String MERGE = MinigameCatalog.MERGE;
    private static final MinigameFinishRequest RUNNER_FINISH = new MinigameFinishRequest(188, List.of());
    // 모바일의 seed 1 fixture: 1, 7 tick에서 LEFT 입력 후 187 tick에 시간 초과.
    private static final MinigameFinishRequest STAIRS_FINISH = actions(187,
            new MinigameAction(1, MinigameDirection.LEFT), new MinigameAction(7, MinigameDirection.LEFT));
    // 상위 16 bit 난수를 사용하는 모바일 seed 1 fixture: UP 세 번의 합치기 누적 점수는 8.
    private static final MinigameFinishRequest MERGE_FINISH = actions(30,
            new MinigameAction(10, MinigameDirection.UP), new MinigameAction(20, MinigameDirection.UP),
            new MinigameAction(30, MinigameDirection.UP));

    @Autowired private MinigameCatalog catalog;
    @Autowired private MinigameCommandService commandService;
    @Autowired private MinigameQueryService queryService;
    @Autowired private MinigameRunRepository runRepository;
    @Autowired private MinigameBestScoreRepository bestScoreRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private Clock kstClock;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Instant now;

    @BeforeEach
    void setClock() {
        now = Instant.parse("2026-09-12T06:00:00Z");
        when(kstClock.instant()).thenAnswer(invocation -> now);
        when(kstClock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
    }

    @AfterEach
    void deleteOnlyCreatedPlayers() {
        for (Long userId : createdUserIds) {
            jdbc.update("delete from minigame_runs where user_id = ?", userId);
            jdbc.update("delete from minigame_best_scores where user_id = ?", userId);
            jdbc.update("delete from users where id = ?", userId);
        }
    }

    @Test
    void 게임_목록의_세_게임은_각자_규칙의_최대시간으로_입장한다() {
        User player = player("세 게임");
        Map<String, Integer> maxTicks = Map.of(RUNNER, 18_000, STAIRS, 7_200, MERGE, 18_000);

        assertThat(catalog.list().items()).extracting(MinigameListResponse.Item::gameCode)
                .containsExactly(RUNNER, STAIRS, MERGE);
        for (String game : List.of(RUNNER, STAIRS, MERGE)) {
            var started = commandService.start(player.getId(), game);

            assertThat(started.gameCode()).isEqualTo(game);
            assertThat(started.maxTicks()).isEqualTo(maxTicks.get(game));
            assertThat(started.rulesVersion()).isEqualTo(1);
            assertThat(started.seed()).isPositive();
            assertThat(started.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));
            MinigameRun saved = runRepository.findById(started.runId()).orElseThrow();
            assertThat(saved.getUser().getId()).isEqualTo(player.getId());
            assertThat(saved.getGameCode()).isEqualTo(game);
            assertThat(saved.getStartedAt()).isEqualTo(now);
        }
    }

    @Test
    void 같은_사용자라도_다른_게임의_세션으로_제출하면_404다() {
        User player = player("게임 경계");
        for (String sourceGame : List.of(RUNNER, STAIRS, MERGE)) {
            MinigameRun run = readyRun(player, sourceGame);
            for (String targetGame : List.of(RUNNER, STAIRS, MERGE)) {
                if (sourceGame.equals(targetGame)) {
                    continue;
                }
                MinigameFinishRequest request = targetGame.equals(RUNNER) ? RUNNER_FINISH : actions(180);
                assertError(() -> commandService.finish(player.getId(), targetGame, run.getId(), request),
                        MinigameErrorCode.RUN_NOT_FOUND);
            }
            assertUnfinishedWithoutBest(run, player.getId(), sourceGame);
        }
    }

    @Test
    void 점프와_방향_입력은_각_게임의_검증기로_전달되고_다른_형식은_거부된다() {
        User player = player("입력 형식");
        MinigameRun runner = readyRun(player, RUNNER);
        assertError(() -> commandService.finish(player.getId(), RUNNER, runner.getId(), actions(188)),
                MinigameErrorCode.INVALID_REPLAY);
        assertError(() -> commandService.finish(player.getId(), RUNNER, runner.getId(),
                        new MinigameFinishRequest(188, List.of(), List.of())), MinigameErrorCode.INVALID_REPLAY);
        assertUnfinishedWithoutBest(runner, player.getId(), RUNNER);

        for (String game : List.of(STAIRS, MERGE)) {
            MinigameRun run = readyRun(player, game);
            assertError(() -> commandService.finish(player.getId(), game, run.getId(), new MinigameFinishRequest(180, List.of())),
                    MinigameErrorCode.INVALID_REPLAY);
            assertError(() -> commandService.finish(player.getId(), game, run.getId(),
                            new MinigameFinishRequest(180, List.of(), List.of())), MinigameErrorCode.INVALID_REPLAY);
            assertError(() -> commandService.finish(player.getId(), game, run.getId(),
                            new MinigameFinishRequest(180, null, null)), MinigameErrorCode.INVALID_REPLAY);
            assertUnfinishedWithoutBest(run, player.getId(), game);
        }

        assertThat(commandService.finish(player.getId(), RUNNER, runner.getId(), RUNNER_FINISH).score()).isEqualTo(31);
        assertThat(commandService.finish(player.getId(), STAIRS, readyRun(player, STAIRS).getId(), STAIRS_FINISH).score())
                .isEqualTo(2);
        assertThat(commandService.finish(player.getId(), MERGE, readyRun(player, MERGE).getId(), MERGE_FINISH).score())
                .isEqualTo(8);
    }

    @Test
    void 계단은_입력없는_시간초과와_첫_오답과_정답_후_시간초과를_재생한다() {
        User player = player("계단 종료");
        var timeout = commandService.finish(player.getId(), STAIRS, readyRun(player, STAIRS).getId(), actions(180));
        var wrong = commandService.finish(player.getId(), STAIRS, readyRun(player, STAIRS).getId(),
                actions(1, new MinigameAction(1, MinigameDirection.RIGHT)));
        var correct = commandService.finish(player.getId(), STAIRS, readyRun(player, STAIRS).getId(), STAIRS_FINISH);

        assertThat(timeout.score()).isZero();
        assertThat(timeout.personalBest()).isTrue();
        assertThat(wrong.score()).isZero();
        assertThat(wrong.personalBest()).isFalse();
        assertThat(correct.score()).isEqualTo(2);
        assertThat(correct.bestScore()).isEqualTo(2);
        assertThat(correct.personalBest()).isTrue();
        assertThat(queryService.leaderboard(player.getId(), STAIRS).myEntry().score()).isEqualTo(2);
    }

    @Test
    void 합치기는_빈_기록_수동종료와_타일_합산_점수를_받고_입력_재전송을_멱등_처리한다() {
        User player = player("합치기 종료");
        var empty = commandService.finish(player.getId(), MERGE, readyRun(player, MERGE).getId(), actions(1));
        assertThat(empty.score()).isZero();
        assertThat(empty.personalBest()).isTrue();
        MinigameRun run = readyRun(player, MERGE);
        var merged = commandService.finish(player.getId(), MERGE, run.getId(), MERGE_FINISH);
        assertThat(merged.score()).isEqualTo(8);
        assertThat(merged.bestScore()).isEqualTo(8);
        assertThat(merged.personalBest()).isTrue();
        now = run.getExpiresAt().plusSeconds(1);

        MinigameFinishRequest copied = actions(30,
                new MinigameAction(10, MinigameDirection.UP), new MinigameAction(20, MinigameDirection.UP),
                new MinigameAction(30, MinigameDirection.UP));
        assertThat(commandService.finish(player.getId(), MERGE, run.getId(), copied)).isEqualTo(merged);
        assertError(() -> commandService.finish(player.getId(), MERGE, run.getId(), actions(30,
                        new MinigameAction(10, MinigameDirection.UP), new MinigameAction(20, MinigameDirection.UP),
                        new MinigameAction(30, MinigameDirection.DOWN))), MinigameErrorCode.RUN_ALREADY_FINISHED);
        assertThat(queryService.leaderboard(player.getId(), MERGE).myEntry().score()).isEqualTo(8);
    }

    @Test
    void 동일_사용자의_세_게임_최고점과_랭킹은_서로_영향을_주지_않는다() {
        User player = player("게임별 기록");
        User competitor = player("게임별 경쟁자");
        seedBest(player, RUNNER, 9_000_000);
        seedBest(player, STAIRS, 8_000_000);
        seedBest(player, MERGE, 7_000_000);
        seedBest(competitor, RUNNER, 10_000_000);
        seedBest(competitor, STAIRS, 1);
        seedBest(competitor, MERGE, 8_000_000);

        var runner = commandService.finish(player.getId(), RUNNER, readyRun(player, RUNNER).getId(), RUNNER_FINISH);
        var stairs = commandService.finish(player.getId(), STAIRS, readyRun(player, STAIRS).getId(), STAIRS_FINISH);
        var merge = commandService.finish(player.getId(), MERGE, readyRun(player, MERGE).getId(), MERGE_FINISH);

        assertThat(runner.score()).isEqualTo(31);
        assertThat(runner.bestScore()).isEqualTo(9_000_000);
        assertThat(runner.rank()).isEqualTo(2);
        assertThat(stairs.score()).isEqualTo(2);
        assertThat(stairs.bestScore()).isEqualTo(8_000_000);
        assertThat(stairs.rank()).isEqualTo(1);
        assertThat(merge.score()).isEqualTo(8);
        assertThat(merge.bestScore()).isEqualTo(7_000_000);
        assertThat(merge.rank()).isEqualTo(2);
        assertThat(List.of(runner, stairs, merge)).allSatisfy(result -> assertThat(result.personalBest()).isFalse());
        assertThat(queryService.leaderboard(player.getId(), RUNNER).myEntry())
                .isEqualTo(new Entry(2, player.getId(), player.getNickname(), 9_000_000));
        assertThat(queryService.leaderboard(player.getId(), STAIRS).myEntry())
                .isEqualTo(new Entry(1, player.getId(), player.getNickname(), 8_000_000));
        assertThat(queryService.leaderboard(player.getId(), MERGE).myEntry())
                .isEqualTo(new Entry(2, player.getId(), player.getNickname(), 7_000_000));
        assertThat(jdbc.queryForObject("select count(*) from minigame_best_scores where user_id = ?", Long.class, player.getId()))
                .isEqualTo(3);
    }

    @Test
    void 방향_입력의_재전송은_만료_후에도_멱등이고_방향이_바뀌면_409다() {
        User player = player("방향 재전송");
        MinigameRun run = readyRun(player, STAIRS);
        var first = commandService.finish(player.getId(), STAIRS, run.getId(), STAIRS_FINISH);
        now = run.getExpiresAt().plusSeconds(1);
        MinigameFinishRequest copied = actions(187,
                new MinigameAction(1, MinigameDirection.LEFT), new MinigameAction(7, MinigameDirection.LEFT));

        assertThat(commandService.finish(player.getId(), STAIRS, run.getId(), copied)).isEqualTo(first);
        assertError(() -> commandService.finish(player.getId(), STAIRS, run.getId(), actions(187,
                        new MinigameAction(1, MinigameDirection.LEFT), new MinigameAction(7, MinigameDirection.RIGHT))),
                MinigameErrorCode.RUN_ALREADY_FINISHED);
        assertThat(commandService.finish(player.getId(), STAIRS, run.getId(), copied)).isEqualTo(first);
        assertThat(bestScoreRepository.findByUserIdAndGameCode(player.getId(), STAIRS).orElseThrow().getScore())
                .isEqualTo(2);
    }

    @Test
    void 새_게임에도_실제_진행시간_검증이_적용되며_시간이_지나면_정상_제출된다() {
        User player = player("공통 시간 검증");
        Instant startedAt = now;
        MinigameRun stairs = seededRun(player, STAIRS, startedAt);
        MinigameRun merge = seededRun(player, MERGE, startedAt);

        assertError(() -> commandService.finish(player.getId(), STAIRS, stairs.getId(), actions(180)),
                MinigameErrorCode.RUN_TOO_EARLY);
        assertError(() -> commandService.finish(player.getId(), MERGE, merge.getId(), actions(300)),
                MinigameErrorCode.RUN_TOO_EARLY);
        assertUnfinishedWithoutBest(stairs, player.getId(), STAIRS);
        assertUnfinishedWithoutBest(merge, player.getId(), MERGE);

        now = startedAt.plusSeconds(5);
        assertThat(commandService.finish(player.getId(), STAIRS, stairs.getId(), actions(180)).score()).isZero();
        assertThat(commandService.finish(player.getId(), MERGE, merge.getId(), actions(300)).score()).isZero();
    }

    @Test
    void 새_게임도_틀린_입력이나_종료되지_않은_기록에는_완료와_최고점을_저장하지_않는다() {
        User player = player("새 게임 검증");
        MinigameRun stairs = readyRun(player, STAIRS);
        MinigameRun merge = readyRun(player, MERGE);

        assertError(() -> commandService.finish(player.getId(), STAIRS, stairs.getId(), actions(179)),
                MinigameErrorCode.RUN_NOT_FINISHED);
        assertError(() -> commandService.finish(player.getId(), STAIRS, stairs.getId(), actions(7201)),
                MinigameErrorCode.INVALID_REPLAY);
        assertError(() -> commandService.finish(player.getId(), STAIRS, stairs.getId(),
                        actions(1, new MinigameAction(1, MinigameDirection.UP))), MinigameErrorCode.INVALID_REPLAY);
        assertError(() -> commandService.finish(player.getId(), MERGE, merge.getId(), actions(10,
                        new MinigameAction(2, MinigameDirection.LEFT), new MinigameAction(1, MinigameDirection.UP))),
                MinigameErrorCode.INVALID_REPLAY);
        assertError(() -> commandService.finish(player.getId(), MERGE, merge.getId(),
                        actions(1, new MinigameAction(2, MinigameDirection.LEFT))), MinigameErrorCode.INVALID_REPLAY);
        // 첫 세 입력은 8점을 만들지만 네 번째 UP은 보드 변화가 없어 전체 기록이 거부되어야 함.
        assertError(() -> commandService.finish(player.getId(), MERGE, merge.getId(), actions(40,
                        new MinigameAction(10, MinigameDirection.UP), new MinigameAction(20, MinigameDirection.UP),
                        new MinigameAction(30, MinigameDirection.UP), new MinigameAction(40, MinigameDirection.UP))),
                MinigameErrorCode.INVALID_REPLAY);

        assertUnfinishedWithoutBest(stairs, player.getId(), STAIRS);
        assertUnfinishedWithoutBest(merge, player.getId(), MERGE);
        assertThat(commandService.finish(player.getId(), STAIRS, stairs.getId(), STAIRS_FINISH).score()).isEqualTo(2);
        assertThat(commandService.finish(player.getId(), MERGE, merge.getId(), MERGE_FINISH).score()).isEqualTo(8);
    }

    private User player(String nickname) {
        User user = User.signUp();
        user.changeNickname(nickname);
        User saved = userRepository.saveAndFlush(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private MinigameRun readyRun(User user, String game) {
        return seededRun(user, game, now.minusSeconds(100));
    }

    private MinigameRun seededRun(User user, String game, Instant startedAt) {
        return runRepository.saveAndFlush(MinigameRun.start(UUID.randomUUID().toString(), user,
                game, 1, 1, startedAt, startedAt.plusSeconds(1800)));
    }

    private void seedBest(User player, String game, int score) {
        bestScoreRepository.saveAndFlush(MinigameBestScore.create(player, game, 1, score, now.minusSeconds(3600)));
    }

    private void assertUnfinishedWithoutBest(MinigameRun run, Long userId, String game) {
        MinigameRun saved = runRepository.findById(run.getId()).orElseThrow();
        assertThat(saved.isFinished()).isFalse();
        assertThat(saved.getScore()).isNull();
        assertThat(saved.getSubmissionHash()).isNull();
        assertThat(saved.getFinishedBestScore()).isNull();
        assertThat(bestScoreRepository.findByUserIdAndGameCode(userId, game)).isEmpty();
    }

    private static MinigameFinishRequest actions(int ticks, MinigameAction... actions) {
        return new MinigameFinishRequest(ticks, null, List.of(actions));
    }

    private static void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
