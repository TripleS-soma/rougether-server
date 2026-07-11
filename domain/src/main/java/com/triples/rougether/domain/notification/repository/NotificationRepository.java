package com.triples.rougether.domain.notification.repository;

import com.triples.rougether.domain.notification.entity.Notification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 커서 기반 최신순 조회 - cursor(null 이면 첫 페이지)보다 오래된 알림을 id 내림차순으로.
    @Query("select n from Notification n "
            + "where n.user.id = :userId and (:cursor is null or n.id < :cursor) order by n.id desc")
    List<Notification> findPageByCursor(@Param("userId") Long userId,
                                        @Param("cursor") Long cursor,
                                        Pageable pageable);

    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    // 전체 읽음 - 안 읽은 알림만 bulk update
    @Modifying
    @Query("update Notification n set n.isRead = true where n.user.id = :userId and n.isRead = false")
    int markAllReadByUserId(@Param("userId") Long userId);
}
