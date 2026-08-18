package com.triples.rougether.userapi.routine.similarity.service;

import com.triples.rougether.common.text.TitleNormalizer;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.infra.llm.EmbeddingClient;
import com.triples.rougether.infra.llm.LlmException;
import com.triples.rougether.userapi.agenda.DailyAgendaAssembler;
import com.triples.rougether.userapi.routine.similarity.CosineSimilarity;
import com.triples.rougether.userapi.routine.similarity.dto.SimilarItem;
import com.triples.rougether.userapi.routine.similarity.dto.SimilarityRequest;
import com.triples.rougether.userapi.routine.similarity.dto.SimilarityResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 캘린더 임포트 미리보기용 유사 루틴·투두 판정 (#303). 저장 없음 — 힌트라서 임베딩을 못 쓰면 EXACT 만으로 응답한다(fail-open).
// 중복 방지의 정본은 투두 externalId(#299)이고 이 결과는 "비슷한 게 있어요" 안내에만 쓴다.
@Service
public class RoutineSimilarityService {

    private static final Logger log = LoggerFactory.getLogger(RoutineSimilarityService.class);
    // 항목당 돌려주는 유사 후보 최대 개수
    static final int MAX_SIMILAR_PER_ITEM = 3;

    private final RoutineRepository routineRepository;
    private final TodoRepository todoRepository;
    private final DailyAgendaAssembler agendaAssembler;
    private final EmbeddingClient embeddingClient;
    // 코사인 유사도가 이 값 이상이면 유사로 채택(기본 0.50 — large/1024 실측값, application.yml 주석 참고)
    private final double threshold;

    public RoutineSimilarityService(RoutineRepository routineRepository, TodoRepository todoRepository,
                                    DailyAgendaAssembler agendaAssembler, EmbeddingClient embeddingClient,
                                    @Value("${routine.similarity.threshold:0.50}") double threshold) {
        this.routineRepository = routineRepository;
        this.todoRepository = todoRepository;
        this.agendaAssembler = agendaAssembler;
        this.embeddingClient = embeddingClient;
        this.threshold = threshold;
    }

    @Transactional(readOnly = true)
    public SimilarityResponse compare(Long userId, SimilarityRequest request) {
        List<Query> queries = request.items().stream()
                .map(item -> new Query(item.date(), item.title().trim()))
                .toList();
        Map<LocalDate, List<Candidate>> candidatesByDate = candidatesByDate(userId, queries);

        // 1차 EXACT: 정규화 제목 일치. 남은 (항목, 후보) 쌍만 임베딩으로 본다
        List<List<SimilarItem>> exactMatches = new ArrayList<>(queries.size());
        boolean anyPending = false;
        for (Query query : queries) {
            List<Candidate> candidates = candidatesByDate.getOrDefault(query.date(), List.of());
            List<SimilarItem> exact = candidates.stream()
                    .filter(c -> c.normalizedTitle().equals(query.normalizedTitle()))
                    .map(c -> c.toSimilar(1.0, SimilarItem.MatchType.EXACT))
                    .toList();
            exactMatches.add(exact);
            anyPending |= exact.size() < candidates.size();
        }

        // 2차 EMBEDDING: EXACT 로 못 잡은 쌍이 하나라도 있을 때만 호출(후보 0개면 호출 없음). 요청 제목+후보 제목의 distinct 로 1회.
        // embeddingApplied 는 "임베딩을 쓸 수 있었는지"(stub 아님 + 호출 실패 없음) — 호출이 필요 없었던 경우도 true
        boolean available = embeddingClient.isAvailable();
        Map<String, float[]> vectors = Map.of();
        boolean failed = false;
        if (anyPending && available) {
            try {
                vectors = embedDistinct(queries, candidatesByDate);
            } catch (LlmException e) {
                // 힌트 API 라 외부 장애로 임포트 흐름을 막지 않는다. embeddingApplied=false 로 알린다
                log.warn("유사도 임베딩 호출 실패 → 정규화 일치만으로 응답: {}", e.getMessage());
                failed = true;
            }
        }
        boolean embeddingApplied = available && !failed;

        List<SimilarityResponse.Item> items = new ArrayList<>(queries.size());
        for (int i = 0; i < queries.size(); i++) {
            Query query = queries.get(i);
            List<SimilarItem> similar = new ArrayList<>(exactMatches.get(i));
            if (!vectors.isEmpty()) {
                similar.addAll(embeddingMatches(query, candidatesByDate.getOrDefault(query.date(), List.of()),
                        vectors));
            }
            similar.sort(Comparator.comparingDouble(SimilarItem::score).reversed()
                    .thenComparing(SimilarItem::kind).thenComparing(SimilarItem::id));
            List<SimilarItem> top = List.copyOf(similar.subList(0, Math.min(MAX_SIMILAR_PER_ITEM, similar.size())));
            items.add(new SimilarityResponse.Item(query.date(), query.title(), !top.isEmpty(), top));
        }
        return new SimilarityResponse(embeddingApplied, items);
    }

    // 후보 소싱(live): ACTIVE·미삭제 루틴은 유저당 1회 조회 후 날짜별 반복 대상 필터, 투두는 요청 날짜 min~max 범위 1회 조회 후 마감일로 그룹핑
    private Map<LocalDate, List<Candidate>> candidatesByDate(Long userId, List<Query> queries) {
        Set<LocalDate> dates = queries.stream().map(Query::date).collect(Collectors.toCollection(LinkedHashSet::new));
        LocalDate from = dates.stream().min(Comparator.naturalOrder()).orElseThrow();
        LocalDate to = dates.stream().max(Comparator.naturalOrder()).orElseThrow();

        List<Routine> routines = routineRepository
                .findByUserIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(
                        userId, RoutineStatus.ACTIVE);
        Map<LocalDate, List<Todo>> todosByDate = todoRepository.findOwnedByDueDateBetween(userId, from, to).stream()
                .collect(Collectors.groupingBy(Todo::getDueDate));

        Map<LocalDate, List<Candidate>> result = new HashMap<>();
        for (LocalDate date : dates) {
            List<Candidate> candidates = new ArrayList<>();
            for (Routine routine : routines) {
                if (agendaAssembler.isRoutineTargetOn(routine, date)) {
                    candidates.add(new Candidate(SimilarItem.Kind.ROUTINE, routine.getId(), routine.getTitle()));
                }
            }
            for (Todo todo : todosByDate.getOrDefault(date, List.of())) {
                candidates.add(new Candidate(SimilarItem.Kind.TODO, todo.getId(), todo.getTitle()));
            }
            result.put(date, List.copyOf(candidates));
        }
        return result;
    }

    // 요청 제목 + 후보 제목의 정규화 distinct 를 한 번에 임베딩. 실패는 LlmException 으로 호출자에게 넘긴다
    private Map<String, float[]> embedDistinct(List<Query> queries, Map<LocalDate, List<Candidate>> candidatesByDate) {
        Set<String> distinct = new LinkedHashSet<>();
        queries.forEach(q -> distinct.add(q.normalizedTitle()));
        candidatesByDate.values().forEach(list -> list.forEach(c -> distinct.add(c.normalizedTitle())));
        List<String> inputs = List.copyOf(distinct);
        List<float[]> embedded = embeddingClient.embed(inputs);
        Map<String, float[]> vectors = new HashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            vectors.put(inputs.get(i), embedded.get(i));
        }
        return vectors;
    }

    private List<SimilarItem> embeddingMatches(Query query, List<Candidate> candidates, Map<String, float[]> vectors) {
        float[] queryVector = vectors.get(query.normalizedTitle());
        List<SimilarItem> matches = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.normalizedTitle().equals(query.normalizedTitle())) {
                continue; // EXACT 로 이미 채택
            }
            double score = CosineSimilarity.of(queryVector, vectors.get(candidate.normalizedTitle()));
            if (score >= threshold) {
                matches.add(candidate.toSimilar(score, SimilarItem.MatchType.EMBEDDING));
            }
        }
        return matches;
    }

    private record Query(LocalDate date, String title, String normalizedTitle) {
        Query(LocalDate date, String title) {
            this(date, title, TitleNormalizer.normalize(title));
        }
    }

    private record Candidate(SimilarItem.Kind kind, Long id, String title, String normalizedTitle) {
        Candidate(SimilarItem.Kind kind, Long id, String title) {
            this(kind, id, title, TitleNormalizer.normalize(title));
        }

        SimilarItem toSimilar(double score, SimilarItem.MatchType matchType) {
            return new SimilarItem(kind, id, title, score, matchType);
        }
    }
}
