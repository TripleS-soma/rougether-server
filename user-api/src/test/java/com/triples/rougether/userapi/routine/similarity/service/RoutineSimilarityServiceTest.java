package com.triples.rougether.userapi.routine.similarity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.infra.llm.EmbeddingClient;
import com.triples.rougether.infra.llm.LlmException;
import com.triples.rougether.userapi.agenda.DailyAgendaAssembler;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.routine.similarity.dto.SimilarItem;
import com.triples.rougether.userapi.routine.similarity.dto.SimilarityRequest;
import com.triples.rougether.userapi.routine.similarity.dto.SimilarityResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class RoutineSimilarityServiceTest {

    // 2026-08-20 은 목요일
    private static final LocalDate THU = LocalDate.of(2026, 8, 20);
    private static final LocalDate FRI = THU.plusDays(1);
    private static final double THRESHOLD = 0.75;

    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private UserRepository userRepository;

    private FakeEmbeddingClient embeddingClient;
    private RoutineSimilarityService service;
    private User user;

    @BeforeEach
    void setUp() {
        embeddingClient = new FakeEmbeddingClient();
        service = new RoutineSimilarityService(routineRepository, todoRepository, new DailyAgendaAssembler(),
                embeddingClient, THRESHOLD);
        user = userRepository.save(User.signUp());
    }

    @Test
    void 정규화_제목이_같으면_EXACT_1점으로_잡는다() {
        Long routineId = persistDailyRoutine(user, "물 마시기");

        SimilarityResponse response = service.compare(user.getId(), request(THU, "물마시기"));

        SimilarityResponse.Item item = response.items().get(0);
        assertThat(item.hasSimilar()).isTrue();
        assertThat(item.similar()).singleElement().satisfies(s -> {
            assertThat(s.kind()).isEqualTo(SimilarItem.Kind.ROUTINE);
            assertThat(s.id()).isEqualTo(routineId);
            assertThat(s.score()).isEqualTo(1.0);
            assertThat(s.matchType()).isEqualTo(SimilarItem.MatchType.EXACT);
        });
        // 후보가 전부 EXACT 로 잡히면 임베딩을 부르지 않는다
        assertThat(embeddingClient.calls).isEmpty();
        assertThat(response.embeddingApplied()).isTrue();
    }

    @Test
    void 임베딩_코사인이_threshold_이상인_후보만_EMBEDDING으로_잡는다() {
        Long gym = persistDailyRoutine(user, "헬스");
        persistDailyRoutine(user, "독서");
        embeddingClient.vectors = Map.of(
                "pt", new float[] {1f, 0f},
                "헬스", new float[] {0.9f, 0.436f},   // cos ≈ 0.90
                "독서", new float[] {0f, 1f});         // cos = 0

        SimilarityResponse response = service.compare(user.getId(), request(THU, "PT"));

        assertThat(response.embeddingApplied()).isTrue();
        SimilarityResponse.Item item = response.items().get(0);
        assertThat(item.similar()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo(gym);
            assertThat(s.matchType()).isEqualTo(SimilarItem.MatchType.EMBEDDING);
            assertThat(s.score()).isBetween(0.85, 0.95);
        });
    }

    @Test
    void score_내림차순으로_최대_3개만_돌려준다() {
        embeddingClient.vectors = Map.of(
                "운동", new float[] {1f, 0f},
                "a", new float[] {0.99f, 0.14f},
                "b", new float[] {0.95f, 0.31f},
                "c", new float[] {0.90f, 0.44f},
                "d", new float[] {0.85f, 0.53f});
        persistDailyRoutine(user, "a");
        persistDailyRoutine(user, "b");
        persistTodo(user, "c", THU);
        persistTodo(user, "d", THU);

        SimilarityResponse.Item item = service.compare(user.getId(), request(THU, "운동")).items().get(0);

        assertThat(item.similar()).hasSize(3);
        assertThat(item.similar()).extracting(SimilarItem::title).containsExactly("a", "b", "c");
        assertThat(item.similar()).extracting(SimilarItem::score).isSortedAccordingTo((x, y) -> Double.compare(y, x));
    }

    @Test
    void 날짜별로_반복_대상_루틴과_그날_마감_투두만_후보가_된다() {
        // 목요일에만 반복하는 루틴, 금요일 마감 투두, 마감 없는 투두 — 목요일 조회엔 루틴만, 금요일 조회엔 투두만
        persistWeeklyRoutine(user, "물 마시기", "THU");
        persistTodo(user, "물 마시기", FRI);
        persistTodo(user, "물 마시기", null);

        SimilarityResponse response = service.compare(user.getId(),
                new SimilarityRequest(List.of(new SimilarityRequest.Item(THU, "물마시기"),
                        new SimilarityRequest.Item(FRI, "물마시기"),
                        new SimilarityRequest.Item(FRI.plusDays(1), "물마시기"))));

        assertThat(response.items()).hasSize(3);
        assertThat(response.items().get(0).similar()).extracting(SimilarItem::kind)
                .containsExactly(SimilarItem.Kind.ROUTINE);
        assertThat(response.items().get(1).similar()).extracting(SimilarItem::kind)
                .containsExactly(SimilarItem.Kind.TODO);
        assertThat(response.items().get(2).hasSimilar()).isFalse();
    }

    @Test
    void 삭제된_투두는_후보에서_제외되고_완료된_투두는_포함된다() {
        Long deleted = persistTodo(user, "물 마시기", THU);
        todoRepository.findById(deleted).orElseThrow().softDelete(java.time.Instant.now());
        Long done = persistTodo(user, "물마시기", THU);
        todoRepository.findById(done).orElseThrow()
                .complete(com.triples.rougether.domain.shared.CurrencyType.COIN, 0, java.time.Instant.now());
        todoRepository.flush();

        SimilarityResponse.Item item = service.compare(user.getId(), request(THU, "물마시기")).items().get(0);

        assertThat(item.similar()).extracting(SimilarItem::id).containsExactly(done);
    }

    @Test
    void 타_유저의_루틴_투두는_후보에서_제외된다() {
        User other = userRepository.save(User.signUp());
        persistDailyRoutine(other, "물 마시기");
        persistTodo(other, "물 마시기", THU);

        SimilarityResponse response = service.compare(user.getId(), request(THU, "물마시기"));

        assertThat(response.items().get(0).hasSimilar()).isFalse();
        assertThat(embeddingClient.calls).isEmpty();
    }

    @Test
    void 후보가_없으면_임베딩을_호출하지_않는다() {
        SimilarityResponse response = service.compare(user.getId(), request(THU, "아무거나"));

        assertThat(response.items().get(0).hasSimilar()).isFalse();
        assertThat(embeddingClient.calls).isEmpty();
        assertThat(response.embeddingApplied()).isTrue();
    }

    @Test
    void stub이면_EXACT만으로_응답하고_embeddingApplied는_false() {
        embeddingClient.available = false;
        persistDailyRoutine(user, "물 마시기");
        persistDailyRoutine(user, "헬스");

        SimilarityResponse response = service.compare(user.getId(), request(THU, "물마시기"));

        assertThat(response.embeddingApplied()).isFalse();
        assertThat(response.items().get(0).similar()).extracting(SimilarItem::matchType)
                .containsExactly(SimilarItem.MatchType.EXACT);
        assertThat(embeddingClient.calls).isEmpty();
    }

    @Test
    void 임베딩_호출이_실패해도_EXACT만으로_200_응답하고_embeddingApplied는_false() {
        embeddingClient.failWith = new LlmException("boom", true);
        persistDailyRoutine(user, "물 마시기");
        persistDailyRoutine(user, "헬스");

        SimilarityResponse response = service.compare(user.getId(), request(THU, "물마시기"));

        assertThat(response.embeddingApplied()).isFalse();
        assertThat(response.items().get(0).similar()).extracting(SimilarItem::title).containsExactly("물 마시기");
        assertThat(embeddingClient.calls).hasSize(1);
    }

    @Test
    void 요청_제목과_후보_제목의_정규화_distinct만_한_번에_임베딩한다() {
        persistDailyRoutine(user, "헬스");
        persistDailyRoutine(user, "헬 스");   // 정규화하면 같은 제목
        persistTodo(user, "독서", THU);
        embeddingClient.vectors = Map.of("pt", new float[] {1f, 0f}, "헬스", new float[] {0f, 1f},
                "독서", new float[] {0f, 1f}, "책읽기", new float[] {0f, 1f});

        service.compare(user.getId(), new SimilarityRequest(List.of(
                new SimilarityRequest.Item(THU, "PT"), new SimilarityRequest.Item(THU, " pt "),
                new SimilarityRequest.Item(THU, "책 읽기"))));

        assertThat(embeddingClient.calls).hasSize(1);
        assertThat(embeddingClient.calls.get(0)).containsExactlyInAnyOrder("pt", "책읽기", "헬스", "독서");
    }

    @Test
    void 응답_items는_요청_순서를_유지하고_title은_trim된다() {
        SimilarityResponse response = service.compare(user.getId(), new SimilarityRequest(List.of(
                new SimilarityRequest.Item(FRI, "  둘째 "), new SimilarityRequest.Item(THU, "첫째"))));

        assertThat(response.items()).extracting(SimilarityResponse.Item::title).containsExactly("둘째", "첫째");
        assertThat(response.items()).extracting(SimilarityResponse.Item::date).containsExactly(FRI, THU);
    }

    private static SimilarityRequest request(LocalDate date, String title) {
        return new SimilarityRequest(List.of(new SimilarityRequest.Item(date, title)));
    }

    private Long persistDailyRoutine(User owner, String title) {
        Routine saved = routineRepository.save(Routine.create(owner, null, title, AuthType.CHECK,
                "DAILY", null, null, THU.minusDays(30), null));
        saved.assignOriginToSelf();
        return saved.getId();
    }

    private Long persistWeeklyRoutine(User owner, String title, String day) {
        Routine saved = routineRepository.save(Routine.create(owner, null, title, AuthType.CHECK,
                "WEEKLY", "{\"daysOfWeek\":[\"" + day + "\"]}", null, THU.minusDays(30), null));
        saved.assignOriginToSelf();
        return saved.getId();
    }

    private Long persistTodo(User owner, String title, LocalDate dueDate) {
        return todoRepository.save(Todo.create(owner, null, title, null, dueDate, null)).getId();
    }

    // 정규화 제목 → 고정 벡터 테이블. 없는 제목은 (0,0) — 코사인 0
    static final class FakeEmbeddingClient implements EmbeddingClient {
        boolean available = true;
        LlmException failWith;
        Map<String, float[]> vectors = Map.of();
        final List<List<String>> calls = new ArrayList<>();

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public List<float[]> embed(List<String> inputs) {
            calls.add(List.copyOf(inputs));
            if (failWith != null) {
                throw failWith;
            }
            return inputs.stream().map(in -> vectors.getOrDefault(in, new float[] {0f, 0f})).toList();
        }
    }
}
