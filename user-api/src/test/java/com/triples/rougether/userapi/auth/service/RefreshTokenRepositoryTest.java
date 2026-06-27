package com.triples.rougether.userapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.RefreshToken;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.triples.rougether.userapi.global.config.JpaConfig;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

// 커스텀 쿼리(findByTokenHash, findAllByUserIdAndRevokedAtIsNull) 만 검증함. Flyway 스키마 + JPA 감사 설정 사용.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class RefreshTokenRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void findByTokenHash_는_해시로_토큰을_찾는다() {
        User user = userRepository.save(User.signUp());
        refreshTokenRepository.save(RefreshToken.issue(user, "hash-a", future()));

        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHash("hash-a");

        assertThat(found).isPresent();
        assertThat(found.get().getUser().getId()).isEqualTo(user.getId());
        assertThat(refreshTokenRepository.findByTokenHash("hash-none")).isEmpty();
    }

    @Test
    void findAllByUserIdAndRevokedAtIsNull_은_해당_user_의_살아있는_토큰만_준다() {
        User user = userRepository.save(User.signUp());
        User other = userRepository.save(User.signUp());

        refreshTokenRepository.save(RefreshToken.issue(user, "active-1", future()));
        RefreshToken revoked = RefreshToken.issue(user, "revoked-1", future());
        revoked.revoke(Instant.now());
        refreshTokenRepository.save(revoked);
        refreshTokenRepository.save(RefreshToken.issue(other, "other-active", future()));

        List<RefreshToken> active = refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId());

        assertThat(active).extracting(RefreshToken::getTokenHash).containsExactly("active-1");
    }

    private Instant future() {
        return Instant.now().plus(14, ChronoUnit.DAYS);
    }
}
