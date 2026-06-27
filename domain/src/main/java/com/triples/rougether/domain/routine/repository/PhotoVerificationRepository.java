package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.PhotoVerification;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PhotoVerificationRepository extends JpaRepository<PhotoVerification, Long> {

    List<PhotoVerification> findByRoutineLogId(Long routineLogId);
}
