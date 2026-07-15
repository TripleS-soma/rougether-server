package com.triples.rougether.userapi.routine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.category.error.CategoryErrorCode;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.routine.dto.RepeatDays;
import com.triples.rougether.userapi.routine.dto.RoutineCreateRequest;
import java.util.List;
import com.triples.rougether.userapi.routine.dto.RoutineResponse;
import com.triples.rougether.userapi.routine.dto.RoutineUpdateRequest;
import com.triples.rougether.userapi.routine.error.RoutineErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class RoutineServiceIntegrationTest {

    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager em;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private RoutineService routineService;
    private Long userId;

    @BeforeEach
    void setUp() {
        routineService = new RoutineService(routineRepository, categoryRepository, userRepository);
        userId = userRepository.save(User.signUp()).getId();
    }

    @Test
    void 등록하면_status가_ACTIVE로_주입된다() {
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("아침 운동", null, AuthType.CHECK, null, null, null, null, null));

        assertThat(created.status()).isEqualTo(RoutineStatus.ACTIVE);
    }

    @Test
    void categoryId_미지정이면_미분류로_등록된다() {
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("물 마시기", null, AuthType.CHECK, null, null, null, null, null));

        assertThat(created.categoryId()).isNull();
    }

    @Test
    void 본인_카테고리를_지정하면_연결된다() {
        Long categoryId = persistCategory(userId, "운동");

        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("아침 운동", categoryId, AuthType.PHOTO,
                        "WEEKLY", new RepeatDays(List.of("MON")), LocalTime.of(7, 0), null, null));

        assertThat(created.categoryId()).isEqualTo(categoryId);
        assertThat(created.authType()).isEqualTo(AuthType.PHOTO);
        assertThat(created.repeatType()).isEqualTo("WEEKLY");
        // 객체 → 저장(JSON 문자열) → 객체 round-trip
        assertThat(created.repeatDays().daysOfWeek()).containsExactly("MON");
    }

    @Test
    void 타인_카테고리를_지정하면_CATEGORY_NOT_FOUND() {
        Long other = userRepository.save(User.signUp()).getId();
        Long othersCategory = persistCategory(other, "남의것");

        assertThatThrownBy(() -> routineService.create(userId,
                new RoutineCreateRequest("탈취", othersCategory, AuthType.CHECK, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CategoryErrorCode.CATEGORY_NOT_FOUND);
    }

    @Test
    void 타인_루틴_조회_수정_삭제는_ROUTINE_NOT_FOUND() {
        Long other = userRepository.save(User.signUp()).getId();
        RoutineResponse othersRoutine = routineService.create(other,
                new RoutineCreateRequest("남의 루틴", null, AuthType.CHECK, null, null, null, null, null));

        assertThatThrownBy(() -> routineService.get(userId, othersRoutine.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_NOT_FOUND);
        assertThatThrownBy(() -> routineService.update(userId, othersRoutine.id(),
                new RoutineUpdateRequest("탈취", null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_NOT_FOUND);
        assertThatThrownBy(() -> routineService.delete(userId, othersRoutine.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_NOT_FOUND);
    }

    @Test
    void 수정_시_타인_카테고리를_지정하면_CATEGORY_NOT_FOUND() {
        Long other = userRepository.save(User.signUp()).getId();
        Long othersCategory = persistCategory(other, "남의것");
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("원래", null, AuthType.CHECK, null, null, null, null, null));

        assertThatThrownBy(() -> routineService.update(userId, created.id(),
                new RoutineUpdateRequest(null, othersCategory, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CategoryErrorCode.CATEGORY_NOT_FOUND);
    }

    @Test
    void 수정은_authType_repeatType_null은_유지하고_categoryId는_변경한다() {
        Long firstCategory = persistCategory(userId, "운동");
        Long secondCategory = persistCategory(userId, "공부");
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("원래", firstCategory, AuthType.CHECK,
                        "DAILY", null, LocalTime.of(8, 0), null, null));

        RoutineResponse updated = routineService.update(userId, created.id(),
                new RoutineUpdateRequest("변경됨", secondCategory, null, null, null,
                        LocalTime.of(8, 0), null, null));

        assertThat(updated.title()).isEqualTo("변경됨");
        assertThat(updated.categoryId()).isEqualTo(secondCategory);
        assertThat(updated.authType()).isEqualTo(AuthType.CHECK);
        assertThat(updated.repeatType()).isEqualTo("DAILY");
        assertThat(updated.scheduledTime()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void scheduledTime과_endsOn에_null을_보내면_해제된다() {
        LocalDate today = LocalDate.now(KST);
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("운동", null, AuthType.CHECK, "DAILY", null,
                        LocalTime.of(7, 0), today, today.plusMonths(6)));

        RoutineResponse updated = routineService.update(userId, created.id(),
                new RoutineUpdateRequest("운동", null, null, null, null, null, null, null));

        assertThat(updated.scheduledTime()).isNull();
        assertThat(updated.endsOn()).isNull();
    }

    @Test
    void 삭제하면_soft_delete되고_목록에서_제외된다() {
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("삭제대상", null, AuthType.CHECK, null, null, null, null, null));

        routineService.delete(userId, created.id());
        em.flush();
        em.clear();

        Routine reloaded = routineRepository.findById(created.id()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
        assertThat(routineService.list(userId, null, null).items()).isEmpty();
        assertThatThrownBy(() -> routineService.get(userId, created.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_NOT_FOUND);
    }

    @Test
    void 루틴_응답은_소속_카테고리를_categoryId로만_담는다() {
        User owner = userRepository.findById(userId).orElseThrow();
        Category category = categoryRepository.save(
                Category.create(owner, "운동", "#FFAA00", "icon/run", 0, PrivacyScope.PRIVATE));
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("아침 운동", category.getId(), AuthType.CHECK,
                        null, null, LocalTime.of(7, 0), null, null));
        em.flush();
        em.clear();

        var items = routineService.list(userId, null, null).items();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).categoryId()).isEqualTo(category.getId());

        RoutineResponse single = routineService.get(userId, created.id());
        assertThat(single.categoryId()).isEqualTo(category.getId());
    }

    @Test
    void 목록은_categoryId와_status로_필터링한다() {
        Long category = persistCategory(userId, "운동");
        RoutineResponse inCategory = routineService.create(userId,
                new RoutineCreateRequest("분류됨", category, AuthType.CHECK, null, null,
                        LocalTime.of(6, 0), null, null));
        routineService.create(userId,
                new RoutineCreateRequest("미분류", null, AuthType.CHECK, null, null,
                        LocalTime.of(7, 0), null, null));

        assertThat(routineService.list(userId, category, null).items())
                .extracting(RoutineResponse::id).containsExactly(inCategory.id());
        assertThat(routineService.list(userId, null, RoutineStatus.ACTIVE).items()).hasSize(2);
        assertThat(routineService.list(userId, category, RoutineStatus.ACTIVE).items())
                .extracting(RoutineResponse::id).containsExactly(inCategory.id());
        assertThat(routineService.list(userId, null, null).items()).hasSize(2);
    }

    @Test
    void 스케줄_수정이고_경과분이면_새_버전으로_분기한다() {
        LocalDate startsOn = LocalDate.now(KST);
        LocalDate endsOn = startsOn.plusMonths(6);
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("운동", null, AuthType.CHECK, "WEEKLY",
                        new RepeatDays(List.of("MON")), LocalTime.of(7, 0),
                        startsOn, endsOn));
        Long oldId = created.id();
        backdateCreatedAt(oldId, 3);

        RoutineResponse updated = routineService.update(userId, oldId,
                new RoutineUpdateRequest(null, null, null, "WEEKLY", new RepeatDays(List.of("TUE")),
                        null, null, null));
        em.flush();
        em.clear();

        // 응답은 새 버전(새 id) — 스케줄 변경 반영
        assertThat(updated.id()).isNotEqualTo(oldId);
        assertThat(updated.repeatDays().daysOfWeek()).containsExactly("TUE");

        // 옛 버전: deleted_at 세팅, starts_on/ends_on·스케줄 불변
        Routine old = routineRepository.findById(oldId).orElseThrow();
        assertThat(old.getDeletedAt()).isNotNull();
        assertThat(old.getStartsOn()).isEqualTo(startsOn);
        assertThat(old.getEndsOn()).isEqualTo(endsOn);
        assertThat(old.getRepeatDays()).contains("MON");

        // 새 버전: origin 승계(옛 버전의 origin = oldId), 살아 있음
        Routine neo = routineRepository.findById(updated.id()).orElseThrow();
        assertThat(neo.getOriginRoutineId()).isEqualTo(oldId);
        assertThat(neo.getDeletedAt()).isNull();
    }

    @Test
    void 오늘_생성분은_스케줄을_수정해도_제자리다() {
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("운동", null, AuthType.CHECK, "DAILY", null,
                        LocalTime.of(7, 0), null, null));

        RoutineResponse updated = routineService.update(userId, created.id(),
                new RoutineUpdateRequest(null, null, null, "WEEKLY", new RepeatDays(List.of("MON")),
                        null, null, null));
        em.flush();
        em.clear();

        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.repeatType()).isEqualTo("WEEKLY");
        assertThat(routineRepository.findById(created.id()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void 레거시_포맷_WEEKLY_루틴을_동일_요일로_수정하면_제자리다() {
        // repeatDays에 dayOfMonth/month/day 키가 없는 옛 저장 포맷을 직접 재현(RepeatDays 신규 필드 추가 이전 데이터).
        // MySQL의 JSON 컬럼은 저장 시 텍스트를 정규화(콜론 뒤 공백 추가 등)하므로,
        // isScheduleChanged가 원본 문자열 그대로 비교했다면 동일 요일 재전송도 "변경"으로 오인했을 케이스
        User user = userRepository.findById(userId).orElseThrow();
        Routine legacy = Routine.create(user, null, "운동", AuthType.CHECK, "WEEKLY",
                "{\"daysOfWeek\":[\"MON\"]}", LocalTime.of(7, 0), null, null);
        Routine saved = routineRepository.save(legacy);
        saved.assignOriginToSelf();
        Long id = saved.getId();
        backdateCreatedAt(id, 3);

        RoutineResponse updated = routineService.update(userId, id,
                new RoutineUpdateRequest(null, null, null, "WEEKLY", new RepeatDays(List.of("MON")),
                        null, null, null));
        em.flush();
        em.clear();

        // 요일이 그대로면 파싱한 RepeatDays 객체가 동일하므로 스케줄 미변경 → 제자리 수정
        assertThat(updated.id()).isEqualTo(id);
        assertThat(routineRepository.findById(id).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void 제목만_수정이면_경과분이라도_제자리다() {
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("원래 제목", null, AuthType.CHECK, "DAILY", null,
                        LocalTime.of(7, 0), null, null));
        backdateCreatedAt(created.id(), 3);

        RoutineResponse updated = routineService.update(userId, created.id(),
                new RoutineUpdateRequest("바뀐 제목", null, null, null, null, null, null, null));
        em.flush();
        em.clear();

        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.title()).isEqualTo("바뀐 제목");
        assertThat(routineRepository.findById(created.id()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void 분기해도_목록은_계보당_현재_버전_하나만_정렬_위치_그대로_노출한다() {
        RoutineResponse a = routineService.create(userId,
                new RoutineCreateRequest("A", null, AuthType.CHECK, "DAILY", null,
                        LocalTime.of(7, 0), null, null));
        RoutineResponse b = routineService.create(userId,
                new RoutineCreateRequest("B", null, AuthType.CHECK, "DAILY", null,
                        LocalTime.of(8, 0), null, null));
        backdateCreatedAt(a.id(), 3);

        RoutineResponse a2 = routineService.update(userId, a.id(),
                new RoutineUpdateRequest(null, null, null, "WEEKLY", new RepeatDays(List.of("MON")),
                        null, null, null));
        em.flush();
        em.clear();

        // 옛 A는 닫혀 제외, 새 A는 origin(=A의 id) 기준 정렬이라 여전히 B 앞. 계보당 하나만 노출
        assertThat(routineService.list(userId, null, null).items())
                .extracting(RoutineResponse::id).containsExactly(a2.id(), b.id());
    }

    @Test
    void 시작일_미지정이면_생성일_오늘로_기본_지정된다() {
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("운동", null, AuthType.CHECK, "DAILY", null,
                        LocalTime.of(7, 0), null, null));

        assertThat(created.startsOn()).isEqualTo(LocalDate.now(KST));
    }

    @Test
    void 시작일을_오늘_이전으로_생성하면_STARTS_ON_BEFORE_TODAY() {
        LocalDate past = LocalDate.now(KST).minusDays(1);

        assertThatThrownBy(() -> routineService.create(userId,
                new RoutineCreateRequest("운동", null, AuthType.CHECK, "DAILY", null,
                        LocalTime.of(7, 0), past, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_STARTS_ON_BEFORE_TODAY);
    }

    @Test
    void 시작일을_오늘_이전으로_수정하면_STARTS_ON_BEFORE_TODAY() {
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("운동", null, AuthType.CHECK, "DAILY", null,
                        LocalTime.of(7, 0), null, null));
        LocalDate past = LocalDate.now(KST).minusDays(1);

        assertThatThrownBy(() -> routineService.update(userId, created.id(),
                new RoutineUpdateRequest(null, null, null, null, null, null, past, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_STARTS_ON_BEFORE_TODAY);
    }

    @Test
    void 기존_과거_시작일을_그대로_재전송하면_통과한다() {
        // 어제 시작해 이미 저장된(=한번 통과한) 시작일이, 하루 지나 과거가 된 상태를 도메인 엔티티로 직접 모사
        User owner = userRepository.findById(userId).orElseThrow();
        LocalDate startsOn = LocalDate.now(KST).minusDays(1);
        Long id = routineRepository.save(Routine.create(owner, null, "운동", AuthType.CHECK,
                "DAILY", null, LocalTime.of(7, 0), startsOn, null)).getId();
        em.flush();
        em.clear();

        // 수정 없이 그대로 재전송(no-op) → 멱등하게 통과해야 함
        RoutineResponse updated = routineService.update(userId, id,
                new RoutineUpdateRequest("바뀐 제목", null, null, null, null, null, startsOn, null));

        assertThat(updated.startsOn()).isEqualTo(startsOn);
        assertThat(updated.title()).isEqualTo("바뀐 제목");
    }

    @Test
    void BIWEEKLY_등록_시_startsOn이_있으면_성공한다() {
        LocalDate startsOn = LocalDate.now(KST);
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("격주 운동", null, AuthType.CHECK, "BIWEEKLY",
                        new RepeatDays(List.of("MON")), null, startsOn, null));

        assertThat(created.repeatType()).isEqualTo("BIWEEKLY");
        assertThat(created.startsOn()).isEqualTo(startsOn);
    }

    @Test
    void BIWEEKLY_등록_시_startsOn이_없으면_BIWEEKLY_REQUIRES_STARTS_ON() {
        assertThatThrownBy(() -> routineService.create(userId,
                new RoutineCreateRequest("격주 운동", null, AuthType.CHECK, "BIWEEKLY",
                        new RepeatDays(List.of("MON")), null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.BIWEEKLY_REQUIRES_STARTS_ON);
    }

    @Test
    void BIWEEKLY_등록_시_daysOfWeek가_없으면_BIWEEKLY_REQUIRES_WEEKDAYS() {
        assertThatThrownBy(() -> routineService.create(userId,
                new RoutineCreateRequest("격주 운동", null, AuthType.CHECK, "BIWEEKLY",
                        null, null, LocalDate.of(2026, 7, 13), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.BIWEEKLY_REQUIRES_WEEKDAYS);
    }

    @Test
    void BIWEEKLY_등록_시_daysOfWeek가_빈_목록이면_BIWEEKLY_REQUIRES_WEEKDAYS() {
        assertThatThrownBy(() -> routineService.create(userId,
                new RoutineCreateRequest("격주 운동", null, AuthType.CHECK, "BIWEEKLY",
                        new RepeatDays(List.of()), null, LocalDate.of(2026, 7, 13), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.BIWEEKLY_REQUIRES_WEEKDAYS);
    }

    @Test
    void BIWEEKLY_등록_시_daysOfWeek에_유효하지_않은_요일이_있으면_BIWEEKLY_REQUIRES_WEEKDAYS() {
        assertThatThrownBy(() -> routineService.create(userId,
                new RoutineCreateRequest("격주 운동", null, AuthType.CHECK, "BIWEEKLY",
                        new RepeatDays(List.of("XXX")), null, LocalDate.of(2026, 7, 13), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.BIWEEKLY_REQUIRES_WEEKDAYS);
    }

    @Test
    void MONTHLY_YEARLY_등록이_정상_동작한다() {
        RoutineResponse monthly = routineService.create(userId,
                new RoutineCreateRequest("월세 납부", null, AuthType.CHECK, "MONTHLY",
                        new RepeatDays(null, 15, null, null), null, null, null));
        RoutineResponse yearly = routineService.create(userId,
                new RoutineCreateRequest("생일", null, AuthType.CHECK, "YEARLY",
                        new RepeatDays(null, null, 7, 12), null, null, null));

        assertThat(monthly.repeatType()).isEqualTo("MONTHLY");
        assertThat(monthly.repeatDays().dayOfMonth()).isEqualTo(15);
        assertThat(yearly.repeatType()).isEqualTo("YEARLY");
        assertThat(yearly.repeatDays().month()).isEqualTo(7);
        assertThat(yearly.repeatDays().day()).isEqualTo(12);
    }

    @Test
    void MONTHLY_등록_시_dayOfMonth가_없으면_MONTHLY_REQUIRES_DAY_OF_MONTH() {
        assertThatThrownBy(() -> routineService.create(userId,
                new RoutineCreateRequest("월세 납부", null, AuthType.CHECK, "MONTHLY",
                        null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.MONTHLY_REQUIRES_DAY_OF_MONTH);
    }

    @Test
    void MONTHLY_등록_시_dayOfMonth가_범위를_벗어나면_MONTHLY_REQUIRES_DAY_OF_MONTH() {
        assertThatThrownBy(() -> routineService.create(userId,
                new RoutineCreateRequest("월세 납부", null, AuthType.CHECK, "MONTHLY",
                        new RepeatDays(null, 0, null, null), null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.MONTHLY_REQUIRES_DAY_OF_MONTH);
    }

    @Test
    void YEARLY_등록_시_month나_day가_없으면_YEARLY_REQUIRES_MONTH_AND_DAY() {
        assertThatThrownBy(() -> routineService.create(userId,
                new RoutineCreateRequest("생일", null, AuthType.CHECK, "YEARLY",
                        new RepeatDays(null, null, 7, null), null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.YEARLY_REQUIRES_MONTH_AND_DAY);
    }

    @Test
    void YEARLY_등록_시_month가_범위를_벗어나면_YEARLY_REQUIRES_MONTH_AND_DAY() {
        assertThatThrownBy(() -> routineService.create(userId,
                new RoutineCreateRequest("생일", null, AuthType.CHECK, "YEARLY",
                        new RepeatDays(null, null, 13, 12), null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.YEARLY_REQUIRES_MONTH_AND_DAY);
    }

    @Test
    void YEARLY_등록_시_실존하지_않는_날짜_조합이면_YEARLY_REQUIRES_MONTH_AND_DAY() {
        assertThatThrownBy(() -> routineService.create(userId,
                new RoutineCreateRequest("생일", null, AuthType.CHECK, "YEARLY",
                        new RepeatDays(null, null, 4, 31), null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.YEARLY_REQUIRES_MONTH_AND_DAY);
    }

    @Test
    void YEARLY_등록_시_2월29일은_윤년_여부와_무관하게_허용된다() {
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("생일", null, AuthType.CHECK, "YEARLY",
                        new RepeatDays(null, null, 2, 29), null, null, null));

        assertThat(created.repeatDays().month()).isEqualTo(2);
        assertThat(created.repeatDays().day()).isEqualTo(29);
    }

    @Test
    void 수정으로_repeatType이_MONTHLY로_바뀌는데_dayOfMonth가_결과적으로_없으면_실패한다() {
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("운동", null, AuthType.CHECK, "DAILY", null,
                        LocalTime.of(7, 0), null, null));

        assertThatThrownBy(() -> routineService.update(userId, created.id(),
                new RoutineUpdateRequest(null, null, null, "MONTHLY", null,
                        null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.MONTHLY_REQUIRES_DAY_OF_MONTH);
    }

    @Test
    void 수정으로_repeatType이_BIWEEKLY로_바뀌는데_startsOn이_결과적으로_없으면_실패한다() {
        User owner = userRepository.findById(userId).orElseThrow();
        Long id = routineRepository.save(Routine.create(owner, null, "운동", AuthType.CHECK,
                "DAILY", null, LocalTime.of(7, 0), null, null)).getId();
        em.flush();
        em.clear();

        assertThatThrownBy(() -> routineService.update(userId, id,
                new RoutineUpdateRequest(null, null, null, "BIWEEKLY", new RepeatDays(List.of("MON")),
                        null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.BIWEEKLY_REQUIRES_STARTS_ON);
    }

    @Test
    void 수정으로_repeatType이_BIWEEKLY로_바뀌어도_기존_startsOn이_있으면_성공한다() {
        LocalDate startsOn = LocalDate.now(KST);
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("운동", null, AuthType.CHECK, "DAILY", null,
                        LocalTime.of(7, 0), startsOn, null));

        RoutineResponse updated = routineService.update(userId, created.id(),
                new RoutineUpdateRequest(null, null, null, "BIWEEKLY", new RepeatDays(List.of("MON")),
                        null, null, null));

        assertThat(updated.repeatType()).isEqualTo("BIWEEKLY");
        assertThat(updated.startsOn()).isEqualTo(startsOn);
    }

    @Test
    void 시작일_미지정_상태로_종료일에_과거를_보내면_STARTS_ON_AFTER_ENDS_ON() {
        LocalDate yesterday = LocalDate.now(KST).minusDays(1);

        assertThatThrownBy(() -> routineService.create(userId,
                new RoutineCreateRequest("운동", null, AuthType.CHECK, "DAILY", null,
                        LocalTime.of(7, 0), null, yesterday)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_STARTS_ON_AFTER_ENDS_ON);
    }

    @Test
    void 시작일이_종료일보다_늦으면_STARTS_ON_AFTER_ENDS_ON() {
        LocalDate startsOn = LocalDate.now(KST).plusDays(10);
        LocalDate endsOn = LocalDate.now(KST).plusDays(5);

        assertThatThrownBy(() -> routineService.create(userId,
                new RoutineCreateRequest("운동", null, AuthType.CHECK, "DAILY", null,
                        LocalTime.of(7, 0), startsOn, endsOn)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_STARTS_ON_AFTER_ENDS_ON);
    }

    @Test
    void 수정으로_종료일을_기존_시작일보다_앞으로_당기면_STARTS_ON_AFTER_ENDS_ON() {
        LocalDate startsOn = LocalDate.now(KST).plusDays(10);
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("운동", null, AuthType.CHECK, "DAILY", null,
                        LocalTime.of(7, 0), startsOn, null));
        LocalDate earlierEndsOn = LocalDate.now(KST).plusDays(5);

        assertThatThrownBy(() -> routineService.update(userId, created.id(),
                new RoutineUpdateRequest(null, null, null, null, null, null, null, earlierEndsOn)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_STARTS_ON_AFTER_ENDS_ON);
    }

    private Long persistCategory(Long ownerId, String name) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        return categoryRepository.save(
                Category.create(owner, name, null, null, 0, PrivacyScope.PRIVATE)).getId();
    }

    // created_at은 auditing이 now로 채우고 updatable=false라 JPA로 못 바꿈 → 네이티브로 N일 과거로 당김
    private void backdateCreatedAt(Long routineId, int days) {
        em.flush();
        em.createNativeQuery(
                "update routines set created_at = created_at - interval " + days
                        + " day where id = " + routineId).executeUpdate();
        em.clear();
    }
}
