package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.Category;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByUserIdAndDeletedAtIsNullOrderBySortOrderAsc(Long userId);

    // includeDeleted=true 경로용: soft-deleted 포함 전체 목록
    List<Category> findByUserIdOrderBySortOrderAsc(Long userId);

    Optional<Category> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    // 카테고리가 없으면 null 반환
    @Query("select max(c.sortOrder) from Category c"
            + " where c.user.id = :userId and c.deletedAt is null")
    Integer findMaxSortOrderByUserId(@Param("userId") Long userId);

    // 탈퇴·강퇴 시 그 집과의 연동 해제(해당 회원 것만). PC 를 비우므로(clearAutomatically) 트랜잭션 끝에서 호출한다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Category c set c.houseId = null where c.user.id = :userId and c.houseId = :houseId")
    int clearHouseLinkOfMember(@Param("userId") Long userId, @Param("houseId") Long houseId);
}
