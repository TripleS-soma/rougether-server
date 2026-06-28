package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.Todo;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TodoRepository extends JpaRepository<Todo, Long> {

    List<Todo> findByUserIdAndStatusAndDeletedAtIsNull(Long userId, String status);

    // flushAutomatically: 같은 트랜잭션의 category soft delete가 먼저 반영되어야 유실되지 않음
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Todo t set t.category = null where t.category.id = :categoryId")
    int clearCategory(@Param("categoryId") Long categoryId);
}
