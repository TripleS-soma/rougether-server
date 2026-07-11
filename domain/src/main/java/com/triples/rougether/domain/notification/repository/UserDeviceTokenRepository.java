package com.triples.rougether.domain.notification.repository;

import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserDeviceTokenRepository extends JpaRepository<UserDeviceToken, Long> {

    Optional<UserDeviceToken> findByToken(String token);
}
