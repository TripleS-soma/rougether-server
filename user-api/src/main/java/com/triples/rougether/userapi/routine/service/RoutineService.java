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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoutineService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RoutineRepository routineRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public RoutineListResponse list(Long userId, Long categoryId, RoutineStatus status) {
        List<Routine> routines;
        if (categoryId != null && status != null) {
            routines = routineRepository
                    .findByUserIdAndCategoryIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(
                            userId, categoryId, status);
        } else if (categoryId != null) {
            routines = routineRepository
                    .findByUserIdAndCategoryIdAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(
                            userId, categoryId);
        } else if (status != null) {
            routines = routineRepository
                    .findByUserIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(
                            userId, status);
        } else {
            routines = routineRepository
                    .findByUserIdAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(userId);
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
        LocalDate startsOn = resolveStartsOn(request.startsOn());
        Routine routine = Routine.create(user, category, request.title(), request.authType(),
                request.repeatType(), repeatDays, request.scheduledTime(),
                startsOn, request.endsOn());
        Routine saved = routineRepository.save(routine);
        // 계보 루트를 자기 id로 지정(dirty → 트랜잭션 커밋 시 반영)
        saved.assignOriginToSelf();
        return RoutineResponse.from(saved);
    }

    @Transactional
    public RoutineResponse update(Long userId, Long routineId, RoutineUpdateRequest request) {
        Routine routine = findOwned(userId, routineId);
        Category category = request.categoryId() != null
                ? findOwnedCategory(userId, request.categoryId()) : null;
        String repeatDays = request.repeatDays() != null ? request.repeatDays().toJson() : null;
        validateStartsOn(routine, request.startsOn());

        // 반복 스케줄이 실제로 바뀌고, 경과한 날이 있는(created_at<오늘) 버전이면 새 버전으로 분기.
        // 옛 버전은 그대로 닫아(deleted_at) 과거 유효기간엔 남기고, 응답은 새 버전(새 id)
        if (isScheduleChanged(routine, request, repeatDays) && hasElapsedDay(routine)) {
            Routine newVersion = routine.copyAsNewVersion(category, request.title(),
                    request.authType(), request.repeatType(), repeatDays,
                    request.scheduledTime(), request.startsOn(), request.endsOn());
            routine.softDelete(Instant.now());
            return RoutineResponse.from(routineRepository.save(newVersion));
        }

        // 제자리 수정(제목·카테고리·시각·인증 변경, 또는 오늘 생성분의 스케줄 변경) — 과거에도 반영됨
        if (category != null) {
            routine.changeCategory(category);
        }
        routine.update(request.title(), request.authType(), request.repeatType(),
                repeatDays, request.scheduledTime(), request.startsOn(),
                request.endsOn());
        return RoutineResponse.from(routine);
    }

    // 반복 스케줄 필드(repeat_type·repeat_days·starts_on·ends_on) 중 하나라도 현재값과 달라졌는지.
    // repeat_type·repeat_days·starts_on은 부분수정이라 제공한 필드만 비교하고,
    // ends_on은 null=해제라 항상 현재값과 비교(해제도 변경으로 침)
    private boolean isScheduleChanged(Routine routine, RoutineUpdateRequest request, String repeatDays) {
        if (request.repeatType() != null
                && !request.repeatType().equals(routine.getRepeatType())) {
            return true;
        }
        if (repeatDays != null && !repeatDays.equals(routine.getRepeatDays())) {
            return true;
        }
        if (request.startsOn() != null && !request.startsOn().equals(routine.getStartsOn())) {
            return true;
        }
        return !Objects.equals(request.endsOn(), routine.getEndsOn());
    }

    // 생성 시: 시작일 미지정이면 생성일(오늘)로 기본 지정. 스냅샷 구조상 생성일 이전 소급은 불가.
    private LocalDate resolveStartsOn(LocalDate startsOn) {
        LocalDate today = LocalDate.now(KST);
        if (startsOn == null) {
            return today;
        }
        if (startsOn.isBefore(today)) {
            throw new BusinessException(RoutineErrorCode.ROUTINE_STARTS_ON_BEFORE_TODAY);
        }
        return startsOn;
    }

    // 수정 시: 시작일을 실제로 바꿀 때만 검사(null=유지, 기존값 재전송=no-op은 통과). 과거로 옮기는 것만 불가.
    // startsOn>=오늘은 시간 상대적 제약이라 기준선(오늘)이 움직임 → 안 바뀐 값을 다시 검사하면 멱등성 깨짐
    private void validateStartsOn(Routine routine, LocalDate startsOn) {
        if (startsOn != null
                && !startsOn.equals(routine.getStartsOn())
                && startsOn.isBefore(LocalDate.now(KST))) {
            throw new BusinessException(RoutineErrorCode.ROUTINE_STARTS_ON_BEFORE_TODAY);
        }
    }

    // 이 버전이 오늘 이전에 생성됐는지
    private boolean hasElapsedDay(Routine routine) {
        LocalDate createdDate = LocalDate.ofInstant(routine.getCreatedAt(), KST);
        return createdDate.isBefore(LocalDate.now(KST));
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
