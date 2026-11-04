package com.triples.rougether.adminapi.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.adminapi.user.dto.AdminUserFunnelResponse;
import com.triples.rougether.adminapi.user.dto.AdminUserFunnelResponse.FunnelStage;
import com.triples.rougether.adminapi.user.dto.AdminUserFunnelResponse.StageMetric;
import com.triples.rougether.adminapi.user.service.AdminUserFunnelService;
import com.triples.rougether.domain.member.repository.AdminUserFunnelRepository;
import com.triples.rougether.domain.member.repository.AdminUserFunnelRepository.FunnelUserRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminUserFunnelServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2030, 8, 20);
    private static final Instant NOW = LocalDateTime.of(2030, 8, 20, 12, 0).atZone(KST).toInstant();
    private static final Instant SIGNUP_AT = LocalDateTime.of(2030, 8, 18, 9, 0).atZone(KST).toInstant();
    private static final Instant NEXT_DAY_ACCESS = LocalDateTime.of(2030, 8, 19, 9, 0).atZone(KST).toInstant();

    @Mock
    AdminUserFunnelRepository funnelRepository;

    AdminUserFunnelService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserFunnelService(funnelRepository, Clock.fixed(NOW, KST));
    }

    @Test
    void 단계는_이전_단계를_모두_거친_사용자만_누적해_세고_최대_이탈_구간을_찾는다() {
        Instant createdFrom = TODAY.minusDays(29).atStartOfDay(KST).toInstant();
        // 1·2는 다음날 재접속, 7은 완료 없이 재접속, 나머지는 가입 당일만 접속.
        List<FunnelUserRow> users = LongStream.rangeClosed(1, 10)
                .mapToObj(id -> row(id, SIGNUP_AT, (id <= 2 || id == 7) ? NEXT_DAY_ACCESS : SIGNUP_AT))
                .toList();
        when(funnelRepository.findHumanUsers(createdFrom)).thenReturn(users);
        // 99는 가입 창 밖 사용자 - 어느 단계에도 섞이면 안 됨.
        when(funnelRepository.findOnboardingCompletedUserIds(createdFrom))
                .thenReturn(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 99L));
        // 9·10은 온보딩 없이 루틴을 만든 경우 - 누적 기준이라 생성 단계에 세지 않음.
        when(funnelRepository.findRoutineOrTodoCreatedUserIds(createdFrom))
                .thenReturn(List.of(1L, 2L, 3L, 4L, 9L, 10L));
        when(funnelRepository.findFirstCompletionUserIds(createdFrom)).thenReturn(List.of(1L, 2L, 3L, 9L));
        when(funnelRepository.findSharedHouseUserIds(createdFrom)).thenReturn(List.of(1L, 5L, 99L));
        when(funnelRepository.countWithdrawnUsers(createdFrom)).thenReturn(2L);

        AdminUserFunnelResponse response = service.getFunnel(30);

        assertThat(response.asOfDate()).isEqualTo(TODAY);
        assertThat(response.generatedAt()).isEqualTo(NOW);
        assertThat(response.signupDays()).isEqualTo(30);
        assertThat(response.signupPeriod().fromDate()).isEqualTo(TODAY.minusDays(29));
        assertThat(response.signupPeriod().toDate()).isEqualTo(TODAY);
        assertThat(response.signupPeriod().days()).isEqualTo(30);
        assertThat(response.stages()).extracting(StageMetric::stage).containsExactly(
                FunnelStage.SIGNED_UP, FunnelStage.ONBOARDED, FunnelStage.ROUTINE_CREATED,
                FunnelStage.FIRST_COMPLETED, FunnelStage.RETURNED);
        assertThat(response.stages()).extracting(StageMetric::userCount)
                .containsExactly(10L, 8L, 4L, 3L, 2L);
        assertThat(response.stages()).extracting(StageMetric::signupPercentage)
                .containsExactly(100.0, 80.0, 40.0, 30.0, 20.0);
        assertThat(response.stages()).extracting(StageMetric::previousStagePercentage)
                .containsExactly(100.0, 80.0, 50.0, 75.0, 66.7);
        assertThat(response.stages()).extracting(StageMetric::droppedFromPrevious)
                .containsExactly(0L, 2L, 4L, 1L, 1L);
        assertThat(response.biggestDrop().fromStage()).isEqualTo(FunnelStage.ONBOARDED);
        assertThat(response.biggestDrop().toStage()).isEqualTo(FunnelStage.ROUTINE_CREATED);
        assertThat(response.biggestDrop().droppedUserCount()).isEqualTo(4L);
        assertThat(response.biggestDrop().dropPercentage()).isEqualTo(50.0);
        assertThat(response.sharedHouse().userCount()).isEqualTo(2L);
        assertThat(response.sharedHouse().denominatorCount()).isEqualTo(10L);
        assertThat(response.sharedHouse().percentage()).isEqualTo(20.0);
        assertThat(response.withdrawn().userCount()).isEqualTo(2L);
        assertThat(response.withdrawn().denominatorCount()).isEqualTo(12L);
        assertThat(response.withdrawn().percentage()).isEqualTo(16.7);
    }

    @Test
    void 전체_기간은_가입_하한_없이_조회하고_기간을_내리지_않는다() {
        AdminUserFunnelResponse response = service.getFunnel(0);

        assertThat(response.signupDays()).isZero();
        assertThat(response.signupPeriod()).isNull();
        verify(funnelRepository).findHumanUsers(null);
        verify(funnelRepository).findOnboardingCompletedUserIds(null);
        verify(funnelRepository).findRoutineOrTodoCreatedUserIds(null);
        verify(funnelRepository).findFirstCompletionUserIds(null);
        verify(funnelRepository).findSharedHouseUserIds(null);
        verify(funnelRepository).countWithdrawnUsers(null);
    }

    @Test
    void 가입_기간은_최대_365일로_제한하고_음수는_전체_기간으로_본다() {
        AdminUserFunnelResponse bounded = service.getFunnel(1000);
        assertThat(bounded.signupDays()).isEqualTo(365);
        assertThat(bounded.signupPeriod().fromDate()).isEqualTo(TODAY.minusDays(364));
        assertThat(bounded.signupPeriod().days()).isEqualTo(365);
        verify(funnelRepository).findHumanUsers(TODAY.minusDays(364).atStartOfDay(KST).toInstant());

        AdminUserFunnelResponse negative = service.getFunnel(-5);
        assertThat(negative.signupDays()).isZero();
        assertThat(negative.signupPeriod()).isNull();
    }

    @Test
    void 가입자가_없으면_비율은_0이고_최대_이탈_구간은_없다() {
        AdminUserFunnelResponse response = service.getFunnel(7);

        assertThat(response.stages()).hasSize(5);
        assertThat(response.stages()).extracting(StageMetric::userCount).containsOnly(0L);
        assertThat(response.stages()).extracting(StageMetric::signupPercentage).containsOnly(0.0);
        assertThat(response.stages()).extracting(StageMetric::previousStagePercentage).containsOnly(0.0);
        assertThat(response.stages()).extracting(StageMetric::droppedFromPrevious).containsOnly(0L);
        assertThat(response.biggestDrop()).isNull();
        assertThat(response.sharedHouse().percentage()).isZero();
        assertThat(response.withdrawn().percentage()).isZero();
    }

    @Test
    void 이탈이_없으면_최대_이탈_구간은_없다() {
        Instant createdFrom = TODAY.minusDays(6).atStartOfDay(KST).toInstant();
        when(funnelRepository.findHumanUsers(createdFrom))
                .thenReturn(List.of(row(1L, SIGNUP_AT, NEXT_DAY_ACCESS)));
        when(funnelRepository.findOnboardingCompletedUserIds(createdFrom)).thenReturn(List.of(1L));
        when(funnelRepository.findRoutineOrTodoCreatedUserIds(createdFrom)).thenReturn(List.of(1L));
        when(funnelRepository.findFirstCompletionUserIds(createdFrom)).thenReturn(List.of(1L));

        AdminUserFunnelResponse response = service.getFunnel(7);

        assertThat(response.stages()).extracting(StageMetric::userCount).containsOnly(1L);
        assertThat(response.stages()).extracting(StageMetric::previousStagePercentage).containsOnly(100.0);
        assertThat(response.biggestDrop()).isNull();
    }

    @Test
    void 재방문은_마지막_접속의_KST_날짜가_가입일보다_뒤일_때만_인정한다() {
        Instant createdFrom = TODAY.minusDays(6).atStartOfDay(KST).toInstant();
        Instant lateNightSignup = LocalDateTime.of(2030, 8, 18, 23, 30).atZone(KST).toInstant();
        when(funnelRepository.findHumanUsers(createdFrom)).thenReturn(List.of(
                row(1L, lateNightSignup, LocalDateTime.of(2030, 8, 19, 0, 10).atZone(KST).toInstant()),
                row(2L, lateNightSignup, LocalDateTime.of(2030, 8, 18, 23, 59).atZone(KST).toInstant()),
                row(3L, lateNightSignup, null)));
        when(funnelRepository.findOnboardingCompletedUserIds(createdFrom)).thenReturn(List.of(1L, 2L, 3L));
        when(funnelRepository.findRoutineOrTodoCreatedUserIds(createdFrom)).thenReturn(List.of(1L, 2L, 3L));
        when(funnelRepository.findFirstCompletionUserIds(createdFrom)).thenReturn(List.of(1L, 2L, 3L));

        AdminUserFunnelResponse response = service.getFunnel(7);

        StageMetric returned = response.stages().get(4);
        assertThat(returned.stage()).isEqualTo(FunnelStage.RETURNED);
        assertThat(returned.userCount()).isEqualTo(1L);
        assertThat(returned.droppedFromPrevious()).isEqualTo(2L);
        assertThat(response.biggestDrop().fromStage()).isEqualTo(FunnelStage.FIRST_COMPLETED);
        assertThat(response.biggestDrop().toStage()).isEqualTo(FunnelStage.RETURNED);
        assertThat(response.biggestDrop().dropPercentage()).isEqualTo(66.7);
    }

    private static FunnelUserRow row(long userId, Instant createdAt, Instant lastAccessedAt) {
        return new FunnelUserRow() {
            @Override
            public Long getUserId() {
                return userId;
            }

            @Override
            public Instant getCreatedAt() {
                return createdAt;
            }

            @Override
            public Instant getLastAccessedAt() {
                return lastAccessedAt;
            }
        };
    }
}
