package com.triples.rougether.userapi.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.notification.service.NotificationPushStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.TestTransaction;

// FcmPushExecutor는 @Async(주변 트랜잭션 없는 스레드)에서 NotificationPushStatusService를 호출한다.
// TestTransaction.end()로 @DataJpaTest가 걸어주는 트랜잭션을 커밋·종료해 그 상황(주변 트랜잭션 없음)을 재현하고,
// markSent/markFailed가 스스로의 @Transactional로 커밋까지 마치는지 검증한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, NotificationPushStatusService.class})
class NotificationPushStatusServiceTransactionTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private NotificationPushStatusService notificationPushStatusService;

    @Test
    void 주변_트랜잭션_없이_호출해도_SENT_갱신이_커밋된다() {
        User user = userRepository.save(User.signUp());
        Notification notification = notificationRepository.save(
                Notification.create(user, NotificationType.ROUTINE_REMINDER, "제목", "내용", null));

        TestTransaction.flagForCommit();
        TestTransaction.end();

        notificationPushStatusService.markSent(notification.getId());

        assertThat(notificationRepository.findById(notification.getId()).orElseThrow().getPushStatus())
                .isEqualTo(PushStatus.SENT);

        // 트랜잭션을 커밋·종료했으므로 @DataJpaTest 기본 롤백이 적용되지 않는다 — 직접 정리.
        TestTransaction.start();
        notificationRepository.delete(notificationRepository.findById(notification.getId()).orElseThrow());
        userRepository.delete(userRepository.findById(user.getId()).orElseThrow());
    }

    @Test
    void 주변_트랜잭션_없이_호출해도_FAILED_갱신이_커밋된다() {
        User user = userRepository.save(User.signUp());
        Notification notification = notificationRepository.save(
                Notification.create(user, NotificationType.ROUTINE_REMINDER, "제목", "내용", null));

        TestTransaction.flagForCommit();
        TestTransaction.end();

        notificationPushStatusService.markFailed(notification.getId());

        assertThat(notificationRepository.findById(notification.getId()).orElseThrow().getPushStatus())
                .isEqualTo(PushStatus.FAILED);

        TestTransaction.start();
        notificationRepository.delete(notificationRepository.findById(notification.getId()).orElseThrow());
        userRepository.delete(userRepository.findById(user.getId()).orElseThrow());
    }
}
