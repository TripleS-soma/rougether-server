package com.triples.rougether.domain.notification.repository;

import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserDeviceTokenRepository extends JpaRepository<UserDeviceToken, Long> {

    Optional<UserDeviceToken> findByToken(String token);

    // FCM 발송 대상 조회(멀티디바이스 전체).
    List<UserDeviceToken> findAllByUserId(Long userId);

    // FCM UNREGISTERED/INVALID_ARGUMENT 응답 token 정리.
    void deleteAllByTokenIn(List<String> tokens);

    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT INTO user_device_token (user_id, token, platform, created_at, updated_at) "
            + "VALUES (:userId, :token, :platform, :now, :now) "
            + "ON DUPLICATE KEY UPDATE user_id = :userId, platform = :platform, updated_at = :now",
            nativeQuery = true)
    void upsert(@Param("userId") Long userId, @Param("token") String token,
                @Param("platform") String platform, @Param("now") Instant now);
}
