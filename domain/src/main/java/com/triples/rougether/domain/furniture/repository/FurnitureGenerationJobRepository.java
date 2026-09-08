package com.triples.rougether.domain.furniture.repository;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Status;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface FurnitureGenerationJobRepository extends JpaRepository<FurnitureGenerationJob, String> {
    Optional<FurnitureGenerationJob> findByUserIdAndRequestId(Long userId, String requestId);
    Optional<FurnitureGenerationJob> findByIdAndUserId(String id, Long userId);
    List<FurnitureGenerationJob> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable page);
    // AI 호출 전 업로드 실패는 일일 생성 횟수에서 제외함. 처리 시도가 있는 실패는 계속 집계함.
    @Query("select count(j) from FurnitureGenerationJob j where j.userId = :userId and j.createdAt >= :since "
            + "and (j.status <> 'FAILED' "
            + "or coalesce(j.failureCode, '') not in ('SOURCE_UPLOAD_FAILED', 'UPLOAD_INTERRUPTED') "
            + "or j.extractionAttempts > 0 or j.imageAttempts > 0 or j.reviewAttempts > 0)")
    long countDailyAttempts(@Param("userId") Long userId, @Param("since") Instant since);
    boolean existsByUserIdAndStatusIn(Long userId, Collection<Status> statuses);

    @Query("select j.userId from FurnitureGenerationJob j where j.id = :id")
    Optional<Long> findOwnerId(@Param("id") String id);

    @Query("select count(j) > 0 from FurnitureGenerationJob j where "
            + "j.sourceKey = :key or j.candidateKey = :key or j.resultAssetKey = :key")
    boolean referencesAsset(@Param("key") String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from FurnitureGenerationJob j where j.id = :id")
    Optional<FurnitureGenerationJob> findForUpdate(@Param("id") String id);

    @Query("select j.id from FurnitureGenerationJob j where j.status = 'QUEUED' "
            + "and j.nextRunAt <= :now order by j.nextRunAt, j.id")
    List<String> findDue(@Param("now") Instant now, Pageable page);

    @Query("select j.id from FurnitureGenerationJob j where "
            + "(j.status = 'PROCESSING' and j.leaseUntil <= :now) "
            + "or (j.status = 'UPLOADING' and j.createdAt <= :uploadCutoff) "
            + "or (j.expiresAt <= :now and (j.sourceKey is not null or j.candidateKey is not null "
            + "or j.targetHint <> '' or j.feedback <> '' or j.status = 'QUEUED')) "
            + "or (j.sourceKey is not null and exists "
            + "(select u.id from User u where u.id = j.userId and u.deletedAt is not null)) "
            + "order by j.createdAt")
    List<String> findForMaintenance(@Param("now") Instant now, @Param("uploadCutoff") Instant uploadCutoff,
                                    Pageable page);
}
