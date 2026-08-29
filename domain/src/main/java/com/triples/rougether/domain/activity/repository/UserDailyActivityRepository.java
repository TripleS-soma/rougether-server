package com.triples.rougether.domain.activity.repository;

import com.triples.rougether.domain.activity.entity.UserDailyActivity;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserDailyActivityRepository extends JpaRepository<UserDailyActivity, Long> {

    // 유효 JWT가 있더라도 탈퇴 사용자·봇이면 기록하지 않음. INSERT-SELECT와 UNIQUE upsert를 한 문장으로
    // 처리해 사용자 상태 확인 SELECT를 없애고 다중 인스턴스의 동시 최초 요청도 멱등하게 수렴시킴.
    @Modifying
    @Query(value = """
            INSERT INTO user_daily_activity (user_id, activity_date, created_at)
            SELECT u.id, :activityDate, CURRENT_TIMESTAMP
            FROM users u
            WHERE u.id = :userId
              AND u.deleted_at IS NULL
              AND u.is_bot = FALSE
            ON DUPLICATE KEY UPDATE activity_date = VALUES(activity_date)
            """, nativeQuery = true)
    int insertIfActiveUser(@Param("userId") Long userId,
                           @Param("activityDate") LocalDate activityDate);

    long countByUserIdAndActivityDate(Long userId, LocalDate activityDate);
}
