package com.triples.rougether.userapi.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.userapi.notification.service.NotificationCommandService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

// Notification 의 @DynamicUpdate 회귀 테스트(User 의 탈퇴/로그인 flush 테스트와 같은 구조).
// 전체 컬럼 UPDATE 면 stale 스냅샷 flush 가 deleted_at·push_status 를 되써서 soft delete 가 풀리거나 재발송됨.
@SpringBootTest
class NotificationDeleteConcurrencyTest {

    @Autowired private NotificationCommandService notificationCommandService;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 테스트 트랜잭션이 없어 직접 정리함.
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", userId);
            userRepository.deleteById(userId);
        }
    }

    private Notification savedNotificationOfNewUser() {
        User user = userRepository.save(User.signUp());
        userIds.add(user.getId());
        return notificationRepository.save(
                Notification.create(user, NotificationType.ROUTINE_REMINDER, "제목", "내용", null));
    }

    private static void runAndJoin(Runnable task) {
        Thread thread = new Thread(task);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 삭제와_동시에_진행되던_push_상태_갱신_flush_가_soft_delete_를_되돌리지_않는다() {
        // push 상태 갱신 트랜잭션(T2)이 삭제 커밋 전 스냅샷(deletedAt=null)을 들고 있다가
        // 삭제(T1) 커밋 후 markPushSent() 로 flush 해도 deleted_at 을 되쓰지 않아야 함.
        Notification saved = savedNotificationOfNewUser();
        Long userId = saved.getUser().getId();

        transactionTemplate.executeWithoutResult(status -> {
            Notification stale = notificationRepository.findById(saved.getId()).orElseThrow();
            runAndJoin(() -> notificationCommandService.delete(userId, saved.getId()));
            stale.markPushSent();
        });

        Notification after = notificationRepository.findById(saved.getId()).orElseThrow();
        assertThat(after.getDeletedAt()).isNotNull();
        assertThat(after.getPushStatus()).isEqualTo(PushStatus.SENT);
    }

    @Test
    void 삭제_flush_가_그_사이_batch_가_bulk_로_갱신한_push_status_를_되돌리지_않는다() {
        // 사용자 삭제 트랜잭션(T2)이 push_status=PENDING 스냅샷을 든 채로 batch(T1)의 SENT bulk update 가
        // 커밋되면, T2 flush 가 PENDING 을 되써서 다음 실행에 재발송되는 역방향 회귀를 막음.
        Notification saved = savedNotificationOfNewUser();

        transactionTemplate.executeWithoutResult(status -> {
            Notification stale = notificationRepository.findById(saved.getId()).orElseThrow();
            runAndJoin(() -> transactionTemplate.executeWithoutResult(inner ->
                    notificationRepository.updatePushStatus(saved.getId(), PushStatus.SENT)));
            stale.softDelete(Instant.now());
        });

        Notification after = notificationRepository.findById(saved.getId()).orElseThrow();
        assertThat(after.getDeletedAt()).isNotNull();
        assertThat(after.getPushStatus()).isEqualTo(PushStatus.SENT);
    }
}
