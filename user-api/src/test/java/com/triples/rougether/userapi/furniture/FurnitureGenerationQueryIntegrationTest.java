package com.triples.rougether.userapi.furniture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Action;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Status;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.furniture.ai.FurnitureAiClient;
import com.triples.rougether.furniture.dto.FurnitureGenerationResponse;
import com.triples.rougether.infra.assets.AssetStorageService;
import com.triples.rougether.userapi.furniture.service.FurnitureGenerationQueryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "furniture.generation.enabled=false")
class FurnitureGenerationQueryIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-06T03:00:00Z");

    @Autowired FurnitureGenerationQueryService queries;
    @Autowired FurnitureGenerationJobRepository jobs;
    @Autowired UserRepository users;
    @MockitoBean FurnitureAiClient ai;
    @MockitoBean AssetStorageService storage;
    @MockitoBean Clock kstClock;

    private final List<String> jobIds = new ArrayList<>();
    private final List<User> owners = new ArrayList<>();
    private User user;

    @BeforeEach
    void setup() {
        when(kstClock.instant()).thenReturn(CREATED_AT.plusSeconds(172800));
        when(kstClock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        user = createUser();
    }

    @AfterEach
    void cleanup() {
        jobs.deleteAllById(jobIds);
        users.deleteAll(owners);
        verifyNoInteractions(ai, storage);
    }

    @Test
    void 생성이_비활성화돼도_본인_최신_20개만_생성시각_내림차순으로_조회함() {
        List<String> ownIds = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            ownIds.add(saveJob(user, CREATED_AT.plusSeconds(i)).getId());
        }
        saveJob(createUser(), CREATED_AT.plusSeconds(100));

        var result = queries.list(user.getId());

        assertThat(result).extracting(FurnitureGenerationResponse::id)
                .containsExactlyElementsOf(ownIds.reversed().subList(0, 20));
    }

    @Test
    void 생성한_작업이_없으면_빈_목록을_반환함() {
        assertThat(queries.list(user.getId())).isEmpty();
    }

    @Test
    void 본인_업로드중_작업은_아직_없는_결과를_null로_조회함() {
        var job = saveJob(user, CREATED_AT);

        assertThat(queries.get(user.getId(), job.getId())).isEqualTo(new FurnitureGenerationResponse(
                job.getId(), Status.UPLOADING, Action.EXTRACT, null, null, 0, 0,
                null, null, null, CREATED_AT.plusSeconds(86400), CREATED_AT, CREATED_AT));
    }

    @Test
    void 보관기간이_지난_실패작업도_검수결과와_시도횟수를_조회함() {
        var job = saveJob(user, CREATED_AT);
        job.uploaded("private/source.png", CREATED_AT.plusSeconds(1));
        job.extracted("{\"category\":\"chair\"}", CREATED_AT.plusSeconds(2));
        job.claim(CREATED_AT.plusSeconds(3), CREATED_AT.plusSeconds(60));
        job.generated("private/candidate.png", CREATED_AT.plusSeconds(4));
        job.claim(CREATED_AT.plusSeconds(5), CREATED_AT.plusSeconds(60));
        job.reviewed("REJECT", "사진과 다른 형태", "다리 형태 확인");
        job.fail("HARD_QA_REJECTED", CREATED_AT.plusSeconds(6));
        jobs.saveAndFlush(job);

        assertThat(queries.get(user.getId(), job.getId())).isEqualTo(new FurnitureGenerationResponse(
                job.getId(), Status.FAILED, Action.REVIEW, null, null, 1, 1,
                "REJECT", "사진과 다른 형태", "HARD_QA_REJECTED", CREATED_AT.plusSeconds(86400),
                CREATED_AT, CREATED_AT.plusSeconds(6)));
    }

    @Test
    void 타인_작업과_없는_작업은_동일한_404로_차단함() {
        var otherJob = saveJob(createUser(), CREATED_AT);

        assertCode(() -> queries.get(user.getId(), otherJob.getId()), "FURNITURE_JOB_NOT_FOUND", 404);
        assertCode(() -> queries.get(user.getId(), UUID.randomUUID().toString()), "FURNITURE_JOB_NOT_FOUND", 404);
    }

    @Test
    void 탈퇴한_사용자는_본인_작업_상세와_목록을_조회할_수_없음() {
        var job = saveJob(user, CREATED_AT);
        user.softDelete(CREATED_AT.plusSeconds(10));
        users.saveAndFlush(user);

        assertCode(() -> queries.get(user.getId(), job.getId()), "AUTH_INVALID_TOKEN", 401);
        assertCode(() -> queries.list(user.getId()), "AUTH_INVALID_TOKEN", 401);
    }

    @Test
    void 없는_사용자는_작업_존재여부보다_먼저_차단함() {
        assertCode(() -> queries.get(Long.MAX_VALUE, UUID.randomUUID().toString()), "AUTH_INVALID_TOKEN", 401);
        assertCode(() -> queries.list(Long.MAX_VALUE), "AUTH_INVALID_TOKEN", 401);
    }

    private User createUser() {
        var owner = users.save(User.signUp("furniture-query-" + UUID.randomUUID() + "@example.test"));
        owners.add(owner);
        return owner;
    }

    private FurnitureGenerationJob saveJob(User owner, Instant createdAt) {
        var job = jobs.saveAndFlush(FurnitureGenerationJob.create(owner.getId(), UUID.randomUUID().toString(),
                "query-fixture", "앞 의자", createdAt, createdAt.plusSeconds(86400)));
        jobIds.add(job.getId());
        return job;
    }

    private void assertCode(Runnable action, String code, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getErrorCode().code()).isEqualTo(code);
            assertThat(error.getErrorCode().status()).isEqualTo(status);
        });
    }
}
