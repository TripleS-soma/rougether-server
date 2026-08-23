package com.triples.rougether.userapi.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.recommendation.entity.RecommendationType;
import com.triples.rougether.domain.recommendation.entity.RoutineRecommendation;
import com.triples.rougether.domain.recommendation.repository.RoutineRecommendationRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.house.support.HouseLinkValidator;
import com.triples.rougether.userapi.recommendation.dto.RecommendationItem;
import com.triples.rougether.userapi.recommendation.dto.RecommendationListResponse;
import com.triples.rougether.userapi.routine.dto.RepeatDays;
import com.triples.rougether.userapi.routine.dto.RoutineUpdateRequest;
import com.triples.rougether.userapi.routine.service.RoutineService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

// 조정 추천 목록(#329). 만료·루틴 삭제·stale 은 상태 전이 없이 조회에서 lazy 로 걸러지는지, 소유권 스코프가
// 지켜지는지를 실제 DB 로 본다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class RecommendationQueryServiceIntegrationTest {

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

    private RecommendationQueryService service;
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
        service = new RecommendationQueryService(recommendationRepository, routineRepository,
                JsonMapper.builder().build(), Clock.fixed(now, KST));
        user = userRepository.save(User.signUp());
        userId = user.getId();
    }

    @Test
    void 활성_추천을_최신_생성순으로_필드까지_채워_반환한다() {
        Routine older = persistWeekly("아침 러닝");
        Routine newer = persistWeekly("저녁 독서");
        RoutineRecommendation first = persistRecommendation(older, now.plus(Duration.ofDays(3)));
        RoutineRecommendation second = persistRecommendation(newer, now.plus(Duration.ofDays(3)));

        RecommendationListResponse response = service.getMyRecommendations(userId);

        assertThat(response.items()).hasSize(2);
        // createdAt 동률이어도 id 내림차순 타이브레이크로 나중 저장분이 앞
        assertThat(response.items()).extracting(RecommendationItem::recommendationId)
                .containsExactly(second.getId(), first.getId());
        RecommendationItem item = response.items().getFirst();
        assertThat(item.type()).isEqualTo(RecommendationType.ADJUST_DAYS);
        assertThat(item.message()).contains("수요일");
        assertThat(item.routineId()).isEqualTo(newer.getId());
        assertThat(item.originRoutineId()).isEqualTo(newer.getOriginRoutineId());
        assertThat(item.routineTitle()).isEqualTo("저녁 독서");
        assertThat(item.proposal().repeatType()).isEqualTo("WEEKLY");
        assertThat(item.proposal().daysOfWeek()).containsExactly("MON", "FRI");
        assertThat(item.expiresAt()).isEqualTo(now.plus(Duration.ofDays(3)));
        assertThat(item.createdAt()).isNotNull();
    }

    @Test
    void 만료됐거나_이미_처리된_추천은_목록에서_뺀다() {
        Routine expired = persistWeekly("만료");
        persistRecommendation(expired, now.minus(Duration.ofHours(1)));
        Routine dismissed = persistWeekly("무시됨");
        RoutineRecommendation dismissedRecommendation =
                persistRecommendation(dismissed, now.plus(Duration.ofDays(3)));
        dismissedRecommendation.dismiss(now);
        Routine accepted = persistWeekly("수락됨");
        RoutineRecommendation acceptedRecommendation =
                persistRecommendation(accepted, now.plus(Duration.ofDays(3)));
        acceptedRecommendation.accept(now, accepted.getId());
        Routine active = persistWeekly("활성");
        RoutineRecommendation activeRecommendation = persistRecommendation(active, now.plus(Duration.ofDays(3)));

        RecommendationListResponse response = service.getMyRecommendations(userId);

        assertThat(response.items()).extracting(RecommendationItem::recommendationId)
                .containsExactly(activeRecommendation.getId());
    }

    @Test
    void 루틴이_삭제됐거나_생성_뒤_스케줄이_수정된_추천은_목록에서_뺀다() {
        // 대상 루틴 삭제
        Routine deleted = persistWeekly("삭제됨");
        persistRecommendation(deleted, now.plus(Duration.ofDays(3)));
        deleted.softDelete(now);
        routineRepository.save(deleted);
        // 생성 뒤 사용자가 스케줄을 직접 수정(버전 분기 → 생성 시점 대상 버전과 불일치)
        Routine edited = persistWeekly("수정됨");
        backdateCreatedAt(edited.getId(), 21);
        persistRecommendation(edited, now.plus(Duration.ofDays(3)));
        routineService.update(userId, edited.getId(), new RoutineUpdateRequest(null, null, null,
                "WEEKLY", new RepeatDays(List.of("TUE", "THU")), null, null, null));

        RecommendationListResponse response = service.getMyRecommendations(userId);

        assertThat(response.items()).isEmpty();
        // lazy 판정이라 상태는 그대로 ACTIVE(지표에서 무반응 종결로 집계)
        assertThat(recommendationRepository.findAll())
                .allSatisfy(recommendation -> assertThat(recommendation.getStatus().name()).isEqualTo("ACTIVE"));
    }

    @Test
    void 제자리_수정된_제목은_현재_버전_값으로_노출되고_추천은_유효하다() {
        Routine routine = persistWeekly("옛 제목");
        RoutineRecommendation recommendation = persistRecommendation(routine, now.plus(Duration.ofDays(3)));
        // 제목 변경은 제자리 수정(버전 불변) — stale 아님
        routineService.update(userId, routine.getId(), new RoutineUpdateRequest("새 제목", null, null,
                null, null, null, null, null));

        RecommendationListResponse response = service.getMyRecommendations(userId);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().recommendationId()).isEqualTo(recommendation.getId());
        assertThat(response.items().getFirst().routineTitle()).isEqualTo("새 제목");
    }

    @Test
    void 타인의_추천은_보이지_않는다() {
        Routine mine = persistWeekly("내 루틴");
        RoutineRecommendation mineRecommendation = persistRecommendation(mine, now.plus(Duration.ofDays(3)));
        User other = userRepository.save(User.signUp());
        Routine othersRoutine = routineRepository.save(Routine.create(other, null, "남의 루틴", AuthType.CHECK,
                "WEEKLY", "{\"daysOfWeek\":[\"MON\",\"WED\"]}", null, null, null));
        othersRoutine.assignOriginToSelf();
        routineRepository.save(othersRoutine);
        recommendationRepository.save(RoutineRecommendation.rule(other, othersRoutine.getOriginRoutineId(),
                othersRoutine.getId(), RecommendationType.ADJUST_DAYS, PROPOSAL_JSON, "남의 추천",
                now.plus(Duration.ofDays(3))));

        RecommendationListResponse response = service.getMyRecommendations(userId);

        assertThat(response.items()).extracting(RecommendationItem::recommendationId)
                .containsExactly(mineRecommendation.getId());
    }

    // ---------- fixtures ----------

    private Routine persistWeekly(String title) {
        Routine routine = routineRepository.save(Routine.create(user, null, title, AuthType.CHECK,
                "WEEKLY", "{\"daysOfWeek\":[\"MON\",\"WED\",\"FRI\"]}", null, null, null));
        routine.assignOriginToSelf();
        return routineRepository.save(routine);
    }

    private RoutineRecommendation persistRecommendation(Routine routine, Instant expiresAt) {
        return recommendationRepository.save(RoutineRecommendation.rule(user, routine.getOriginRoutineId(),
                routine.getId(), RecommendationType.ADJUST_DAYS, PROPOSAL_JSON,
                "수요일 수행이 3주 연속 실패했어요.", expiresAt));
    }

    // created_at은 auditing이 now로 채우고 updatable=false라 JPA로 못 바꿈 → 네이티브로 과거로 당김
    private void backdateCreatedAt(Long routineId, int days) {
        em.flush();
        em.createNativeQuery("update routines set created_at = created_at - interval " + days
                + " day where id = " + routineId).executeUpdate();
        em.clear();
    }
}
