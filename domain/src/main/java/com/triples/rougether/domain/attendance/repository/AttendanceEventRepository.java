package com.triples.rougether.domain.attendance.repository;

import com.triples.rougether.domain.attendance.entity.AttendanceEvent;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttendanceEventRepository extends JpaRepository<AttendanceEvent, Long> {

    boolean existsByCode(String code);

    Optional<AttendanceEvent> findByCode(String code);

    @Query("select event from AttendanceEvent event left join fetch event.rewardItem where event.id = :id")
    Optional<AttendanceEvent> findWithRewardById(@Param("id") Long id);

    @Query("select event from AttendanceEvent event left join fetch event.rewardItem "
            + "where event.active = true and event.mode = com.triples.rougether.domain.attendance.entity.AttendanceMode.STREAK and event.startsOn <= :date and event.endsOn >= :date "
            + "order by event.startsOn desc, event.id desc")
    List<AttendanceEvent> findActiveOn(@Param("date") LocalDate date);

    @Query("select count(event) from AttendanceEvent event "
            + "where event.active = true and event.mode = com.triples.rougether.domain.attendance.entity.AttendanceMode.STREAK and event.startsOn <= :endsOn and event.endsOn >= :startsOn")
    long countActiveOverlapping(@Param("startsOn") LocalDate startsOn,
                                @Param("endsOn") LocalDate endsOn);
}
