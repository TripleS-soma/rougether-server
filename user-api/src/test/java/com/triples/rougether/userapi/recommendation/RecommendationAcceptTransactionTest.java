package com.triples.rougether.userapi.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.recommendation.entity.RecommendationStatus;
import com.triples.rougether.domain.recommendation.entity.RecommendationType;
import com.triples.rougether.domain.recommendation.entity.RoutineRecommendation;
import com.triples.rougether.domain.recommendation.repository.RoutineRecommendationRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.recommendation.error.RecommendationErrorCode;
import com.triples.rougether.userapi.recommendation.service.RecommendationCommandService;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 수락의 트랜잭션 경계·동시성(#329)을 프록시가 붙은 실제 빈으로 검증한다.
// 서비스 단위 통합테스트(@DataJpaTest + new Service)는 테스트 트랜잭션에 얹혀 돌아 @Transactional 경계
// 자체를 증명하지 못하므로, 여기서 (a) 동시 accept 직렬화(행 락) (b) 루틴 분기와 추천 상태 갱신이
// 한 트랜잭션으로 묶임(롤백 시 둘 다 되돌아감)을 별도로 확인한다.
@SpringBootTest
class RecommendationAcceptTransactionTest {

    private static final String PROPOSAL_JSON = "{\"repeatType\":\"WEEKLY\",\"daysOfWeek\":[\"MON\",\"FRI\"]}";

    @Autowired
    private RecommendationCommandService commandService;
    @Autowired
    private RoutineRecommendationRepository recommendationRepository;
    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private User user;
    private Routine routine;
    private RoutineRecommendation recommendation;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.signUp());
        routine = routineRepository.save(Routine.create(user, null, "동시성 러닝", AuthType.CHECK,
                "WEEKLY", "{\"daysOfWeek\":[\"MON\",\"WED\",\"FRI\"]}", null, null, null));
        routine.assignOriginToSelf();
        routine = routineRepository.save(routine);
        // 당일 생성분의 스케줄 변경은 제자리 수정이라, 버전 분기 경로를 태우려면 경과한 날이 있어야 함
        jdbcTemplate.update("UPDATE routines SET created_at = created_at - INTERVAL 21 DAY WHERE id = ?",
                routine.getId());
        recommendation = recommendationRepository.save(RoutineRecommendation.rule(user,
                routine.getOriginRoutineId(), routine.getId(), RecommendationType.ADJUST_DAYS, PROPOSAL_JSON,
                "수요일을 빼 보면 어떨까요?", Instant.now().plus(Duration.ofDays(3))));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM routine_recommendations WHERE user_id = ?", user.getId());
        jdbcTemplate.update("DELETE FROM routines WHERE user_id = ?", user.getId());
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());
    }

    @Test
    void 같은_추천_동시_수락은_행_락으로_직렬화되어_한_번만_적용된다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger succeeded = new AtomicInteger();
        ConcurrentLinkedQueue<String> failureCodes = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    commandService.accept(user.getId(), recommendation.getId());
                    succeeded.incrementAndGet();
                } catch (BusinessException e) {
                    failureCodes.add(e.getErrorCode().code());
                } catch (Exception ignored) {
                    // 성공 횟수와 DB 최종 상태로 검증한다
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 정확히 한 요청만 적용되고, 나머지는 선행 커밋의 종결 상태를 보고 409
        assertThat(succeeded).hasValue(1);
        assertThat(failureCodes).containsExactly(RecommendationErrorCode.RECOMMENDATION_ALREADY_HANDLED.code());
        // 계보에 살아있는 버전이 정확히 1개(이중 분기 없음)이고, 추천은 그 버전을 가리킨다
        Long lineageId = routine.getOriginRoutineId();
        Integer aliveCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routines WHERE COALESCE(origin_routine_id, id) = ? AND deleted_at IS NULL",
                Integer.class, lineageId);
        assertThat(aliveCount).isEqualTo(1);
        RoutineRecommendation accepted = recommendationRepository.findById(recommendation.getId()).orElseThrow();
        assertThat(accepted.getStatus()).isEqualTo(RecommendationStatus.ACCEPTED);
        Routine aliveVersion = routineRepository.findAliveByLineage(user.getId(), lineageId).orElseThrow();
        assertThat(accepted.getAppliedRoutineId()).isEqualTo(aliveVersion.getId());
        assertThat(aliveVersion.getId()).isNotEqualTo(routine.getId());
    }

    @Test
    void 수락_트랜잭션이_롤백되면_루틴_분기와_추천_상태가_함께_되돌아간다() {
        // accept 가 이 트랜잭션에 REQUIRED 로 참여함을 증명한다 — 만약 내부가 REQUIRES_NEW 로 바뀌면
        // 롤백 후에도 분기가 남아 이 테스트가 잡는다.
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            commandService.accept(user.getId(), recommendation.getId());
            status.setRollbackOnly();
        });

        RoutineRecommendation reloaded = recommendationRepository.findById(recommendation.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RecommendationStatus.ACTIVE);
        assertThat(reloaded.getAppliedRoutineId()).isNull();
        Integer lineageRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routines WHERE COALESCE(origin_routine_id, id) = ?",
                Integer.class, routine.getOriginRoutineId());
        assertThat(lineageRows).isEqualTo(1);
        Routine untouched = routineRepository.findById(routine.getId()).orElseThrow();
        assertThat(untouched.getDeletedAt()).isNull();
    }
}
