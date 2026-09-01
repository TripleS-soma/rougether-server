package com.triples.rougether.userapi.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.recommendation.entity.RecommendationStatus;
import com.triples.rougether.domain.recommendation.entity.RecommendationType;
import com.triples.rougether.domain.recommendation.entity.RoutineRecommendation;
import com.triples.rougether.domain.recommendation.repository.RoutineRecommendationRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.house.support.HouseLinkValidator;
import com.triples.rougether.userapi.recommendation.error.RecommendationErrorCode;
import com.triples.rougether.userapi.routine.dto.RepeatDays;
import com.triples.rougether.userapi.routine.dto.RoutineResponse;
import com.triples.rougether.userapi.routine.dto.RoutineUpdateRequest;
import com.triples.rougether.userapi.routine.service.RoutineService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

// 조정 추천 수락/무시(#329). 수락이 루틴 수정 경로를 재사용해 시간버전 분기·필드 보존이 일어나는지와
// 검증 5종의 순서를 실제 DB 로 본다. 위험영역(루틴 쓰기) — 분기·상태 갱신 정합까지 확인.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class RecommendationCommandServiceIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String PROPOSAL_JSON = "{\"repeatType\":\"WEEKLY\",\"daysOfWeek\":[\"MON\",\"FRI\"]}";

    @Autowired
    private RoutineRecommendationRepository recommendationRepository;
    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private HouseRepository houseRepository;
    @Autowired
    private HouseMissionRepository houseMissionRepository;
    @Autowired
    private HouseMemberRepository houseMemberRepository;

    @PersistenceContext
    private EntityManager em;

    private RecommendationCommandService service;
    private RoutineService routineService;
    private Instant now;
    private User user;
    private Long userId;

    @BeforeEach
    void setUp() {
        // TIMESTAMP 컬럼은 초 단위라 DB 왕복 후 값 비교를 위해 나노초를 버림
        now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        routineService = new RoutineService(routineRepository, categoryRepository, userRepository,
                new HouseLinkValidator(houseRepository, houseMissionRepository, houseMemberRepository));
        service = new RecommendationCommandService(recommendationRepository, routineRepository, routineService,
                JsonMapper.builder().build(), Clock.fixed(now, KST));
        user = userRepository.save(User.signUp());
        userId = user.getId();
    }

    @Test
    void 수락은_제안_스케줄만_적용해_버전을_분기하고_나머지_필드를_보존한다() {
        Category category = categoryRepository.save(
                Category.create(user, "운동", "#FFFFFF", null, 0, PrivacyScope.PRIVATE));
        Routine routine = persistWeekly("아침 러닝", category, LocalTime.of(7, 0), LocalDate.now(KST).plusMonths(1));
        backdateCreatedAt(routine.getId(), 21);
        RoutineRecommendation recommendation = persistRecommendation(routine, now.plus(Duration.ofDays(3)));

        RoutineResponse applied = service.accept(userId, recommendation.getId());

        // 스케줄 변경이라 새 버전으로 분기 — 응답 id 가 바뀌고 옛 버전은 닫힌다
        assertThat(applied.id()).isNotEqualTo(routine.getId());
        em.flush();
        em.clear();
        Routine oldVersion = routineRepository.findById(routine.getId()).orElseThrow();
        assertThat(oldVersion.getDeletedAt()).isNotNull();
        Routine newVersion = routineRepository.findById(applied.id()).orElseThrow();
        assertThat(newVersion.getOriginRoutineId()).isEqualTo(routine.getOriginRoutineId());
        assertThat(newVersion.getRepeatType()).isEqualTo("WEEKLY");
        assertThat(RepeatDays.fromJson(newVersion.getRepeatDays()).daysOfWeek()).containsExactly("MON", "FRI");
        // categoryId·scheduledTime·endsOn 은 "null = 해제" 규칙이라 현재값을 실어 보존됨을 확인
        assertThat(newVersion.getCategory().getId()).isEqualTo(category.getId());
        assertThat(newVersion.getScheduledTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(newVersion.getEndsOn()).isEqualTo(routine.getEndsOn());
        assertThat(newVersion.getTitle()).isEqualTo("아침 러닝");
        // 추천 상태는 같은 트랜잭션에서 ACCEPTED + 적용 버전 기록
        RoutineRecommendation acceptedRecommendation =
                recommendationRepository.findById(recommendation.getId()).orElseThrow();
        assertThat(acceptedRecommendation.getStatus()).isEqualTo(RecommendationStatus.ACCEPTED);
        assertThat(acceptedRecommendation.getActedAt()).isEqualTo(now);
        assertThat(acceptedRecommendation.getAppliedRoutineId()).isEqualTo(applied.id());
    }

    @Test
    void 타인의_추천은_존재해도_404로_숨긴다() {
        Routine routine = persistWeekly("아침 러닝", null, null, null);
        backdateCreatedAt(routine.getId(), 21);
        RoutineRecommendation recommendation = persistRecommendation(routine, now.plus(Duration.ofDays(3)));
        User other = userRepository.save(User.signUp());

        assertThatThrownBy(() -> service.accept(other.getId(), recommendation.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.RECOMMENDATION_NOT_FOUND);
        assertThatThrownBy(() -> service.dismiss(other.getId(), recommendation.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.RECOMMENDATION_NOT_FOUND);
    }

    @Test
    void 이미_수락하거나_무시한_추천은_다시_처리할_수_없다() {
        Routine routine = persistWeekly("아침 러닝", null, null, null);
        backdateCreatedAt(routine.getId(), 21);
        RoutineRecommendation recommendation = persistRecommendation(routine, now.plus(Duration.ofDays(3)));

        service.accept(userId, recommendation.getId());

        assertThatThrownBy(() -> service.accept(userId, recommendation.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.RECOMMENDATION_ALREADY_HANDLED);
        assertThatThrownBy(() -> service.dismiss(userId, recommendation.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.RECOMMENDATION_ALREADY_HANDLED);
    }

    @Test
    void 만료된_추천의_수락은_거부한다() {
        Routine routine = persistWeekly("아침 러닝", null, null, null);
        backdateCreatedAt(routine.getId(), 21);
        // 만료 + 루틴 삭제가 겹치면 만료를 먼저 알린다(검증 순서)
        routine.softDelete(now);
        routineRepository.save(routine);
        RoutineRecommendation recommendation = persistRecommendation(routine, now.minus(Duration.ofHours(1)));

        assertThatThrownBy(() -> service.accept(userId, recommendation.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.RECOMMENDATION_EXPIRED);
    }

    @Test
    void 만료된_추천의_무시도_거부한다() {
        Routine routine = persistWeekly("아침 러닝", null, null, null);
        backdateCreatedAt(routine.getId(), 21);
        RoutineRecommendation recommendation = persistRecommendation(routine, now.minus(Duration.ofHours(1)));

        // accept 와 대칭(#355) - 만료 카드는 두 버튼 모두 같은 409 를 받아 재조회로 수렴한다
        assertThatThrownBy(() -> service.dismiss(userId, recommendation.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.RECOMMENDATION_EXPIRED);
        // 상태는 ACTIVE 그대로 - 만료는 lazy 종결이라 DISMISSED 로 덮지 않는다(admin 퍼널 만료 집계 유지)
        assertThat(recommendationRepository.findById(recommendation.getId()).orElseThrow().getStatus())
                .isEqualTo(RecommendationStatus.ACTIVE);
    }

    @Test
    void 대상_루틴이_삭제된_추천의_수락은_거부한다() {
        Routine routine = persistWeekly("아침 러닝", null, null, null);
        backdateCreatedAt(routine.getId(), 21);
        RoutineRecommendation recommendation = persistRecommendation(routine, now.plus(Duration.ofDays(3)));
        routine.softDelete(now);
        routineRepository.save(routine);

        assertThatThrownBy(() -> service.accept(userId, recommendation.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.RECOMMENDATION_ROUTINE_DELETED);
        // 거부돼도 추천은 ACTIVE 그대로(상태를 소모하지 않음)
        assertThat(recommendationRepository.findById(recommendation.getId()).orElseThrow().getStatus())
                .isEqualTo(RecommendationStatus.ACTIVE);
    }

    @Test
    void 추천_생성_뒤_스케줄을_먼저_수정했으면_stale로_거부한다() {
        Routine routine = persistWeekly("아침 러닝", null, null, null);
        backdateCreatedAt(routine.getId(), 21);
        RoutineRecommendation recommendation = persistRecommendation(routine, now.plus(Duration.ofDays(3)));
        // 사용자가 직접 요일을 바꿔 새 버전으로 분기(생성 시점 대상 버전과 달라짐)
        routineService.update(userId, routine.getId(), new RoutineUpdateRequest(null, null, null,
                "WEEKLY", new RepeatDays(List.of("TUE", "THU")), null, null, null));

        assertThatThrownBy(() -> service.accept(userId, recommendation.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.RECOMMENDATION_STALE);
    }

    @Test
    void 무시는_상태만_바꾸고_루틴을_건드리지_않는다() {
        Routine routine = persistWeekly("아침 러닝", null, null, null);
        backdateCreatedAt(routine.getId(), 21);
        RoutineRecommendation recommendation = persistRecommendation(routine, now.plus(Duration.ofDays(3)));

        service.dismiss(userId, recommendation.getId());

        RoutineRecommendation dismissed = recommendationRepository.findById(recommendation.getId()).orElseThrow();
        assertThat(dismissed.getStatus()).isEqualTo(RecommendationStatus.DISMISSED);
        assertThat(dismissed.getActedAt()).isEqualTo(now);
        assertThat(dismissed.getAppliedRoutineId()).isNull();
        Routine untouched = routineRepository.findById(routine.getId()).orElseThrow();
        assertThat(untouched.getDeletedAt()).isNull();
        assertThat(RepeatDays.fromJson(untouched.getRepeatDays()).daysOfWeek())
                .containsExactly("MON", "WED", "FRI");
    }

    // ---------- fixtures ----------

    private Routine persistWeekly(String title, Category category, LocalTime scheduledTime, LocalDate endsOn) {
        Routine routine = routineRepository.save(Routine.create(user, category, title, AuthType.CHECK,
                "WEEKLY", "{\"daysOfWeek\":[\"MON\",\"WED\",\"FRI\"]}", scheduledTime, null, endsOn));
        routine.assignOriginToSelf();
        return routineRepository.save(routine);
    }

    private RoutineRecommendation persistRecommendation(Routine routine, Instant expiresAt) {
        return recommendationRepository.save(RoutineRecommendation.rule(user, routine.getOriginRoutineId(),
                routine.getId(), RecommendationType.ADJUST_DAYS, PROPOSAL_JSON,
                "『아침 러닝』 수요일을 빼 보면 어떨까요?", expiresAt));
    }

    // created_at은 auditing이 now로 채우고 updatable=false라 JPA로 못 바꿈 → 네이티브로 과거로 당김
    // (당일 생성분의 스케줄 변경은 제자리 수정이라, 버전 분기를 태우려면 경과한 날이 있어야 함)
    private void backdateCreatedAt(Long routineId, int days) {
        em.flush();
        em.createNativeQuery("update routines set created_at = created_at - interval " + days
                + " day where id = " + routineId).executeUpdate();
        em.clear();
    }
}
