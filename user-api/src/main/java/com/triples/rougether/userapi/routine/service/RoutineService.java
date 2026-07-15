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
import com.triples.rougether.userapi.routine.dto.RepeatDays;
import com.triples.rougether.userapi.routine.dto.RoutineCreateRequest;
import com.triples.rougether.userapi.routine.dto.RoutineListResponse;
import com.triples.rougether.userapi.routine.dto.RoutineResponse;
import com.triples.rougether.userapi.routine.dto.RoutineUpdateRequest;
import com.triples.rougether.userapi.routine.error.RoutineErrorCode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
        validateRepeatSchedule(request.repeatType(), request.startsOn(), request.repeatDays());
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

        String effectiveRepeatType = request.repeatType() != null
                ? request.repeatType() : routine.getRepeatType();
        LocalDate effectiveStartsOn = request.startsOn() != null
                ? request.startsOn() : routine.getStartsOn();
        RepeatDays effectiveRepeatDays = request.repeatDays() != null
                ? request.repeatDays() : RepeatDays.fromJson(routine.getRepeatDays());
        validateRepeatSchedule(effectiveRepeatType, effectiveStartsOn, effectiveRepeatDays);

        // 반복 스케줄이 실제로 바뀌고, 경과한 날이 있는(created_at<오늘) 버전이면 새 버전으로 분기.
        // 옛 버전은 그대로 닫아(deleted_at) 과거 유효기간엔 남기고, 응답은 새 버전(새 id)
        if (isScheduleChanged(routine, request) && hasElapsedDay(routine)) {
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
    // repeatDays는 JSON 텍스트가 아니라 파싱한 객체로 비교함 — MySQL의 JSON 컬럼은 저장 시 문자열을
    // 정규화(예: 콜론 뒤 공백 추가)하므로 원본 문자열과 그대로 비교하면 값이 같아도 다르게 판정될 수 있음
    private boolean isScheduleChanged(Routine routine, RoutineUpdateRequest request) {
        if (request.repeatType() != null
                && !request.repeatType().equals(routine.getRepeatType())) {
            return true;
        }
        if (request.repeatDays() != null
                && !request.repeatDays().equals(RepeatDays.fromJson(routine.getRepeatDays()))) {
            return true;
        }
        if (request.startsOn() != null && !request.startsOn().equals(routine.getStartsOn())) {
            return true;
        }
        return !Objects.equals(request.endsOn(), routine.getEndsOn());
    }

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

    private void validateStartsOn(Routine routine, LocalDate startsOn) {
        if (startsOn != null
                && !startsOn.equals(routine.getStartsOn())
                && startsOn.isBefore(LocalDate.now(KST))) {
            throw new BusinessException(RoutineErrorCode.ROUTINE_STARTS_ON_BEFORE_TODAY);
        }
    }

    private static final Set<String> VALID_WEEKDAY_TOKENS =
            Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    // 유형별 필수 서브필드가 없거나 유효하지 않으면 어느 날짜에도 매칭되지 않는 "죽은" 루틴이 생성되므로 여기서 막음
    private void validateRepeatSchedule(String repeatType, LocalDate startsOn, RepeatDays repeatDays) {
        if ("BIWEEKLY".equalsIgnoreCase(repeatType)) {
            if (startsOn == null) {
                throw new BusinessException(RoutineErrorCode.BIWEEKLY_REQUIRES_STARTS_ON);
            }
            List<String> daysOfWeek = repeatDays != null ? repeatDays.daysOfWeek() : null;
            if (daysOfWeek == null || daysOfWeek.isEmpty()
                    || !daysOfWeek.stream().allMatch(RoutineService::isValidWeekdayToken)) {
                throw new BusinessException(RoutineErrorCode.BIWEEKLY_REQUIRES_WEEKDAYS);
            }
        }
        if ("MONTHLY".equalsIgnoreCase(repeatType)) {
            Integer dayOfMonth = repeatDays != null ? repeatDays.dayOfMonth() : null;
            if (dayOfMonth == null || dayOfMonth < 1 || dayOfMonth > 31) {
                throw new BusinessException(RoutineErrorCode.MONTHLY_REQUIRES_DAY_OF_MONTH);
            }
        }
        if ("YEARLY".equalsIgnoreCase(repeatType)) {
            Integer month = repeatDays != null ? repeatDays.month() : null;
            Integer day = repeatDays != null ? repeatDays.day() : null;
            if (month == null || day == null || !isValidMonthDay(month, day)) {
                throw new BusinessException(RoutineErrorCode.YEARLY_REQUIRES_MONTH_AND_DAY);
            }
        }
    }

    private static boolean isValidWeekdayToken(String token) {
        return token != null && VALID_WEEKDAY_TOKENS.contains(token.toUpperCase());
    }

    // MonthDay는 2/29도 유효로 취급함(실제 매칭은 윤년에만 되고, 평년엔 RoutineRecurrence에서 자연 스킵)
    private static boolean isValidMonthDay(int month, int day) {
        try {
            MonthDay.of(month, day);
            return true;
        } catch (DateTimeException e) {
            return false;
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
