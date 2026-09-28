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
    long countByStatus(com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Status status);
    long countByStatusIn(Collection<Status> statuses);

    Optional<FurnitureGenerationJob> findByUserIdAndRequestId(Long userId, String requestId);
    Optional<FurnitureGenerationJob> findByIdAndUserId(String id, Long userId);
    List<FurnitureGenerationJob> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable page);
    boolean existsByUserIdAndStatusIn(Long userId, Collection<Status> statuses);

    @Query("select j.userId from FurnitureGenerationJob j where j.id = :id")
    Optional<Long> findOwnerId(@Param("id") String id);

    @Query("select count(j) > 0 from FurnitureGenerationJob j where "
            + "j.sourceKey = :key or j.rawSourceKey = :key or j.candidateKey = :key or j.resultAssetKey = :key")
    boolean referencesAsset(@Param("key") String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from FurnitureGenerationJob j where j.id = :id")
    Optional<FurnitureGenerationJob> findForUpdate(@Param("id") String id);

    // 거래소 발행 자격 확인용. AI 가구 items row 를 처음 지급받은 생성 작업(요청자 = 제작자)의 ID.
    // 되팔기로 받은 user_item 에는 생성 작업이 없으므로 user_item 이 아니라 item 기준으로 찾음.
    // 잠금은 이 ID 로 findForUpdate 해서 작업 행(PK)만 잡음 — 서브쿼리 잠금 범위가 user_items 로 번지지 않게 함(#400).
    @Query("select j.id from FurnitureGenerationJob j where j.userItemId in "
            + "(select ui.id from UserItem ui where ui.item.id = :itemId)")
    Optional<String> findGenerationJobIdByItemId(@Param("itemId") Long itemId);

    @Query("select j.id from FurnitureGenerationJob j where j.status = 'QUEUED' "
            + "and j.nextRunAt <= :now order by j.nextRunAt, j.id")
    List<String> findDue(@Param("now") Instant now, Pageable page);

    @Query("select j.id from FurnitureGenerationJob j where "
            + "(j.status = 'PROCESSING' and j.leaseUntil <= :now) "
            + "or (j.status = 'UPLOADING' and ((j.uploadExpiresAt is null and j.createdAt <= :uploadCutoff) or j.uploadExpiresAt <= :now)) "
            + "or (j.status = 'QUEUED' and j.nextRunAt <= :queueCutoff) "
            + "or (j.expiresAt <= :now and (j.sourceKey is not null or j.rawSourceKey is not null or j.candidateKey is not null "
            + "or j.targetHint <> '' or j.feedback <> '' or j.status = 'QUEUED')) "
            + "or ((j.sourceKey is not null or j.rawSourceKey is not null) and exists "
            + "(select u.id from User u where u.id = j.userId and u.deletedAt is not null)) "
            + "order by j.createdAt")
    List<String> findForMaintenance(@Param("now") Instant now, @Param("uploadCutoff") Instant uploadCutoff,
                                    @Param("queueCutoff") Instant queueCutoff,
                                    Pageable page);
}
