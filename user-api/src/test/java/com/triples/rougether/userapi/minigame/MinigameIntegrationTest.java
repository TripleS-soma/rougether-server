package com.triples.rougether.userapi.minigame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.AuthErrorCode;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.common.error.ErrorCode;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.minigame.entity.MinigameBestScore;
import com.triples.rougether.domain.minigame.entity.MinigameRun;
import com.triples.rougether.domain.minigame.repository.MinigameBestScoreRepository;
import com.triples.rougether.domain.minigame.repository.MinigameRunRepository;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishRequest;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishResponse;
import com.triples.rougether.userapi.minigame.dto.MinigameLeaderboardResponse.Entry;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

// 기본 테스트 설정의 MySQL 컨테이너에서 사용자 행 락과 최초 최고점 UNIQUE 경쟁을 검증함.
// 테스트 트랜잭션을 두지 않아 각 서비스 호출과 동시 요청이 실제로 커밋됨.
@SpringBootTest
class MinigameIntegrationTest {
    private static final String GAME = MinigameCatalog.RUNNER;
    private static final MinigameFinishRequest NO_JUMP = new MinigameFinishRequest(188, List.of());
    private static final MinigameFinishRequest ONE_JUMP = new MinigameFinishRequest(301, List.of(180));

    @Autowired private MinigameCommandService commandService;
    @Autowired private MinigameQueryService queryService;
    @Autowired private MinigameRunRepository runRepository;
    @Autowired private MinigameBestScoreRepository bestScoreRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @MockitoBean private Clock kstClock;

    private final List<Long> createdUserIds = new ArrayList<>();
    private volatile Instant now;

    @BeforeEach
    void setClock() {
        now = Instant.parse("2026-09-12T03:00:00Z");
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
    void 입장하면_서버가_소유자와_시드와_30분_만료시간을_저장한다() {
        User player = player("러너");

        var started = commandService.start(player.getId(), GAME);

        assertThat(started.runId()).isEqualTo(UUID.fromString(started.runId()).toString());
        assertThat(started.gameCode()).isEqualTo(GAME);
        assertThat(started.rulesVersion()).isEqualTo(1);
        assertThat(started.seed()).isPositive();
        assertThat(started.maxTicks()).isEqualTo(18_000);
        assertThat(started.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));
        MinigameRun saved = runRepository.findById(started.runId()).orElseThrow();
        assertThat(saved.getUser().getId()).isEqualTo(player.getId());
        assertThat(saved.getGameCode()).isEqualTo(GAME);
        assertThat(saved.getRulesVersion()).isEqualTo(started.rulesVersion());
        assertThat(saved.getSeed()).isEqualTo(started.seed());
        assertThat(saved.getStartedAt()).isEqualTo(now);
        assertThat(saved.getExpiresAt()).isEqualTo(started.expiresAt());
        assertThat(saved.isFinished()).isFalse();
        assertThat(saved.getScore()).isNull();
    }

    @Test
    void 알_수_없는_게임은_입장과_종료와_랭킹에서_404로_거부한다() {
        User player = player("러너");

        assertError(() -> commandService.start(player.getId(), "unknown"), MinigameErrorCode.GAME_NOT_FOUND);
        assertError(() -> commandService.finish(player.getId(), "unknown", UUID.randomUUID().toString(), NO_JUMP),
                MinigameErrorCode.GAME_NOT_FOUND);
        assertError(() -> queryService.leaderboard(player.getId(), "unknown"), MinigameErrorCode.GAME_NOT_FOUND);
        assertThat(MinigameErrorCode.GAME_NOT_FOUND.status()).isEqualTo(404);
    }

    @Test
    void 다른_사용자의_세션으로는_점수를_제출할_수_없다() {
        User owner = player("소유자");
        User stranger = player("다른 사람");
        MinigameRun run = readyRun(owner);

        assertError(() -> commandService.finish(stranger.getId(), GAME, run.getId(), NO_JUMP),
                MinigameErrorCode.RUN_NOT_FOUND);

        assertThat(MinigameErrorCode.RUN_NOT_FOUND.status()).isEqualTo(404);
        assertUnfinished(run);
        assertThat(bestScoreRepository.findByUserIdAndGameCode(stranger.getId(), GAME)).isEmpty();
        assertThat(bestScoreRepository.findByUserIdAndGameCode(owner.getId(), GAME)).isEmpty();
    }

