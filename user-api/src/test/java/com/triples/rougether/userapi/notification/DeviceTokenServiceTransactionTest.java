package com.triples.rougether.userapi.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.DevicePlatform;
import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.notification.service.DeviceTokenService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.TestTransaction;

// FcmPushExecutor는 @Async(주변 트랜잭션 없는 스레드)에서 DeviceTokenService.deleteAllByToken을 호출한다.
// TestTransaction.end()로 @DataJpaTest가 걸어주는 트랜잭션을 커밋·종료해 그 상황(주변 트랜잭션 없음)을 재현하고,
// deleteAllByToken이 스스로의 @Transactional로 커밋까지 마치는지(TransactionRequiredException 없이) 검증한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, DeviceTokenService.class})
class DeviceTokenServiceTransactionTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserDeviceTokenRepository userDeviceTokenRepository;
    @Autowired
    private DeviceTokenService deviceTokenService;

    @Test
    void 주변_트랜잭션_없이_호출해도_무효_토큰_삭제가_커밋된다() {
        User user = userRepository.save(User.signUp());
        userDeviceTokenRepository.save(
                UserDeviceToken.register(user, "invalid-token", DevicePlatform.IOS, Instant.now()));

        TestTransaction.flagForCommit();
        TestTransaction.end();

        deviceTokenService.deleteAllByToken(user.getId(), List.of("invalid-token"));

        assertThat(userDeviceTokenRepository.findByToken("invalid-token")).isEmpty();

        // 트랜잭션을 커밋·종료했으므로 @DataJpaTest 기본 롤백이 적용되지 않는다 — 직접 정리.
        TestTransaction.start();
        userRepository.delete(userRepository.findById(user.getId()).orElseThrow());
    }
}
