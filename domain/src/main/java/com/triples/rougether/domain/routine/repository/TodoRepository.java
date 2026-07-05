package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TodoRepository extends JpaRepository<Todo, Long> {

    // 소유권 guard 단건: 타인 소유·미존재·삭제됨 모두 empty
    Optional<Todo> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    // 오늘 현황용: 오늘까지 마감(overdue 포함) 투두
    List<Todo> findByUserIdAndDueDateLessThanEqualAndDeletedAtIsNull(Long userId, LocalDate dueDate);

    // categoryId/status/dueDate는 null이면 해당 조건 무시(동적 필터). dueDate는 오늘 현황용으로도 재사용함
    @Query("""
            select t from Todo t
            where t.user.id = :userId
              and t.deletedAt is null
              and (:categoryId is null or t.category.id = :categoryId)
              and (:status is null or t.status = :status)
              and (:dueDate is null or t.dueDate = :dueDate)
            order by t.dueDate asc, t.id asc
            """)
    List<Todo> findOwnedWithFilters(@Param("userId") Long userId,
                                    @Param("categoryId") Long categoryId,
                                    @Param("status") TodoStatus status,
                                    @Param("dueDate") LocalDate dueDate);
}
