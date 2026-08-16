package com.triples.rougether.domain.attendance.repository;

import com.triples.rougether.domain.attendance.entity.AttendanceCheckIn;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttendanceCheckInRepository extends JpaRepository<AttendanceCheckIn, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select checkIn from AttendanceCheckIn checkIn "
            + "where checkIn.event.id = :eventId and checkIn.user.id = :userId "
            + "and checkIn.attendanceDate = :attendanceDate")
    Optional<AttendanceCheckIn> findForUpdateByEventIdAndUserIdAndAttendanceDate(
            @Param("eventId") Long eventId,
            @Param("userId") Long userId,
            @Param("attendanceDate") LocalDate attendanceDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AttendanceCheckIn> findFirstByEventIdAndUserIdOrderByAttendanceDateDesc(
            Long eventId, Long userId);

    List<AttendanceCheckIn> findByEventIdAndUserIdOrderByAttendanceDateAsc(Long eventId, Long userId);

    long countByEventIdAndUserId(Long eventId, Long userId);
}
