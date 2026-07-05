package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.Category;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
