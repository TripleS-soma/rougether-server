package com.triples.rougether.userapi.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.userapi.global.config.JpaConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class NotificationRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private TestEntityManager entityManager;

    private Notification saveNotification(User user) {
        return notificationRepository.save(
                Notification.create(user, NotificationType.ROUTINE_REMINDER, "제목", "내용", null));
    }

    @Test
    void 커서_조회는_본인_알림만_최신순으로_커서_이전부터_가져온다() {
        User me = userRepository.save(User.signUp());
        User other = userRepository.save(User.signUp());
        Notification n1 = saveNotification(me);
        Notification n2 = saveNotification(me);
        Notification n3 = saveNotification(me);
        saveNotification(other);

        List<Notification> firstPage = notificationRepository.findPageByCursor(
                me.getId(), null, PageRequest.of(0, 2));
        assertThat(firstPage).extracting(Notification::getId)
                .containsExactly(n3.getId(), n2.getId());

        List<Notification> nextPage = notificationRepository.findPageByCursor(
                me.getId(), n2.getId(), PageRequest.of(0, 2));
        assertThat(nextPage).extracting(Notification::getId)
                .containsExactly(n1.getId());
    }

    @Test
    void 전체_읽음은_본인의_안읽은_알림만_바꾼다() {
        User me = userRepository.save(User.signUp());
        User other = userRepository.save(User.signUp());
        Notification unread1 = saveNotification(me);
        Notification unread2 = saveNotification(me);
        Notification alreadyRead = saveNotification(me);
        alreadyRead.markRead();
        Notification othersUnread = saveNotification(other);

        int updated = notificationRepository.markAllReadByUserId(me.getId());

        assertThat(updated).isEqualTo(2);
        // bulk update 는 영속성 컨텍스트를 우회하므로 clear 후 재조회.
        entityManager.flush();
        entityManager.clear();
        assertThat(notificationRepository.findById(unread1.getId()).orElseThrow().isRead()).isTrue();
        assertThat(notificationRepository.findById(unread2.getId()).orElseThrow().isRead()).isTrue();
        assertThat(notificationRepository.findById(alreadyRead.getId()).orElseThrow().isRead()).isTrue();
        assertThat(notificationRepository.findById(othersUnread.getId()).orElseThrow().isRead()).isFalse();
    }
}
