package com.triples.rougether.domain.minigame.repository;

import com.triples.rougether.domain.minigame.entity.MinigameRun;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MinigameRunRepository extends JpaRepository<MinigameRun, String> {

    Optional<MinigameRun> findByIdAndUserIdAndGameCode(String id, Long userId, String gameCode);

    Optional<MinigameRun> findByIdAndUserId(String id, Long userId);

    // 지금까지 이 게임을 끝낸 판 수. 완료 처리는 회원 락 안에서 직렬화되므로 새 판의 순번 계산에 씀
    long countByUserIdAndGameCodeAndFinishedAtIsNotNull(Long userId, String gameCode);

    // play_count 가 없는(V89 이전에 끝난) 판의 재요청 영수증용. 기준 판 완료 시각까지 끝낸 판 수
    long countByUserIdAndGameCodeAndFinishedAtLessThanEqual(Long userId, String gameCode, Instant finishedAt);

    @Modifying(flushAutomatically = true)
    @Query("delete from MinigameRun run where run.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
