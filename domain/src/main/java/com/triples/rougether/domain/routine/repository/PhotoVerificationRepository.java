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
            + "(select l.id from RoutineLog l where " + CategoryPurgeQuery.LOG_CONDITION + ")")
    int deleteByCategoryId(@Param("categoryId") Long categoryId, @Param("today") LocalDate today);
}
