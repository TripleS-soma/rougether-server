package com.triples.rougether.domain.notification.campaign.repository;

import com.triples.rougether.domain.notification.campaign.entity.NotificationCampaignRecipient;
import com.triples.rougether.domain.notification.campaign.entity.NotificationCampaignRecipient.Status;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface NotificationCampaignRecipientRepository extends JpaRepository<NotificationCampaignRecipient, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from NotificationCampaignRecipient r where r.id = :id")
    Optional<NotificationCampaignRecipient> findByIdForUpdate(@Param("id") Long id);

    @Query("select r.id from NotificationCampaignRecipient r where r.campaign.id = :campaignId "
            + "and r.status = com.triples.rougether.domain.notification.campaign.entity.NotificationCampaignRecipient.Status.PENDING order by r.id")
    List<Long> findPendingIds(@Param("campaignId") Long campaignId, Pageable pageable);

    long countByCampaignIdAndStatus(Long campaignId, Status status);

    @Query("select r.status, count(r) from NotificationCampaignRecipient r where r.campaign.id = :campaignId group by r.status")
    List<Object[]> countByStatus(@Param("campaignId") Long campaignId);

    @Query("select n.pushStatus, count(n) from NotificationCampaignRecipient r, Notification n "
            + "where r.campaign.id = :campaignId and n.id = r.notificationId group by n.pushStatus")
    List<Object[]> countPushByStatus(@Param("campaignId") Long campaignId);
}
