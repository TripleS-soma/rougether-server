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
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("운동", null, AuthType.CHECK, "DAILY", null,
                        LocalTime.of(7, 0), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));

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
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("운동", null, AuthType.CHECK, "WEEKLY",
                        new RepeatDays(List.of("MON")), LocalTime.of(7, 0),
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
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
        assertThat(old.getStartsOn()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(old.getEndsOn()).isEqualTo(LocalDate.of(2026, 12, 31));
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
    void BIWEEKLY_등록_시_startsOn이_있으면_성공한다() {
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("격주 운동", null, AuthType.CHECK, "BIWEEKLY",
                        new RepeatDays(List.of("MON")), null, LocalDate.of(2026, 7, 13), null));

        assertThat(created.repeatType()).isEqualTo("BIWEEKLY");
        assertThat(created.startsOn()).isEqualTo(LocalDate.of(2026, 7, 13));
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
    void 수정으로_repeatType이_BIWEEKLY로_바뀌는데_startsOn이_결과적으로_없으면_실패한다() {
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("운동", null, AuthType.CHECK, "DAILY", null,
                        LocalTime.of(7, 0), null, null));

        assertThatThrownBy(() -> routineService.update(userId, created.id(),
                new RoutineUpdateRequest(null, null, null, "BIWEEKLY", new RepeatDays(List.of("MON")),
                        null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.BIWEEKLY_REQUIRES_STARTS_ON);
    }

    @Test
    void 수정으로_repeatType이_BIWEEKLY로_바뀌어도_기존_startsOn이_있으면_성공한다() {
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("운동", null, AuthType.CHECK, "DAILY", null,
                        LocalTime.of(7, 0), LocalDate.of(2026, 7, 13), null));

        RoutineResponse updated = routineService.update(userId, created.id(),
                new RoutineUpdateRequest(null, null, null, "BIWEEKLY", new RepeatDays(List.of("MON")),
                        null, null, null));

        assertThat(updated.repeatType()).isEqualTo("BIWEEKLY");
        assertThat(updated.startsOn()).isEqualTo(LocalDate.of(2026, 7, 13));
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
