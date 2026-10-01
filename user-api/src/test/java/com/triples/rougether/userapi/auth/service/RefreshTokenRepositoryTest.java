package com.triples.rougether.userapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.RefreshToken;
import com.triples.rougether.domain.member.entity.RefreshTokenRevokeReason;
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
import jakarta.persistence.EntityManager;

// 커스텀 쿼리(조회·잠금 조회·조건부 폐기) 만 검증함. Flyway 스키마 + JPA 감사 설정 사용.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class RefreshTokenRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private EntityManager entityManager;

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
        revoked.revoke(Instant.now(), RefreshTokenRevokeReason.LOGOUT);
        refreshTokenRepository.save(revoked);
        refreshTokenRepository.save(RefreshToken.issue(other, "other-active", future()));

        List<RefreshToken> active = refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId());

        assertThat(active).extracting(RefreshToken::getTokenHash).containsExactly("active-1");
    }

    @Test
    void findByTokenHashForUpdate_는_잠금_조회로_토큰을_찾는다() {
        User user = userRepository.save(User.signUp());
        refreshTokenRepository.save(RefreshToken.issue(user, "hash-lock", future()));

        assertThat(refreshTokenRepository.findByTokenHashForUpdate("hash-lock")).isPresent();
        assertThat(refreshTokenRepository.findByTokenHashForUpdate("hash-none")).isEmpty();
    }

    @Test
    void revokeIfActive_는_살아있을_때만_사유와_family_를_기록하며_폐기한다() {
        User user = userRepository.save(User.signUp());
        RefreshToken legacy = refreshTokenRepository.save(
                RefreshToken.issueInFamily(user, "legacy", future(), null));
        Instant now = Instant.now();

        assertThat(refreshTokenRepository.revokeIfActive(legacy.getId(), now,
                RefreshTokenRevokeReason.ROTATED, "fam-new")).isEqualTo(1);
        assertThat(refreshTokenRepository.revokeIfActive(legacy.getId(), now,
                RefreshTokenRevokeReason.ROTATED, "fam-other")).isZero();

        entityManager.clear();
        RefreshToken reloaded = refreshTokenRepository.findById(legacy.getId()).orElseThrow();
        assertThat(reloaded.getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.ROTATED);
        assertThat(reloaded.getFamilyId()).isEqualTo("fam-new");
        assertThat(reloaded.isRevoked()).isTrue();
    }

    @Test
    void revokeActiveInFamily_는_그_family_의_살아있는_토큰만_폐기하고_다른_기기는_건드리지_않는다() {
        User user = userRepository.save(User.signUp());
        refreshTokenRepository.save(RefreshToken.issueInFamily(user, "a-active", future(), "fam-a"));
        RefreshToken aOld = RefreshToken.issueInFamily(user, "a-old", future(), "fam-a");
        aOld.revoke(Instant.now().minusSeconds(30), RefreshTokenRevokeReason.ROTATED);
        refreshTokenRepository.save(aOld);
        refreshTokenRepository.save(RefreshToken.issueInFamily(user, "b-active", future(), "fam-b"));

        int count = refreshTokenRepository.revokeActiveInFamily("fam-a", Instant.now(), RefreshTokenRevokeReason.REUSE);

        assertThat(count).isEqualTo(1);
        entityManager.clear();
        assertThat(refreshTokenRepository.findByTokenHash("a-active").orElseThrow().getRevokeReason())
                .isEqualTo(RefreshTokenRevokeReason.REUSE);
        // 이미 폐기된 row 의 최초 사유는 덮어쓰지 않음.
        assertThat(refreshTokenRepository.findByTokenHash("a-old").orElseThrow().getRevokeReason())
                .isEqualTo(RefreshTokenRevokeReason.ROTATED);
        assertThat(refreshTokenRepository.findByTokenHash("b-active").orElseThrow().isRevoked()).isFalse();
    }

    @Test
    void revokeAllActiveByUserId_는_해당_user_의_살아있는_토큰만_폐기한다() {
        User user = userRepository.save(User.signUp());
        User other = userRepository.save(User.signUp());
        refreshTokenRepository.save(RefreshToken.issue(user, "u-1", future()));
        refreshTokenRepository.save(RefreshToken.issue(user, "u-2", future()));
        refreshTokenRepository.save(RefreshToken.issue(other, "o-1", future()));

        int count = refreshTokenRepository.revokeAllActiveByUserId(
                user.getId(), Instant.now(), RefreshTokenRevokeReason.REUSE);

        assertThat(count).isEqualTo(2);
        entityManager.clear();
        assertThat(refreshTokenRepository.findByTokenHash("o-1").orElseThrow().isRevoked()).isFalse();
    }

    private Instant future() {
        return Instant.now().plus(14, ChronoUnit.DAYS);
    }
}
