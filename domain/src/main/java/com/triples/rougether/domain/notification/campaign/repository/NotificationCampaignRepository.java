package com.triples.rougether.domain.notification.campaign.repository;

import com.triples.rougether.domain.admin.entity.AdminUser;
import com.triples.rougether.domain.notification.campaign.entity.NotificationCampaign;
import com.triples.rougether.domain.notification.campaign.entity.NotificationCampaign.Status;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface NotificationCampaignRepository extends JpaRepository<NotificationCampaign, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AdminUser a where a.username = :username")
    Optional<AdminUser> lockAdmin(@Param("username") String username);

    Optional<NotificationCampaign> findByCreatedByIdAndRequestId(Long adminId, String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from NotificationCampaign c where c.id = :id")
    Optional<NotificationCampaign> findByIdForUpdate(@Param("id") Long id);

    @Query("select c from NotificationCampaign c join fetch c.createdBy order by c.id desc")
    List<NotificationCampaign> findRecent(Pageable pageable);

    @Query("select c.id from NotificationCampaign c where c.status = :status order by c.id")
    List<Long> findIdsByStatus(@Param("status") Status status, Pageable pageable);
}
