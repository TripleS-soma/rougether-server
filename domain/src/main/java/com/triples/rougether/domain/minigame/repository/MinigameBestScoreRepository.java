package com.triples.rougether.domain.minigame.repository;

import com.triples.rougether.domain.minigame.entity.MinigameBestScore;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MinigameBestScoreRepository extends JpaRepository<MinigameBestScore, Long> {

    Optional<MinigameBestScore> findByUserIdAndGameCode(Long userId, String gameCode);

    @Query("""
            select best from MinigameBestScore best
            join fetch best.user u
            where best.gameCode = :gameCode and u.deletedAt is null and u.bot = false
            order by best.score desc, best.achievedAt asc, u.id asc
            """)
    List<MinigameBestScore> findLeaderboard(@Param("gameCode") String gameCode, Pageable pageable);

    @Query("""
            select count(best) from MinigameBestScore best
            join best.user u
            where best.gameCode = :gameCode and u.deletedAt is null and u.bot = false
            """)
    long countPlayers(@Param("gameCode") String gameCode);

    @Query("""
            select count(best) from MinigameBestScore best
            join best.user u
            where best.gameCode = :gameCode and u.deletedAt is null and u.bot = false
              and best.score > :score
            """)
    long countHigherScores(@Param("gameCode") String gameCode, @Param("score") int score);

    @Modifying(flushAutomatically = true)
    @Query("delete from MinigameBestScore best where best.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
