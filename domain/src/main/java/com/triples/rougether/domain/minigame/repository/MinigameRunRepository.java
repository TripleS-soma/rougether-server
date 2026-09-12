package com.triples.rougether.domain.minigame.repository;

import com.triples.rougether.domain.minigame.entity.MinigameRun;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MinigameRunRepository extends JpaRepository<MinigameRun, String> {

    Optional<MinigameRun> findByIdAndUserIdAndGameCode(String id, Long userId, String gameCode);

    @Modifying(flushAutomatically = true)
    @Query("delete from MinigameRun run where run.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
