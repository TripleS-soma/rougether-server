package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineRepository extends JpaRepository<Routine, Long> {

    // 소유권 guard 단건: 타인 소유·미존재·삭제됨 모두 empty
    Optional<Routine> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    // 카테고리 삭제 차단 검사용: status 무관 살아있는 루틴 존재 여부
    boolean existsByCategoryIdAndDeletedAtIsNull(Long categoryId);

    // 응답은 categoryId만 담으므로 category 연관은 lazy id 접근으로 충분함(join fetch 불필요)
    List<Routine> findByUserIdAndDeletedAtIsNullOrderByScheduledTimeAscIdAsc(Long userId);

    List<Routine> findByUserIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscIdAsc(
            Long userId, RoutineStatus status);

    List<Routine> findByUserIdAndCategoryIdAndDeletedAtIsNullOrderByScheduledTimeAscIdAsc(
            Long userId, Long categoryId);

    List<Routine> findByUserIdAndCategoryIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscIdAsc(
            Long userId, Long categoryId, RoutineStatus status);
}
