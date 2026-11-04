package com.triples.rougether.adminapi.user.service;

import com.triples.rougether.adminapi.user.dto.AdminUserFunnelResponse;
import com.triples.rougether.adminapi.user.dto.AdminUserFunnelResponse.DropMetric;
import com.triples.rougether.adminapi.user.dto.AdminUserFunnelResponse.FunnelStage;
import com.triples.rougether.adminapi.user.dto.AdminUserFunnelResponse.SideMetric;
import com.triples.rougether.adminapi.user.dto.AdminUserFunnelResponse.SignupPeriod;
import com.triples.rougether.adminapi.user.dto.AdminUserFunnelResponse.StageMetric;
import com.triples.rougether.domain.member.repository.AdminUserFunnelRepository;
import com.triples.rougether.domain.member.repository.AdminUserFunnelRepository.FunnelUserRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 어드민 유저 퍼널(이탈 지점). 가입 창 안의 실사용자(탈퇴·봇·AI 제외)를 이정표별로 누적 집계하고
// 직전 단계 대비 이탈이 가장 큰 구간을 찾음. 개인정보·루틴 본문은 읽지 않고 사용자 id 집합만 다룸.
@Service
@Transactional(readOnly = true)
public class AdminUserFunnelService {

    public static final int DEFAULT_SIGNUP_DAYS = 30;
    public static final int MAX_SIGNUP_DAYS = 365;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AdminUserFunnelRepository funnelRepository;
    private final Clock clock;

    public AdminUserFunnelService(AdminUserFunnelRepository funnelRepository, Clock clock) {
        this.funnelRepository = funnelRepository;
        this.clock = clock;
    }

    // signupDays = KST 오늘 포함 최근 N일 가입자. 0 이하는 전체 기간, 상한은 MAX_SIGNUP_DAYS.
    public AdminUserFunnelResponse getFunnel(int signupDays) {
        int boundedDays = Math.min(Math.max(signupDays, 0), MAX_SIGNUP_DAYS);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, KST);
        SignupPeriod signupPeriod = boundedDays == 0
                ? null
                : SignupPeriod.inclusive(today.minusDays(boundedDays - 1L), today);
        Instant createdFrom = signupPeriod == null
                ? null
                : signupPeriod.fromDate().atStartOfDay(KST).toInstant();

        List<FunnelUserRow> users = funnelRepository.findHumanUsers(createdFrom);
        Set<Long> signedUp = users.stream()
                .map(FunnelUserRow::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> onboarded = reached(signedUp, funnelRepository.findOnboardingCompletedUserIds(createdFrom));
        Set<Long> created = reached(onboarded, funnelRepository.findRoutineOrTodoCreatedUserIds(createdFrom));
        Set<Long> completed = reached(created, funnelRepository.findFirstCompletionUserIds(createdFrom));
        Set<Long> returned = users.stream()
                .filter(row -> completed.contains(row.getUserId()) && returnedAfterSignupDay(row))
                .map(FunnelUserRow::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> sharedHouse = reached(signedUp, funnelRepository.findSharedHouseUserIds(createdFrom));
        long withdrawn = funnelRepository.countWithdrawnUsers(createdFrom);

        List<StageMetric> stages = toStages(List.of(
                (long) signedUp.size(), (long) onboarded.size(), (long) created.size(),
                (long) completed.size(), (long) returned.size()));
        return new AdminUserFunnelResponse(
                today,
                now,
                boundedDays,
                signupPeriod,
                stages,
                biggestDrop(stages),
                SideMetric.of(sharedHouse.size(), signedUp.size()),
                SideMetric.of(withdrawn, signedUp.size() + withdrawn));
    }

    // 누적 기준: 직전 단계를 거친 사용자 중 이번 이정표에 도달한 사용자만 남김(창 밖 id 는 자연히 걸러짐).
    private static Set<Long> reached(Set<Long> previousStage, Collection<Long> milestoneUserIds) {
        Set<Long> milestone = Set.copyOf(milestoneUserIds);
        return previousStage.stream()
                .filter(milestone::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // 재방문 = 마지막 인증 접속(로그인·refresh 회전)의 KST 일자가 가입 KST 일자보다 뒤. 실시간 접속 상태가 아님.
    private static boolean returnedAfterSignupDay(FunnelUserRow row) {
        if (row.getLastAccessedAt() == null) {
            return false;
        }
        return LocalDate.ofInstant(row.getLastAccessedAt(), KST)
                .isAfter(LocalDate.ofInstant(row.getCreatedAt(), KST));
    }

    private static List<StageMetric> toStages(List<Long> countsInStageOrder) {
        FunnelStage[] stageOrder = FunnelStage.values();
        long signupCount = countsInStageOrder.get(0);
        return IntStream.range(0, stageOrder.length)
                .mapToObj(index -> StageMetric.of(
                        stageOrder[index],
                        countsInStageOrder.get(index),
                        signupCount,
                        countsInStageOrder.get(Math.max(index - 1, 0))))
                .toList();
    }

    // 이탈 인원이 가장 큰 구간. 동률이면 앞 구간을 택하고(max 는 먼저 온 원소를 유지), 이탈이 없으면 null.
    private static DropMetric biggestDrop(List<StageMetric> stages) {
        return IntStream.range(1, stages.size())
                .boxed()
                .filter(index -> stages.get(index).droppedFromPrevious() > 0)
                .max(Comparator.comparingLong(index -> stages.get(index).droppedFromPrevious()))
                .map(index -> DropMetric.of(stages.get(index - 1), stages.get(index)))
                .orElse(null);
    }
}