    @Test
    void 탈퇴자와_봇은_입장과_종료와_랭킹을_사용할_수_없다() {
        User deleted = player("탈퇴자");
        User bot = persist(User.bot("mg-" + UUID.randomUUID(), "봇", null));
        MinigameRun deletedRun = readyRun(deleted);
        MinigameRun botRun = readyRun(bot);
        deleted.softDelete(now);
        userRepository.saveAndFlush(deleted);

        for (User rejected : List.of(deleted, bot)) {
            assertError(() -> commandService.start(rejected.getId(), GAME), AuthErrorCode.USER_NOT_FOUND);
            assertError(() -> queryService.leaderboard(rejected.getId(), GAME), AuthErrorCode.USER_NOT_FOUND);
        }
        assertError(() -> commandService.finish(deleted.getId(), GAME, deletedRun.getId(), NO_JUMP),
                AuthErrorCode.USER_NOT_FOUND);
        assertError(() -> commandService.finish(bot.getId(), GAME, botRun.getId(), NO_JUMP),
                AuthErrorCode.USER_NOT_FOUND);
        assertUnfinished(deletedRun);
        assertUnfinished(botRun);
    }

    @Test
    void 만료시각에_도달한_미완료_세션은_410이고_점수를_남기지_않는다() {
        User player = player("만료");
        MinigameRun run = readyRun(player);
        now = run.getExpiresAt();

        assertError(() -> commandService.finish(player.getId(), GAME, run.getId(), NO_JUMP),
                MinigameErrorCode.RUN_EXPIRED);

        assertThat(MinigameErrorCode.RUN_EXPIRED.status()).isEqualTo(410);
        assertUnfinished(run);
        assertThat(bestScoreRepository.findByUserIdAndGameCode(player.getId(), GAME)).isEmpty();
    }

    @Test
    void 실제_진행시간보다_빠른_제출은_거부하고_나중에_정상_제출할_수_있다() {
        User player = player("시간 검증");
        MinigameRun run = seededRun(player, now, now.plusSeconds(1800));

        assertError(() -> commandService.finish(player.getId(), GAME, run.getId(), NO_JUMP),
                MinigameErrorCode.RUN_TOO_EARLY);
        assertUnfinished(run);
        assertThat(bestScoreRepository.findByUserIdAndGameCode(player.getId(), GAME)).isEmpty();

        now = now.plusSeconds(4);
        assertThat(commandService.finish(player.getId(), GAME, run.getId(), NO_JUMP).score()).isEqualTo(31);
    }

    @Test
    void 종료_전이나_충돌_후까지_늘린_리플레이는_완료기록과_최고점을_남기지_않는다() {
        User player = player("리플레이 검증");
        MinigameRun run = readyRun(player);

        assertError(() -> commandService.finish(player.getId(), GAME, run.getId(),
                        new MinigameFinishRequest(187, List.of())), MinigameErrorCode.RUN_NOT_FINISHED);
        assertError(() -> commandService.finish(player.getId(), GAME, run.getId(),
                        new MinigameFinishRequest(189, List.of())), MinigameErrorCode.INVALID_REPLAY);
        assertUnfinished(run);
        assertThat(bestScoreRepository.findByUserIdAndGameCode(player.getId(), GAME)).isEmpty();

        MinigameFinishResponse accepted = commandService.finish(player.getId(), GAME, run.getId(), NO_JUMP);
        assertThat(accepted.score()).isEqualTo(31);
        assertThat(accepted.bestScore()).isEqualTo(31);
        assertThat(accepted.personalBest()).isTrue();
    }

    @Test
    void 완료_재전송은_최고점과_순위가_변하고_만료되어도_최초_응답을_반환한다() {
        User player = player("재전송");
        MinigameRun run = readyRun(player);
        MinigameFinishResponse first = commandService.finish(player.getId(), GAME, run.getId(), NO_JUMP);
        MinigameBestScore best = bestScoreRepository.findByUserIdAndGameCode(player.getId(), GAME).orElseThrow();
        best.improve(1000, 1, now.plusSeconds(1));
        bestScoreRepository.saveAndFlush(best);
        seedBest(player("경쟁자"), 2000, now);
        now = run.getExpiresAt().plusSeconds(1);

        MinigameFinishResponse retry = commandService.finish(player.getId(), GAME, run.getId(), NO_JUMP);

        assertThat(retry).isEqualTo(first);
        assertThat(retry.bestScore()).isEqualTo(31);
        assertThat(retry.personalBest()).isTrue();
        assertThat(bestScoreRepository.findByUserIdAndGameCode(player.getId(), GAME).orElseThrow().getScore())
                .isEqualTo(1000);
    }

