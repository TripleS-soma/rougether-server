package com.triples.rougether.userapi.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.DevicePlatform;
import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.userapi.global.config.JpaConfig;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

// migration 위험영역: Flyway로 적용된 token UNIQUE 제약이 실제로 걸리는지 검증함.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class UserDeviceTokenRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserDeviceTokenRepository userDeviceTokenRepository;

    @Test
    void findByToken_은_token으로_디바이스토큰을_찾는다() {
        User user = userRepository.save(User.signUp());
        userDeviceTokenRepository.save(UserDeviceToken.register(user, "token-a", DevicePlatform.IOS, Instant.now()));

        Optional<UserDeviceToken> found = userDeviceTokenRepository.findByToken("token-a");

        assertThat(found).isPresent();
        assertThat(found.get().getUser().getId()).isEqualTo(user.getId());
        assertThat(userDeviceTokenRepository.findByToken("token-none")).isEmpty();
    }

    @Test
    void token은_UNIQUE_제약으로_중복_저장을_거부한다() {
        User user = userRepository.save(User.signUp());
        User other = userRepository.save(User.signUp());
        userDeviceTokenRepository.saveAndFlush(
                UserDeviceToken.register(user, "dup-token", DevicePlatform.IOS, Instant.now()));

        assertThatThrownBy(() -> userDeviceTokenRepository.saveAndFlush(
                UserDeviceToken.register(other, "dup-token", DevicePlatform.ANDROID, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
