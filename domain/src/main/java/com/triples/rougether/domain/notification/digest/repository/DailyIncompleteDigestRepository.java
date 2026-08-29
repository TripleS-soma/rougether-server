package com.triples.rougether.domain.notification.digest.repository;

import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigest;
import com.triples.rougether.domain.notification.entity.PushStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyIncompleteDigestRepository extends JpaRepository<DailyIncompleteDigest, Long> {

    boolean existsByUserIdAndDigestDate(Long userId, LocalDate digestDate);

    Optional<DailyIncompleteDigest> findByUserIdAndDigestDate(Long userId, LocalDate digestDate);

    @Modifying
    @Query("update DailyIncompleteDigest d set d.pushStatus = :pushStatus, d.sentAt = :sentAt "
            + "where d.notification.id = :notificationId")
    int updatePushStatusByNotificationId(@Param("notificationId") Long notificationId,
                                         @Param("pushStatus") PushStatus pushStatus,
                                         @Param("sentAt") java.time.Instant sentAt);

    @Query("select d from DailyIncompleteDigest d join fetch d.user left join fetch d.notification "
            + "where d.digestDate between :fromDate and :toDate order by d.digestDate desc, d.id desc")
    List<DailyIncompleteDigest> findWithNotificationBetween(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    @Modifying(flushAutomatically = true)
    @Query("delete from DailyIncompleteDigest d where d.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
