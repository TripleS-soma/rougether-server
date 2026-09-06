package com.triples.rougether.domain.appicon.repository;

import com.triples.rougether.domain.appicon.entity.UserAppActivity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAppActivityRepository extends JpaRepository<UserAppActivity, Long> {

    // 처리 중 후보가 줄어도 누락되지 않게 사용자 id 커서로 조회함.
    @Query("""
            select a.userId from UserAppActivity a
            where a.userId > :afterUserId and a.user.deletedAt is null and a.user.bot = false
              and ((a.lastForegroundAt <= :twoDaysAgo and a.lastNotifiedStage < 1)
                or (a.lastForegroundAt <= :fourDaysAgo and a.lastNotifiedStage < 2)
                or (a.lastForegroundAt <= :sevenDaysAgo and a.lastNotifiedStage < 3))
            order by a.userId
            """)
    List<Long> findReminderCandidates(@Param("afterUserId") long afterUserId,
                                      @Param("twoDaysAgo") Instant twoDaysAgo,
                                      @Param("fourDaysAgo") Instant fourDaysAgo,
                                      @Param("sevenDaysAgo") Instant sevenDaysAgo,
                                      Pageable pageable);

    @Modifying(flushAutomatically = true)
    @Query("delete from UserAppActivity a where a.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
