package com.triples.rougether.userapi.routine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.house.support.HouseLinkValidator;
import com.triples.rougether.userapi.routine.dto.RepeatDays;
import com.triples.rougether.userapi.routine.dto.RoutineCreateRequest;
import com.triples.rougether.userapi.routine.dto.RoutineResponse;
import com.triples.rougether.userapi.routine.dto.RoutineUpdateRequest;
import com.triples.rougether.userapi.routine.error.RoutineErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

// 기기 캘린더 반복 일정 임포트 외부 참조 (#317): 중복 방지·버전 분기·응답 origin 해석
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class RoutineExternalRefIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String SOURCE = "GOOGLE_CALENDAR";

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

    private RoutineService service;
    private Long userId;

    @BeforeEach
    void setUp() {
        service = serviceWith(routineRepository);
        userId = userRepository.save(User.signUp()).getId();
    }

    private RoutineService serviceWith(RoutineRepository repository) {
        return new RoutineService(repository, categoryRepository, userRepository,
                new HouseLinkValidator(houseRepository, houseMissionRepository, houseMemberRepository));
    }

    @Test
    void 외부_참조를_함께_보내면_저장되고_응답에_노출된다() {
        RoutineResponse response = service.create(userId, imported("PT", "series-1"));

        assertThat(response.externalSource()).isEqualTo(SOURCE);
        assertThat(response.externalId()).isEqualTo("series-1");
        Routine saved = routineRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getExternalSource()).isEqualTo(SOURCE);
        assertThat(saved.getExternalId()).isEqualTo("series-1");
        assertThat(saved.getOriginRoutineId()).isEqualTo(response.id());
    }

    @Test
    void 같은_외부_참조로_다시_만들면_ROUTINE_EXTERNAL_DUPLICATE() {
        service.create(userId, imported("PT", "series-1"));

        assertDuplicate(() -> service.create(userId, imported("PT(재동기화)", "series-1")));
    }

    @Test
    void 외부_참조_id의_앞뒤_공백은_같은_id로_본다() {
        RoutineResponse first = service.create(userId, imported("PT", "  series-1 "));
        assertThat(first.externalId()).isEqualTo("series-1");

        assertDuplicate(() -> service.create(userId, imported("PT", "series-1")));
    }

    @Test
    void 지운_임포트_루틴과_같은_외부_참조는_다시_만들_수_없다() {
        Long id = service.create(userId, imported("PT", "series-1")).id();
        service.delete(userId, id);

        assertDuplicate(() -> service.create(userId, imported("PT", "series-1")));
    }

    @Test
    void 다른_유저는_같은_외부_참조를_가질_수_있다() {
        service.create(userId, imported("PT", "series-1"));
        Long other = userRepository.save(User.signUp()).getId();

        assertThat(service.create(other, imported("PT", "series-1")).externalId()).isEqualTo("series-1");
    }

    @Test
    void 외부_참조를_한쪽만_보내면_ROUTINE_EXTERNAL_REF_INCOMPLETE() {
        assertThatThrownBy(() -> service.create(userId, request("PT", SOURCE, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_EXTERNAL_REF_INCOMPLETE);
        assertThatThrownBy(() -> service.create(userId, request("PT", null, "series-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_EXTERNAL_REF_INCOMPLETE);
        assertThat(routineRepository.findByUserIdAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(userId))
                .isEmpty();
    }

    @Test
    void 일반_루틴은_외부_참조가_없어도_여러_개_만들_수_있다() {
        service.create(userId, request("운동", null, null));
        service.create(userId, request("독서", null, null));
        service.create(userId, request("명상", "", ""));

        assertThat(routineRepository.findByUserIdAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(userId))
                .hasSize(3)
                .allSatisfy(r -> {
                    assertThat(r.getExternalSource()).isNull();
                    assertThat(r.getExternalId()).isNull();
                });
    }

    @Test
    void 제자리_수정은_외부_참조를_유지한다() {
        Long id = service.create(userId, imported("PT", "series-1")).id();

        RoutineResponse updated = service.update(userId, id,
                new RoutineUpdateRequest("PT(변경)", null, null, null, null, null, null, null));

        assertThat(updated.id()).isEqualTo(id);
        assertThat(updated.title()).isEqualTo("PT(변경)");
        assertThat(updated.externalSource()).isEqualTo(SOURCE);
        assertThat(updated.externalId()).isEqualTo("series-1");
    }

    // 스케줄 수정으로 버전이 갈리면 새 row 에는 external 이 없지만(복사 안 함) 응답은 origin 에서 읽어 그대로 보이고,
    // 옛 row 가 unique 를 계속 점유해 같은 시리즈의 재임포트는 여전히 막힌다
    @Test
    void 스케줄_수정으로_버전이_갈려도_응답은_origin의_외부_참조를_보이고_재임포트는_막힌다() {
        Long oldId = service.create(userId, imported("PT", "series-1")).id();
        backdateCreatedAt(oldId, 3);

        RoutineResponse updated = service.update(userId, oldId,
                new RoutineUpdateRequest(null, null, null, "WEEKLY", new RepeatDays(List.of("TUE")),
                        null, null, null));
        em.flush();
        em.clear();

        assertThat(updated.id()).isNotEqualTo(oldId);
        assertThat(updated.originRoutineId()).isEqualTo(oldId);
        // 새 row 자체에는 없음
        Routine neo = routineRepository.findById(updated.id()).orElseThrow();
        assertThat(neo.getExternalSource()).isNull();
        assertThat(neo.getExternalId()).isNull();
        // 응답(수정·단건·목록 모두)은 origin 값
        assertThat(updated.externalSource()).isEqualTo(SOURCE);
        assertThat(updated.externalId()).isEqualTo("series-1");
        assertThat(service.get(userId, updated.id()).externalId()).isEqualTo("series-1");
        assertThat(service.list(userId, null, null).items())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.id()).isEqualTo(updated.id());
                    assertThat(item.externalId()).isEqualTo("series-1");
                });
        // 재임포트 차단 유지
        assertDuplicate(() -> service.create(userId, imported("PT", "series-1")));
    }

    @Test
    void 사전_조회를_우회해도_unique_제약이_같은_외부_참조_저장을_막는다() {
        User owner = userRepository.findById(userId).orElseThrow();
        routineRepository.saveAndFlush(Routine.createImported(owner, null, "PT", AuthType.CHECK, "DAILY", null,
                null, LocalDate.now(KST), null, SOURCE, "series-1"));

        assertThatThrownBy(() -> routineRepository.saveAndFlush(Routine.createImported(owner, null, "PT",
                AuthType.CHECK, "DAILY", null, null, LocalDate.now(KST), null, SOURCE, "series-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 사전 exists 조회만 false 로 고정한 채 실제 MySQL 에 두 번 저장 — 드라이버 메시지 기반 unique 위반 감지가 409 로 변환되는지
    @Test
    void 사전_조회를_통과한_동시_요청은_실제_unique_위반을_ROUTINE_EXTERNAL_DUPLICATE로_돌려준다() {
        service.create(userId, imported("PT", "series-1"));
        RoutineRepository racing = mock(RoutineRepository.class, AdditionalAnswers.delegatesTo(routineRepository));
        doReturn(false).when(racing).existsByUserIdAndExternalSourceAndExternalId(any(), anyString(), anyString());
        RoutineService racingService = serviceWith(racing);

        assertDuplicate(() -> racingService.create(userId, imported("PT(재동기화)", "series-1")));
    }

    private void assertDuplicate(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_EXTERNAL_DUPLICATE);
    }

    private static RoutineCreateRequest imported(String title, String externalId) {
        return request(title, SOURCE, externalId);
    }

    private static RoutineCreateRequest request(String title, String externalSource, String externalId) {
        return new RoutineCreateRequest(title, null, AuthType.CHECK, "WEEKLY",
                new RepeatDays(List.of("MON")), LocalTime.of(7, 0), LocalDate.now(KST), null, null,
                externalSource, externalId);
    }

    private void backdateCreatedAt(Long routineId, int days) {
        em.flush();
        em.createNativeQuery("update routines set created_at = created_at - interval " + days
                + " day where id = " + routineId).executeUpdate();
        em.clear();
    }
}
