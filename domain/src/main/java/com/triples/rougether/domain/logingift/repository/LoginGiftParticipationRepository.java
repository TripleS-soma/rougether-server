package com.triples.rougether.domain.logingift.repository;

import com.triples.rougether.domain.logingift.entity.LoginGiftParticipation;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginGiftParticipationRepository extends JpaRepository<LoginGiftParticipation, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LoginGiftParticipation p where p.event.id = :eventId and p.user.id = :userId")
    Optional<LoginGiftParticipation> findForUpdate(@Param("eventId") Long eventId, @Param("userId") Long userId);

    @Query("select p from LoginGiftParticipation p join fetch p.event e join fetch e.rewardItem "
            + "left join fetch p.rewardUserItem where p.user.id = :userId order by p.id desc")
    List<LoginGiftParticipation> findReceipts(@Param("userId") Long userId);

    @Query("""
            select p.id as id, p.event.id as eventId, p.user.id as userId
            from LoginGiftParticipation p
            where p.grantedAt is null and p.id > :afterId and p.event.enabled = true
              and p.user.deletedAt is null and p.user.bot = false and p.user.ai = false
            order by p.id
            """)
    List<PendingGift> findPending(@Param("afterId") long afterId, Pageable pageable);

    interface PendingGift {
        Long getId();
        Long getEventId();
        Long getUserId();
    }
}
