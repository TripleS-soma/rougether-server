package com.triples.rougether.domain.notification.repository;

import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserDeviceTokenRepository extends JpaRepository<UserDeviceToken, Long> {

    Optional<UserDeviceToken> findByToken(String token);

    // FCM 발송 대상 조회(멀티디바이스 전체).
    List<UserDeviceToken> findAllByUserId(Long userId);

    // FCM UNREGISTERED/INVALID_ARGUMENT 응답 token 정리.
    void deleteAllByTokenIn(List<String> tokens);
}
