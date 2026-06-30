package com.triples.rougether.userapi.routine.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.category.error.CategoryErrorCode;
import com.triples.rougether.userapi.routine.dto.RoutineCreateRequest;
import com.triples.rougether.userapi.routine.dto.RoutineListResponse;
import com.triples.rougether.userapi.routine.dto.RoutineResponse;
import com.triples.rougether.userapi.routine.dto.RoutineUpdateRequest;
import com.triples.rougether.userapi.routine.error.RoutineErrorCode;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoutineService {

    private final RoutineRepository routineRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public RoutineListResponse list(Long userId, Long categoryId, RoutineStatus status) {
        List<Routine> routines;
        if (categoryId != null && status != null) {
            routines = routineRepository
                    .findByUserIdAndCategoryIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscIdAsc(
                            userId, categoryId, status);
        } else if (categoryId != null) {
            routines = routineRepository
                    .findByUserIdAndCategoryIdAndDeletedAtIsNullOrderByScheduledTimeAscIdAsc(
                            userId, categoryId);
        } else if (status != null) {
            routines = routineRepository
                    .findByUserIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscIdAsc(
                            userId, status);
        } else {
            routines = routineRepository
                    .findByUserIdAndDeletedAtIsNullOrderByScheduledTimeAscIdAsc(userId);
        }
        return new RoutineListResponse(routines.stream().map(RoutineResponse::from).toList());
    }

    @Transactional(readOnly = true)
    public RoutineResponse get(Long userId, Long routineId) {
        return RoutineResponse.from(findOwned(userId, routineId));
    }

    @Transactional
    public RoutineResponse create(Long userId, RoutineCreateRequest request) {
        User user = userRepository.getReferenceById(userId);
        Category category = request.categoryId() != null
                ? findOwnedCategory(userId, request.categoryId()) : null;
        String repeatDays = request.repeatDays() != null ? request.repeatDays().toJson() : null;
        Routine routine = Routine.create(user, category, request.title(), request.authType(),
                request.repeatType(), repeatDays, request.scheduledTime(),
                request.startsOn(), request.endsOn());
        return RoutineResponse.from(routineRepository.save(routine));
    }

    @Transactional
    public RoutineResponse update(Long userId, Long routineId, RoutineUpdateRequest request) {
        Routine routine = findOwned(userId, routineId);
        if (request.categoryId() != null) {
            routine.changeCategory(findOwnedCategory(userId, request.categoryId()));
        }
        String repeatDays = request.repeatDays() != null ? request.repeatDays().toJson() : null;
        routine.update(request.title(), request.authType(), request.repeatType(),
                repeatDays, request.scheduledTime(), request.startsOn(),
                request.endsOn());
        return RoutineResponse.from(routine);
    }

    @Transactional
    public void delete(Long userId, Long routineId) {
        findOwned(userId, routineId).softDelete(Instant.now());
    }

    private Routine findOwned(Long userId, Long routineId) {
        return routineRepository.findByIdAndUserIdAndDeletedAtIsNull(routineId, userId)
                .orElseThrow(() -> new BusinessException(RoutineErrorCode.ROUTINE_NOT_FOUND));
    }

    private Category findOwnedCategory(Long userId, Long categoryId) {
        return categoryRepository.findByIdAndUserIdAndDeletedAtIsNull(categoryId, userId)
                .orElseThrow(() -> new BusinessException(CategoryErrorCode.CATEGORY_NOT_FOUND));
    }
}
