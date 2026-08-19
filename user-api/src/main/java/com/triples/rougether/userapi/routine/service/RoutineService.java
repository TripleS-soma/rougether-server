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
import com.triples.rougether.userapi.global.persistence.UniqueViolations;
import com.triples.rougether.userapi.house.support.HouseLinkValidator;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoutineService {

    private static final Logger log = LoggerFactory.getLogger(RoutineService.class);
    // V49 uk_routines_user_external — 임포트 경로에서 unique 위반을 다른 무결성 오류와 구분하는 기준
    private static final String EXTERNAL_REF_CONSTRAINT = "uk_routines_user_external";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RoutineRepository routineRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final HouseLinkValidator houseLinkValidator;

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
        return new RoutineListResponse(toResponses(routines));
    }

    @Transactional(readOnly = true)
    public RoutineResponse get(Long userId, Long routineId) {
        return toResponse(findOwned(userId, routineId));
    }

    @Transactional
    public RoutineResponse create(Long userId, RoutineCreateRequest request) {
        User user = userRepository.getReferenceById(userId);
        Category category = request.categoryId() != null
                ? findOwnedCategory(userId, request.categoryId()) : null;
        String repeatDays = request.repeatDays() != null ? request.repeatDays().toJson() : null;
        validateRepeatSchedule(request.repeatType(), request.startsOn(), request.repeatDays());
        LocalDate startsOn = resolveStartsOn(request.startsOn());
        validateDateRange(startsOn, request.endsOn());
        if (request.hasPartialExternalRef()) {
            throw new BusinessException(RoutineErrorCode.ROUTINE_EXTERNAL_REF_INCOMPLETE);
        }
        Routine routine = request.hasExternalRef()
                ? newImportedRoutine(userId, user, category, request, repeatDays, startsOn)
                : Routine.create(user, category, request.title(), request.authType(),
                        request.repeatType(), repeatDays, request.scheduledTime(),
                        startsOn, request.endsOn());
        if (request.houseMissionId() != null) {
            houseLinkValidator.validateMissionLink(userId, request.houseMissionId());
            routine.linkHouseMission(request.houseMissionId());
        }
        Routine saved = routine.hasExternalRef() ? saveImported(userId, routine) : routineRepository.save(routine);
        // 계보 루트를 자기 id로 지정(dirty → 트랜잭션 커밋 시 반영)
        saved.assignOriginToSelf();
        return RoutineResponse.from(saved);
    }

    // 기기 캘린더 반복 일정 임포트: (user, externalSource, externalId) 가 이미 있으면 409 — soft delete·옛 버전 row 도 포함해
    // 지운·스케줄을 바꾼 시리즈를 되살리지 않는다. 앞뒤 공백은 같은 참조로 본다
    private Routine newImportedRoutine(Long userId, User user, Category category, RoutineCreateRequest request,
                                       String repeatDays, LocalDate startsOn) {
        String externalSource = request.externalSource().trim();
        String externalId = request.externalId().trim();
        if (routineRepository.existsByUserIdAndExternalSourceAndExternalId(userId, externalSource, externalId)) {
            throw new BusinessException(RoutineErrorCode.ROUTINE_EXTERNAL_DUPLICATE);
        }
        return Routine.createImported(user, category, request.title(), request.authType(),
                request.repeatType(), repeatDays, request.scheduledTime(), startsOn, request.endsOn(),
                externalSource, externalId);
    }

    // flush 까지 해서 unique 위반을 이 자리에서 잡는다(트랜잭션은 예외로 롤백됨). 사전 조회를 통과한 동시 요청만 여기서 걸린다.
    // 단건 트랜잭션 전용 — 상위 @Transactional 에서 여러 건을 돌리며 409 를 잡고 계속 진행하면 커밋 시점에 UnexpectedRollbackException
    private Routine saveImported(Long userId, Routine routine) {
        try {
            return routineRepository.saveAndFlush(routine);
        } catch (DataIntegrityViolationException e) {
            if (!UniqueViolations.isViolationOf(e, EXTERNAL_REF_CONSTRAINT)) {
                // 중복이 아닌 무결성 오류를 409 로 위장하면 앱이 "이미 가져옴"으로 건너뛰어 조용히 유실된다
                throw e;
            }
            // 원인 메시지에는 externalId 원문이 실리므로 제약 이름만 남긴다
            log.warn("임포트 루틴 중복 저장 시도(unique 위반) userId={} source={} constraint={}",
                    userId, routine.getExternalSource(), EXTERNAL_REF_CONSTRAINT);
            throw new BusinessException(RoutineErrorCode.ROUTINE_EXTERNAL_DUPLICATE);
        }
    }

    // 응답의 외부 참조는 계보 원본(origin row)에서 읽는다 — 스케줄 수정으로 갈린 새 버전에는 복사하지 않기 때문(#317).
    // 이 row 에 값이 있으면 그대로, 없고 origin 이 다른 row 면 origin 을 조회. 목록은 origin id 를 모아 1회 배치 조회
    private RoutineResponse toResponse(Routine routine) {
        if (routine.hasExternalRef() || !isBranchedVersion(routine)) {
            return RoutineResponse.from(routine);
        }
        // 계보는 항상 같은 유저지만 소유자 스코프를 코드에 드러낸다(findByIdAndUserId 는 soft delete 행도 읽음)
        Routine origin = routineRepository.findByIdAndUserId(routine.getOriginRoutineId(), routine.getUser().getId())
                .filter(Routine::hasExternalRef)
                .orElse(null);
        return origin != null
                ? RoutineResponse.from(routine, origin.getExternalSource(), origin.getExternalId())
                : RoutineResponse.from(routine);
    }

    private List<RoutineResponse> toResponses(List<Routine> routines) {
        Set<Long> originIds = routines.stream()
                .filter(r -> !r.hasExternalRef() && isBranchedVersion(r))
                .map(Routine::getOriginRoutineId)
                .collect(Collectors.toSet());
        Map<Long, Routine> origins = originIds.isEmpty() ? Map.of()
                : routineRepository.findByUserIdAndIdInAndExternalIdIsNotNull(
                                routines.get(0).getUser().getId(), originIds).stream()
                        .collect(Collectors.toMap(Routine::getId, r -> r, (a, b) -> a, HashMap::new));
        return routines.stream()
                .map(r -> {
                    Routine origin = (!r.hasExternalRef() && isBranchedVersion(r)) ? origins.get(r.getOriginRoutineId()) : null;
                    return origin != null
                            ? RoutineResponse.from(r, origin.getExternalSource(), origin.getExternalId())
                            : RoutineResponse.from(r);
                })
                .toList();
    }

    // 스케줄 수정으로 갈린 버전인지(origin 이 자기 자신이 아님)
    private static boolean isBranchedVersion(Routine routine) {
        return routine.getOriginRoutineId() != null && !routine.getOriginRoutineId().equals(routine.getId());
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
        validateDateRange(effectiveStartsOn, request.endsOn());
        // 단체미션 연동은 null=기존 유지 — 연동 사실을 모르는 구버전 클라이언트의 수정 요청이
        // 연동을 지우지 않도록 categoryId(null=해제)와 다른 규칙. 해제는 전용 API(unlinkHouseMission)
        if (request.houseMissionId() != null) {
            houseLinkValidator.validateMissionLink(userId, request.houseMissionId());
        }

        // 반복 스케줄이 실제로 바뀌고, 경과한 날이 있는(created_at<오늘) 버전이면 새 버전으로 분기.
        // 옛 버전은 그대로 닫아(deleted_at) 과거 유효기간엔 남기고, 응답은 새 버전(새 id)
        if (isScheduleChanged(routine, request) && hasElapsedDay(routine)) {
            Routine newVersion = routine.copyAsNewVersion(category, request.title(),
                    request.authType(), request.repeatType(), repeatDays,
                    request.scheduledTime(), request.startsOn(), request.endsOn());
            if (request.houseMissionId() != null) {
                newVersion.linkHouseMission(request.houseMissionId());
            }
            routine.softDelete(Instant.now());
            return toResponse(routineRepository.save(newVersion));
        }

        // 제자리 수정(제목·카테고리·시각·인증 변경, 또는 오늘 생성분의 스케줄 변경) — 과거에도 반영됨.
        // categoryId null은 미분류로 해제함(scheduledTime·endsOn과 같은 규칙)
        routine.changeCategory(category);
        routine.update(request.title(), request.authType(), request.repeatType(),
                repeatDays, request.scheduledTime(), request.startsOn(),
                request.endsOn());
        if (request.houseMissionId() != null) {
            routine.linkHouseMission(request.houseMissionId());
        }
        return toResponse(routine);
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

    private void validateDateRange(LocalDate startsOn, LocalDate endsOn) {
        if (startsOn != null && endsOn != null && startsOn.isAfter(endsOn)) {
            throw new BusinessException(RoutineErrorCode.ROUTINE_STARTS_ON_AFTER_ENDS_ON);
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

    // 단체미션 연동 해제 - 수정 API 는 null=기존 유지 규칙이라 해제 수단을 별도 API 로 둔다.
    // 이미 미연동이어도 성공(멱등). 과거 자동 기여된 이력은 회수하지 않는다.
    @Transactional
    public void unlinkHouseMission(Long userId, Long routineId) {
        findOwned(userId, routineId).unlinkHouseMission();
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
