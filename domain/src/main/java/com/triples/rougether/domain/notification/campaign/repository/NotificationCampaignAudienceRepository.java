package com.triples.rougether.domain.notification.campaign.repository;

import com.triples.rougether.domain.member.entity.User;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface NotificationCampaignAudienceRepository extends Repository<User, Long> {
    @Query("select u.id from User u where u.id > :afterId and u.deletedAt is null "
            + "and u.bot = false and u.ai = false order by u.id")
    List<Long> findEligibleAfter(@Param("afterId") Long afterId, Pageable pageable);

    @Query("select u.id from User u where u.id in :ids and u.deletedAt is null "
            + "and u.bot = false and u.ai = false order by u.id")
    List<Long> findEligibleIds(@Param("ids") Collection<Long> ids);

    @Query("select distinct t.user.id from UserDeviceToken t where t.user.id in :ids")
    List<Long> findUsersWithTokens(@Param("ids") Collection<Long> ids);
}
