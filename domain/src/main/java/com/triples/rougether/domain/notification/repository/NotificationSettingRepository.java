package com.triples.rougether.domain.notification.repository;

import com.triples.rougether.domain.notification.entity.NotificationSetting;
import com.triples.rougether.domain.notification.entity.NotificationSettingType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, Long> {

    Optional<NotificationSetting> findByUserIdAndType(Long userId, NotificationSettingType type);

    List<NotificationSetting> findAllByUserId(Long userId);

    List<NotificationSetting> findAllByUserIdIn(Collection<Long> userIds);

    // 회원탈퇴 시 알림 설정 전량 삭제 — 본인 전용 데이터라 즉시 파기함.
    @Modifying(flushAutomatically = true)
    @Query("delete from NotificationSetting ns where ns.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
