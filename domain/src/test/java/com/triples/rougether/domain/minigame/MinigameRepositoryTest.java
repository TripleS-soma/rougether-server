package com.triples.rougether.domain.minigame;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.minigame.entity.MinigameBestScore;
import com.triples.rougether.domain.minigame.entity.MinigameRun;
import com.triples.rougether.domain.minigame.repository.MinigameBestScoreRepository;
import com.triples.rougether.domain.minigame.repository.MinigameRunRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@ContextConfiguration(classes = MinigameRepositoryTest.JpaTestConfiguration.class)
class MinigameRepositoryTest {

    private static final Instant ACHIEVED_AT = Instant.parse("2026-09-12T00:00:00Z");
    private static final String GAME_CODE = "room-runner";

    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private MinigameBestScoreRepository bestScores;
    @Autowired
    private MinigameRunRepository runs;

    @Test
    void 랭킹은_활성_일반_사용자의_게임별_기록을_점수와_최초달성_순으로_정렬한다() {
        User earlierId = user();
        User laterId = user();
        User earlierAchievement = user();
        User lowerScore = user();
        User withdrawn = user();
        withdrawn.softDelete(ACHIEVED_AT);
        User bot = entityManager.persist(User.bot("minigame-test-bot", "봇", "봇"));
        score(earlierId, GAME_CODE, 100, ACHIEVED_AT.plusSeconds(1));
        score(laterId, GAME_CODE, 100, ACHIEVED_AT.plusSeconds(1));
        score(earlierAchievement, GAME_CODE, 100, ACHIEVED_AT);
        score(lowerScore, GAME_CODE, 90, ACHIEVED_AT);
        score(withdrawn, GAME_CODE, 999, ACHIEVED_AT);
        score(bot, GAME_CODE, 999, ACHIEVED_AT);
        score(earlierId, "tile-merge", 999, ACHIEVED_AT);
        entityManager.flush();
        entityManager.clear();

        List<MinigameBestScore> leaderboard = bestScores.findLeaderboard(GAME_CODE, PageRequest.of(0, 10));

        assertThat(leaderboard).extracting(best -> best.getUser().getId())
                .containsExactly(earlierAchievement.getId(), earlierId.getId(), laterId.getId(), lowerScore.getId());
        assertThat(bestScores.findLeaderboard(GAME_CODE, PageRequest.of(0, 2)))
                .extracting(best -> best.getUser().getId())
                .containsExactly(earlierAchievement.getId(), earlierId.getId());
        assertThat(bestScores.countPlayers(GAME_CODE)).isEqualTo(4);
        assertThat(bestScores.countHigherScores(GAME_CODE, 100)).isZero();
        assertThat(bestScores.countHigherScores(GAME_CODE, 90)).isEqualTo(3);
        assertThat(bestScores.countPlayers("missing-game")).isZero();
    }

    @Test
    void 완료_기록은_인증_사용자와_게임이_모두_일치할_때만_조회한다() {
        User owner = user();
        User other = user();
        MinigameRun run = run("owned-run", owner, GAME_CODE);
        run.finish(600, 100, "b".repeat(64), 100, true, 1, ACHIEVED_AT.plusSeconds(10));
        entityManager.flush();
        entityManager.clear();

        assertThat(runs.findByIdAndUserIdAndGameCode("owned-run", owner.getId(), GAME_CODE))
                .get().satisfies(saved -> {
                    assertThat(saved.isFinished()).isTrue();
                    assertThat(saved.getFinishedBestScore()).isEqualTo(100);
                    assertThat(saved.getSubmissionHash()).isEqualTo("b".repeat(64));
                });
        assertThat(runs.findByIdAndUserIdAndGameCode("owned-run", other.getId(), GAME_CODE)).isEmpty();
        assertThat(runs.findByIdAndUserIdAndGameCode("owned-run", owner.getId(), "tile-merge")).isEmpty();
    }

    @Test
    void 최고_점수는_더_큰_기록에서만_점수와_달성시각을_갱신한다() {
        User owner = user();
        MinigameBestScore best = score(owner, GAME_CODE, 100, ACHIEVED_AT);

        best.improve(90, 2, ACHIEVED_AT.plusSeconds(10));
        best.improve(100, 2, ACHIEVED_AT.plusSeconds(20));
        entityManager.flush();
        entityManager.clear();
        best = bestScores.findByUserIdAndGameCode(owner.getId(), GAME_CODE).orElseThrow();
        assertThat(best.getScore()).isEqualTo(100);
        assertThat(best.getRulesVersion()).isEqualTo(1);
        assertThat(best.getAchievedAt()).isEqualTo(ACHIEVED_AT);

        best.improve(101, 2, ACHIEVED_AT.plusSeconds(30));
        entityManager.flush();
        entityManager.clear();

        assertThat(bestScores.findByUserIdAndGameCode(owner.getId(), GAME_CODE)).get().satisfies(saved -> {
            assertThat(saved.getScore()).isEqualTo(101);
            assertThat(saved.getRulesVersion()).isEqualTo(2);
            assertThat(saved.getAchievedAt()).isEqualTo(ACHIEVED_AT.plusSeconds(30));
        });
    }

    @Test
    void 탈퇴_삭제는_해당_사용자의_모든_게임만_삭제하고_회원_변경_추적을_유지한다() {
        User owner = user();
        User other = user();
        run("owner-run", owner, GAME_CODE);
        run("owner-second-game", owner, "tile-merge");
        run("other-run", other, GAME_CODE);
        score(owner, GAME_CODE, 100, ACHIEVED_AT);
        score(owner, "tile-merge", 200, ACHIEVED_AT);
        score(other, GAME_CODE, 300, ACHIEVED_AT);

        runs.deleteAllByUserId(owner.getId());
        bestScores.deleteAllByUserId(owner.getId());
        owner.softDelete(ACHIEVED_AT);
        entityManager.flush();
        entityManager.clear();

        assertThat(runs.findAll()).extracting(MinigameRun::getId).containsExactly("other-run");
        assertThat(bestScores.findAll()).extracting(best -> best.getUser().getId()).containsExactly(other.getId());
        assertThat(entityManager.find(User.class, owner.getId()).isDeleted()).isTrue();
    }

    private User user() {
        return entityManager.persist(User.signUp());
    }

    private MinigameRun run(String id, User user, String gameCode) {
        return entityManager.persist(MinigameRun.start(id, user, gameCode, 1, 123,
                ACHIEVED_AT, ACHIEVED_AT.plusSeconds(1800)));
    }

    private MinigameBestScore score(User user, String gameCode, int score, Instant achievedAt) {
        return entityManager.persist(MinigameBestScore.create(user, gameCode, 1, score, achievedAt));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EntityScan(basePackages = "com.triples.rougether.domain")
    @EnableJpaRepositories(basePackageClasses = MinigameBestScoreRepository.class)
    static class JpaTestConfiguration {
    }
}
