package com.triples.rougether.domain.christmas.repository;

import com.triples.rougether.domain.christmas.entity.EventPopupDismissal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventPopupDismissalRepository extends JpaRepository<EventPopupDismissal, Long> {
    boolean existsByUserIdAndEventCode(Long userId, String eventCode);

    @Modifying(flushAutomatically = true)
    @Query("delete from EventPopupDismissal dismissal where dismissal.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