    @Test
    void 완료된_세션을_다른_내용으로_제출하면_409이고_최초_기록을_유지한다() {
        User player = player("중복 충돌");
        MinigameRun run = readyRun(player);
        MinigameFinishResponse first = commandService.finish(player.getId(), GAME, run.getId(), NO_JUMP);

        assertError(() -> commandService.finish(player.getId(), GAME, run.getId(),
                        new MinigameFinishRequest(187, List.of())), MinigameErrorCode.RUN_ALREADY_FINISHED);

        assertThat(MinigameErrorCode.RUN_ALREADY_FINISHED.status()).isEqualTo(409);
        assertThat(commandService.finish(player.getId(), GAME, run.getId(), NO_JUMP)).isEqualTo(first);
    }

    @Test
    void 낮거나_같은_점수는_기존_최고점과_최초_달성시각을_덮어쓰지_않는다() {
        for (int initialScore : List.of(31, 1000)) {
            User player = player("최고점 보존" + initialScore);
            Instant achievedAt = now.minusSeconds(3600);
            MinigameBestScore initial = seedBest(player, initialScore, achievedAt);

            MinigameFinishResponse result = commandService.finish(player.getId(), GAME, readyRun(player).getId(), NO_JUMP);

            assertThat(result.score()).isEqualTo(31);
            assertThat(result.bestScore()).isEqualTo(initialScore);
            assertThat(result.personalBest()).isFalse();
            MinigameBestScore saved = bestScoreRepository.findByUserIdAndGameCode(player.getId(), GAME).orElseThrow();
            assertThat(saved.getId()).isEqualTo(initial.getId());
            assertThat(saved.getScore()).isEqualTo(initialScore);
            assertThat(saved.getAchievedAt()).isEqualTo(achievedAt);
        }
    }

    @Test
    void 전체_랭킹은_공동순위와_최신_닉네임을_반영하고_봇과_탈퇴자를_제외한다() {
        User viewer = player("관전자");
        long originalPlayers = bestScoreRepository.countPlayers(GAME);
        User first = player("먼저 달성");
        User tied = player("나중 달성");
        User third = player("3위");
        User bot = persist(User.bot("mg-" + UUID.randomUUID(), "봇", null));
        User deleted = player("탈퇴자");
        seedBest(first, 2_000_000, now.minusSeconds(2));
        seedBest(tied, 2_000_000, now.minusSeconds(1));
        seedBest(third, 1_999_999, now);
        seedBest(bot, 2_000_001, now);
        seedBest(deleted, 2_000_001, now);
        deleted.softDelete(now);
        userRepository.saveAndFlush(deleted);

        var leaderboard = queryService.leaderboard(viewer.getId(), GAME);

        assertThat(leaderboard.items().subList(0, 3)).extracting(Entry::userId)
                .containsExactly(first.getId(), tied.getId(), third.getId());
        assertThat(leaderboard.items().subList(0, 3)).extracting(Entry::rank).containsExactly(1L, 1L, 3L);
        assertThat(leaderboard.items()).extracting(Entry::userId).doesNotContain(bot.getId(), deleted.getId());
        assertThat(leaderboard.totalPlayers()).isEqualTo(originalPlayers + 3);
        assertThat(leaderboard.myEntry()).isNull();
        assertThat(queryService.leaderboard(third.getId(), GAME).myEntry().rank()).isEqualTo(3);

        first.changeNickname("변경된 닉네임");
        userRepository.saveAndFlush(first);
        var renamed = queryService.leaderboard(first.getId(), GAME);
        assertThat(renamed.items().getFirst().nickname()).isEqualTo("변경된 닉네임");
        assertThat(renamed.myEntry().nickname()).isEqualTo("변경된 닉네임");
    }

    @Test
    void 상위_50명_밖의_사용자도_자기_순위와_전체_참가자수를_받는다() {
        long originalPlayers = bestScoreRepository.countPlayers(GAME);
        User viewer = null;
        for (int i = 0; i < 51; i++) {
            User player = player("순위" + i);
            seedBest(player, 1_000_051 - i, now);
            viewer = player;
        }

        var leaderboard = queryService.leaderboard(viewer.getId(), GAME);

        assertThat(leaderboard.items()).hasSize(50);
        assertThat(leaderboard.items()).extracting(Entry::userId).doesNotContain(viewer.getId());
        assertThat(leaderboard.myEntry()).isEqualTo(new Entry(51, viewer.getId(), "순위50", 1_000_001));
        assertThat(leaderboard.totalPlayers()).isEqualTo(originalPlayers + 51);
    }

