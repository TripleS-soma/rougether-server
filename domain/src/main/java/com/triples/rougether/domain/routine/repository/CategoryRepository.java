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

    // 탈퇴·강퇴 시 그 집과의 연동 해제(해당 회원 것만). clearAutomatically 는 쓰지 않는다 - 호출
    // 트랜잭션이 잠근 house 관리 엔티티가 detach 되면 이후·미flush 변경이 유실된다. 이 쿼리 뒤
    // 같은 트랜잭션에서 카테고리를 다시 읽지 않는 위치(트랜잭션 끝)에서 호출한다(PC 의 카테고리는 stale).
    @Modifying(flushAutomatically = true)
    @Query("update Category c set c.houseId = null where c.user.id = :userId and c.houseId = :houseId")
    int clearHouseLinkOfMember(@Param("userId") Long userId, @Param("houseId") Long houseId);
}
