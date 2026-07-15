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

    // FCM UNREGISTERED/INVALID_ARGUMENT 응답 token 정리. 발송 시점 소유자 조건으로 스코프함 —
    // 비동기 발송~응답 사이에 upsert가 같은 token의 소유권을 이전했으면 새 소유자의 유효 토큰이므로 지우면 안 됨.
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM UserDeviceToken dt WHERE dt.token IN :tokens AND dt.user.id = :userId")
    void deleteAllByTokenInAndUserId(@Param("tokens") List<String> tokens, @Param("userId") Long userId);

    // 소유권 조건을 삭제문 자체에 넣어 단일 DELETE로 실행함. 파생 쿼리(deleteBy...)는 select 후
    // PK delete라, 조회~삭제 사이에 upsert가 소유권을 이전하면 새 소유자의 행을 지울 수 있음.
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM UserDeviceToken dt WHERE dt.token = :token AND dt.user.id = :userId")
    int deleteByTokenAndUserId(@Param("token") String token, @Param("userId") Long userId);

    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT INTO user_device_token (user_id, token, platform, created_at, updated_at) "
            + "VALUES (:userId, :token, :platform, :now, :now) "
            + "ON DUPLICATE KEY UPDATE user_id = :userId, platform = :platform, updated_at = :now",
            nativeQuery = true)
    void upsert(@Param("userId") Long userId, @Param("token") String token,
                @Param("platform") String platform, @Param("now") Instant now);
}