    @Test
    void 같은_세션을_동시에_제출해도_한_최고점과_동일한_완료응답만_남는다() throws Exception {
        User player = player("동시 재전송");
        MinigameRun run = readyRun(player);

        List<MinigameFinishResponse> results = concurrently(
                () -> commandService.finish(player.getId(), GAME, run.getId(), NO_JUMP),
                () -> commandService.finish(player.getId(), GAME, run.getId(), NO_JUMP));

        assertThat(results.getFirst()).isEqualTo(results.getLast());
        assertThat(results.getFirst().personalBest()).isTrue();
        assertThat(results.getFirst().score()).isEqualTo(31);
        assertThat(jdbc.queryForObject("select count(*) from minigame_best_scores where user_id = ? and game_code = ?",
                Long.class, player.getId(), GAME)).isEqualTo(1);
        assertThat(runRepository.findById(run.getId()).orElseThrow().isFinished()).isTrue();
    }

    @Test
    void 서로_다른_세션의_첫_최고점이_경쟁해도_중복_생성과_점수_역행이_없다() throws Exception {
        User player = player("동시 최고점");
        MinigameRun lowerRun = readyRun(player);
        MinigameRun higherRun = readyRun(player);

        List<MinigameFinishResponse> results = concurrently(
                () -> commandService.finish(player.getId(), GAME, lowerRun.getId(), NO_JUMP),
                () -> commandService.finish(player.getId(), GAME, higherRun.getId(), ONE_JUMP));

        assertThat(results).extracting(MinigameFinishResponse::score).containsExactly(31, 50);
        assertThat(results.getLast().personalBest()).isTrue();
        assertThat(results.getLast().bestScore()).isEqualTo(50);
        assertThat(bestScoreRepository.findByUserIdAndGameCode(player.getId(), GAME).orElseThrow().getScore())
                .isEqualTo(50);
        assertThat(jdbc.queryForObject("select count(*) from minigame_best_scores where user_id = ? and game_code = ?",
                Long.class, player.getId(), GAME)).isEqualTo(1);
        assertThat(runRepository.findById(lowerRun.getId()).orElseThrow().getScore()).isEqualTo(31);
        assertThat(runRepository.findById(higherRun.getId()).orElseThrow().getScore()).isEqualTo(50);
        assertThat(commandService.finish(player.getId(), GAME, lowerRun.getId(), NO_JUMP)).isEqualTo(results.getFirst());
        assertThat(queryService.leaderboard(player.getId(), GAME).myEntry().score()).isEqualTo(50);
    }

    @Test
    void 최고점_flush_후_상위_트랜잭션이_실패하면_완료와_최고점이_함께_롤백된다() {
        User player = player("종료 롤백");
        MinigameRun run = readyRun(player);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            assertThat(commandService.finish(player.getId(), GAME, run.getId(), NO_JUMP).score()).isEqualTo(31);
            throw new IllegalStateException("종료 이후 후속 작업 실패");
        })).isInstanceOf(IllegalStateException.class).hasMessage("종료 이후 후속 작업 실패");

        assertUnfinished(run);
        assertThat(bestScoreRepository.findByUserIdAndGameCode(player.getId(), GAME)).isEmpty();
        assertThat(commandService.finish(player.getId(), GAME, run.getId(), NO_JUMP).personalBest()).isTrue();
    }

    private User player(String nickname) {
        User user = User.signUp();
        user.changeNickname(nickname);
        return persist(user);
    }

    private User persist(User user) {
        User saved = userRepository.saveAndFlush(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private MinigameRun readyRun(User user) {
        return seededRun(user, now.minusSeconds(100), now.plusSeconds(1800));
    }

    private MinigameRun seededRun(User user, Instant startedAt, Instant expiresAt) {
        return runRepository.saveAndFlush(MinigameRun.start(UUID.randomUUID().toString(), user,
                GAME, 1, 1, startedAt, expiresAt));
    }

    private MinigameBestScore seedBest(User player, int score, Instant achievedAt) {
        return bestScoreRepository.saveAndFlush(MinigameBestScore.create(player, GAME, 1, score, achievedAt));
    }

    private void assertUnfinished(MinigameRun run) {
        MinigameRun saved = runRepository.findById(run.getId()).orElseThrow();
        assertThat(saved.isFinished()).isFalse();
        assertThat(saved.getTicks()).isNull();
        assertThat(saved.getScore()).isNull();
        assertThat(saved.getSubmissionHash()).isNull();
        assertThat(saved.getFinishedBestScore()).isNull();
        assertThat(saved.getPersonalBest()).isNull();
        assertThat(saved.getFinishedRank()).isNull();
    }

    private static void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static List<MinigameFinishResponse> concurrently(Callable<MinigameFinishResponse> first,
                                                             Callable<MinigameFinishResponse> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("동시 요청 시작을 기다리다 시간 초과");
                }
                return action.call();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.getFirst().get(20, TimeUnit.SECONDS), futures.getLast().get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
