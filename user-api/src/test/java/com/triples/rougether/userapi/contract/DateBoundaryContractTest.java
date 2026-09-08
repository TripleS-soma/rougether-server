package com.triples.rougether.userapi.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.contract.DateBoundaryContracts.BoundaryCase;
import com.triples.rougether.userapi.contract.DateBoundaryContracts.Fixture;
import com.triples.rougether.userapi.contract.DateBoundaryContracts.NaiveDate;
import com.triples.rougether.userapi.contract.DateBoundaryContracts.Recorded;
import com.triples.rougether.userapi.contract.DateBoundaryContracts.RecordedCase;
import com.triples.rougether.userapi.contract.DateBoundaryContracts.RecordedRequest;
import com.triples.rougether.userapi.global.config.SchedulingConfig;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.testsupport.MutableClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// 날짜·시각 경계 계약(spec contracts/date-boundary-cases.json) — 프론트와 백엔드가 같은 날짜를 같은 의미로
// 해석하는지 검증한다. 커버리지가 높아도 잡히지 않던 사고(KST 자정 직후 UTC 절단으로 전날 전송)를 겨냥함:
//  1) 시계를 fixture의 순간으로 고정하고 서버가 expectedDate를 "당일"로, naive 날짜를 적힌 verdict대로 판정하는지
//  2) 모바일이 같은 순간에 실제 요청 생성 코드로 만든 본문(date-boundary-requests.json)을 재생해 저장 날짜·보상 확인
// 전체 스택(보안 필터·실제 MySQL·Flyway) 위에서 돈다. 서비스 4종(RoutineLog/Routine/Todo/Today)이 주입받는
// Clock을 @Primary MutableClock으로 바꿔 case마다 순간을 옮긴다
@SpringBootTest
@AutoConfigureMockMvc
class DateBoundaryContractTest {

    private static final int TODAY_REWARD = 10;
    private static final String COMPLETE_PATH = "/routines/1/logs";
    private static final String CREATE_PATH = "/routines";

    @TestConfiguration
    static class FixedClockConfig {
        // 순간만 고정하고 존은 프로덕션 빈에서 이어받는다 — kstClock의 존이 UTC로 바뀌면 이 테스트가 그대로 깨져야 한다
        @Bean
        @Primary
        MutableClock contractClock() {
            return new MutableClock(Instant.parse("2026-09-08T00:00:00Z"), new SchedulingConfig().kstClock().getZone());
        }
    }

    private static Fixture fixture;
    private static Recorded recorded;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserWalletRepository userWalletRepository;
    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;

    @BeforeAll
    static void loadContracts() {
        fixture = DateBoundaryContracts.loadFixture();
        recorded = DateBoundaryContracts.loadRecorded();
        // 어떤 조합을 검증했는지 로그로 남김(워크플로 summary가 이 줄을 집어감)
        System.out.printf("[date-boundary] source=%s | fixture schema=%d zone=%s cases=%d | mobile=%s (%s) spec=%s | runs=%s%n",
                DateBoundaryContracts.source(), fixture.schemaVersion(), fixture.zone(), fixture.cases().size(),
                recorded.mobileSha(), recorded.mobileRef(),
                recorded.spec() != null ? recorded.spec().get("sha") : null,
                recorded.runs().stream().map(r -> r.tz() + "×" + r.records().size()).toList());
    }

    static Stream<BoundaryCase> fixtureCases() {
        return DateBoundaryContracts.loadFixture().cases().stream();
    }

    static Stream<RecordedCase> recordedCases() {
        return DateBoundaryContracts.loadRecorded().runs().stream().flatMap(r -> r.records().stream());
    }

    // classpath 복사본의 mobileSha가 PENDING-*이면 스냅샷이 아직 머지된 모바일 커밋을 가리키지 않는 것 —
    // 조용히 통과시키지 않고 skip으로 드러낸다. -Dcontracts.dir(교차 워크플로, 모바일 HEAD에서 생성)에서는 항상 실행
    private static void assumeSnapshotIsPinned() {
        boolean external = System.getProperty("contracts.dir") != null;
        String sha = recorded.mobileSha();
        assumeTrue(external || (sha != null && !sha.startsWith("PENDING")),
                "date-boundary-requests.json 의 mobileSha 가 " + sha + " — 모바일 머지 후 스냅샷을 재기록하고 sources.json 을 갱신하세요");
    }

