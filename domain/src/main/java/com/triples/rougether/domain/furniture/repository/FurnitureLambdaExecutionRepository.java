package com.triples.rougether.domain.furniture.repository;

import com.triples.rougether.domain.furniture.entity.FurnitureLambdaExecution;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface FurnitureLambdaExecutionRepository extends JpaRepository<FurnitureLambdaExecution, String> {
    void deleteByJobId(String jobId);
    @Query("select e.jobId from FurnitureLambdaExecution e where e.id = :id")
    Optional<String> findJobId(@org.springframework.data.repository.query.Param("id") String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from FurnitureLambdaExecution e where e.id = :id")
    Optional<FurnitureLambdaExecution> lock(@org.springframework.data.repository.query.Param("id") String id);
    @Query("select e.id from FurnitureLambdaExecution e where e.state = 'PENDING' and e.publishAfter <= :now order by e.createdAt, e.id")
    List<String> pending(@org.springframework.data.repository.query.Param("now") Instant now, Pageable pageable);
}
