package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.PhotoVerification;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PhotoVerificationRepository extends JpaRepository<PhotoVerification, Long> {

    List<PhotoVerification> findByRoutineLogId(Long routineLogId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PhotoVerification p where p.routineLog.id in "
            + "(select l.id from RoutineLog l where l.routine.id in "
            + "(select r.id from Routine r where r.category.id = :categoryId "
            + "  and not exists (select 1 from Routine live "
            + "    where coalesce(live.originRoutineId, live.id) = coalesce(r.originRoutineId, r.id) "
            + "    and live.deletedAt is null)) "
            + "and not (l.routineDate = :today and l.rewardAmount > 0))")
    int deleteByCategoryId(@Param("categoryId") Long categoryId, @Param("today") LocalDate today);
}