    // ---- 1) 고정 시각에서의 서버 판정 --------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("fixtureCases")
    void 고정_시각에서_expectedDate는_당일이고_naive_날짜는_fixture_verdict대로_판정된다(BoundaryCase c) throws Exception {
        clock.set(Instant.parse(c.instant()));
        Seed seed = seed();

        // /today 의 기준일이 곧 서버의 "오늘"
        mockMvc.perform(get("/api/v1/today").header(AUTHORIZATION, seed.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(c.expectedDate()));

        // 계약대로 만든 날짜 → 당일 완료: 보상·저장 날짜
        complete(seed, c.expectedDate())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.routineDate").value(c.expectedDate()))
                .andExpect(jsonPath("$.rewardAmount").value(TODAY_REWARD));
        assertThat(completedOn(seed, c.expectedDate())).isTrue();
        assertThat(walletBalance(seed)).isEqualTo(TODAY_REWARD);

        // 사고 경로가 만들었을 날짜들 — 서버는 고쳐 쓰지 않고 verdict대로 취급한다.
        // UTC 절단과 단말 로컬이 같은 날짜를 내는 case(단말이 UTC·미국)는 한 번만 검증(같은 날짜 재완료는 ALREADY_COMPLETED)
        for (NaiveDate naive : distinctNaiveDates(c)) {
            expectCompletionVerdict(seed, naive, c.expectedDate());
        }

        // 루틴 생성 startsOn — 기본값 "오늘"이 UTC 절단이면 KST 00:00~08:59에 거부되던 경로
        create(seed, c.expectedDate())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startsOn").value(c.expectedDate()));
        for (NaiveDate naive : distinctNaiveDates(c)) {
            expectStartsOnVerdict(seed, naive);
        }
    }

    private static List<NaiveDate> distinctNaiveDates(BoundaryCase c) {
        NaiveDate utc = c.naive().utcTruncated();
        NaiveDate device = c.naive().deviceLocal();
        return utc.date().equals(device.date()) ? List.of(utc) : List.of(utc, device);
    }

    private void expectCompletionVerdict(Seed seed, NaiveDate naive, String expectedDate) throws Exception {
        switch (naive.verdict()) {
            case "TODAY" -> assertThat(naive.date()).isEqualTo(expectedDate); // 동일 날짜 — 위에서 이미 검증
            case "PAST" -> {
                int before = walletBalance(seed);
                complete(seed, naive.date())
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.routineDate").value(naive.date()))
                        .andExpect(jsonPath("$.rewardAmount").value(0));
                // 과거 기록으로 그대로 저장(오늘로 보정하지 않음) + 코인 없음 — 사고 때 사용자가 본 증상
                assertThat(completedOn(seed, naive.date())).isTrue();
                assertThat(walletBalance(seed)).isEqualTo(before);
            }
            case "FUTURE" -> complete(seed, naive.date())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ROUTINE_DATE"));
            default -> fail("알 수 없는 verdict: " + naive.verdict());
        }
    }

    private void expectStartsOnVerdict(Seed seed, NaiveDate naive) throws Exception {
        switch (naive.verdict()) {
            case "TODAY" -> { /* expectedDate 와 동일 — 위에서 검증 */ }
            case "PAST" -> create(seed, naive.date())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ROUTINE_STARTS_ON_BEFORE_TODAY"));
            case "FUTURE" -> create(seed, naive.date())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.startsOn").value(naive.date()));
            default -> fail("알 수 없는 verdict: " + naive.verdict());
        }
    }

    // ---- 2) 모바일이 기록한 실제 요청 재생 ----------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("recordedCases")
    void 모바일이_같은_순간에_실제로_만든_요청을_재생하면_expectedDate로_저장되고_당일_보상을_받는다(RecordedCase r) throws Exception {
        assumeSnapshotIsPinned();
        clock.set(Instant.parse(r.instant()));
        Seed seed = seed();
        assertThat(r.requests()).extracting(RecordedRequest::name)
                .containsExactlyInAnyOrder("routine-complete", "routine-create");

        for (RecordedRequest req : r.requests()) {
            assertThat(req.method()).isEqualTo("POST");
            switch (req.name()) {
                case "routine-complete" -> {
                    assertThat(req.path()).isEqualTo(COMPLETE_PATH);
                    replay(seed, "/api/v1/routines/" + seed.routineId() + "/logs", req.body())
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.routineDate").value(r.expectedDate()))
                            .andExpect(jsonPath("$.rewardAmount").value(TODAY_REWARD));
                    assertThat(completedOn(seed, r.expectedDate())).isTrue();
                }
                case "routine-create" -> {
                    assertThat(req.path()).isEqualTo(CREATE_PATH);
                    replay(seed, "/api/v1/routines", req.body())
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.startsOn").value(r.expectedDate()));
                }
                default -> fail("기록된 요청 이름을 모름: " + req.name());
            }
        }
    }

    // ---- 3) 두 계약 파일이 서로 맞물리는지(테스트에 이빨이 있는지) -------------------------------------

    @Test
    void fixture는_경계를_실제로_밟고_기록된_요청은_모든_case와_단말_시간대를_덮는다() {
        assumeSnapshotIsPinned();
        List<BoundaryCase> cases = fixture.cases();
        assertThat(cases).anyMatch(c -> c.naive().utcTruncated().verdict().equals("PAST"));
        assertThat(cases).anyMatch(c -> c.naive().deviceLocal().verdict().equals("PAST"));
        assertThat(cases).anyMatch(c -> c.naive().deviceLocal().verdict().equals("FUTURE"));

        Map<String, String> expectedById = cases.stream()
                .collect(Collectors.toMap(BoundaryCase::id, BoundaryCase::expectedDate));
        Set<String> zones = cases.stream().map(BoundaryCase::deviceTimeZone).collect(Collectors.toSet());

        assertThat(recorded.runs()).extracting(DateBoundaryContracts.Run::tz).containsAll(zones);
        for (DateBoundaryContracts.Run run : recorded.runs()) {
            assertThat(run.records()).extracting(RecordedCase::caseId).containsAll(expectedById.keySet());
            for (RecordedCase rc : run.records()) {
                assertThat(rc.expectedDate())
                        .as("case %s 의 기록 expectedDate 가 fixture 와 다름 — fixture 갱신 후 재기록 필요", rc.caseId())
                        .isEqualTo(expectedById.get(rc.caseId()));
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private record Seed(Long userId, Long routineId, String bearer) {
    }

    private Seed seed() {
        User user = userRepository.save(User.signUp());
        userWalletRepository.save(UserWallet.createWithBalance(user, CurrencyType.COIN, 0));
        Routine routine = routineRepository.save(Routine.create(user, null, "경계 루틴", AuthType.CHECK,
                "DAILY", null, null, null, null));
        String token = tokenService.issueAccessToken(user.getId(), MemberRole.NORMAL);
        return new Seed(user.getId(), routine.getId(), "Bearer " + token);
    }

    private ResultActions complete(Seed seed, String routineDate) throws Exception {
        return replay(seed, "/api/v1/routines/" + seed.routineId() + "/logs", Map.of("routineDate", routineDate));
    }

    private ResultActions create(Seed seed, String startsOn) throws Exception {
        return replay(seed, "/api/v1/routines", Map.of(
                "title", "경계 생성", "authType", "CHECK", "repeatType", "DAILY", "startsOn", startsOn));
    }

    private ResultActions replay(Seed seed, String url, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(url)
                .header(AUTHORIZATION, seed.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(DateBoundaryContracts.toJson(body)));
    }

    // 저장된 날짜가 클라이언트가 보낸 날짜 그대로인지(서버가 오늘로 고쳐 쓰지 않았는지) — 루틴·날짜 스코프 조회
    private boolean completedOn(Seed seed, String date) {
        return routineLogRepository.findByRoutineIdAndRoutineDate(seed.routineId(), LocalDate.parse(date)).stream()
                .anyMatch(log -> log.getStatus() == RoutineLogStatus.COMPLETED);
    }

    private int walletBalance(Seed seed) {
        return userWalletRepository.findByUserIdAndCurrencyType(seed.userId(), CurrencyType.COIN)
                .orElseThrow().getBalance();
    }
}
