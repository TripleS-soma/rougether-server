package com.triples.rougether.adminapi.user.service;

import com.triples.rougether.adminapi.user.dto.AdminUserAccountStatus;
import com.triples.rougether.adminapi.user.dto.AdminUserActivityFilter;
import com.triples.rougether.adminapi.user.dto.AdminUserListResponse;
import com.triples.rougether.adminapi.user.dto.AdminUserListResponse.AdminUserRow;
import com.triples.rougether.adminapi.user.dto.AdminUserOnboardingFilter;
import com.triples.rougether.adminapi.user.dto.AdminUserSummaryResponse;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.support.UserMetricCount;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 어드민 유저 관측 - 계정 상태·활성화 지표는 원문 활동 내용을 노출하지 않고 건수로만 제공함.
@Service
@Transactional(readOnly = true)
public class AdminUserQueryService {

    private static final int RECENT_WINDOW_DAYS = 7;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UserRepository userRepository;
    private final UserWalletRepository userWalletRepository;
    private final RoutineRepository routineRepository;
    private final RoutineLogRepository routineLogRepository;
    private final TodoRepository todoRepository;
    private final HouseMemberRepository houseMemberRepository;

    public AdminUserQueryService(UserRepository userRepository,
                                 UserWalletRepository userWalletRepository,
                                 RoutineRepository routineRepository,
                                 RoutineLogRepository routineLogRepository,
                                 TodoRepository todoRepository,
                                 HouseMemberRepository houseMemberRepository) {
        this.userRepository = userRepository;
        this.userWalletRepository = userWalletRepository;
        this.routineRepository = routineRepository;
        this.routineLogRepository = routineLogRepository;
        this.todoRepository = todoRepository;
        this.houseMemberRepository = houseMemberRepository;
    }

    public AdminUserSummaryResponse getSummary() {
        Instant now = Instant.now();
        Instant sevenDaysAgo = now.minus(Duration.ofDays(RECENT_WINDOW_DAYS));
        long activeUsers = userRepository.countByDeletedAtIsNull();
        return AdminUserSummaryResponse.of(
                activeUsers,
                userRepository.countByDeletedAtIsNullAndCreatedAtGreaterThanEqual(sevenDaysAgo),
                userRepository.countByDeletedAtIsNullAndLastAccessedAtGreaterThanEqual(sevenDaysAgo),
                userRepository.countOnboardingCompletedUsers(),
                now);
    }

    public AdminUserListResponse getUsers(String query,
                                          AdminUserAccountStatus accountStatus,
                                          AdminUserActivityFilter activity,
                                          AdminUserOnboardingFilter onboarding,
                                          int page,
                                          int size) {
        String normalized = (query == null || query.isBlank()) ? null : query.trim();
        Instant now = Instant.now();
        Page<User> users = userRepository.searchForAdmin(
                normalized,
                accountStatus.name(),
                activity == AdminUserActivityFilter.NEVER,
                accessedAfter(activity, now),
                onboarding.name(),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));

        List<Long> userIds = users.getContent().stream().map(User::getId).toList();
        if (userIds.isEmpty()) {
            return new AdminUserListResponse(List.of(), page, size, users.getTotalElements());
        }

        Map<Long, Map<CurrencyType, Long>> balances = userWalletRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.groupingBy(wallet -> wallet.getUser().getId(),
                        Collectors.toMap(UserWallet::getCurrencyType, wallet -> (long) wallet.getBalance())));
        Set<Long> onboardingCompletedUserIds = Set.copyOf(
                userRepository.findOnboardingCompletedUserIds(userIds));
        Map<Long, Long> activeRoutineCounts = toCountMap(
                routineRepository.countActiveByUserIds(userIds, RoutineStatus.ACTIVE));
        Map<Long, Long> activeHouseCounts = toCountMap(
                houseMemberRepository.countActiveByUserIds(userIds, HouseMemberStatus.ACTIVE));

        LocalDate recentStartDate = LocalDate.now(KST).minusDays(RECENT_WINDOW_DAYS - 1L);
        Instant recentStart = recentStartDate.atStartOfDay(KST).toInstant();
        Map<Long, Long> recentCompletionCounts = mergeCounts(
                routineLogRepository.countCompletedByUserIdsSince(
                        userIds, RoutineLogStatus.COMPLETED, recentStartDate),
                todoRepository.countCompletedByUserIdsSince(
                        userIds, TodoStatus.COMPLETED, recentStart));

        List<AdminUserRow> items = users.getContent().stream()
                .map(user -> toRow(
                        user,
                        balances.getOrDefault(user.getId(), Map.of()),
                        onboardingCompletedUserIds.contains(user.getId()),
                        activeRoutineCounts.getOrDefault(user.getId(), 0L),
                        activeHouseCounts.getOrDefault(user.getId(), 0L),
                        recentCompletionCounts.getOrDefault(user.getId(), 0L)))
                .toList();
        return new AdminUserListResponse(items, page, size, users.getTotalElements());
    }

    private AdminUserRow toRow(User user,
                               Map<CurrencyType, Long> wallets,
                               boolean onboardingCompleted,
                               long activeRoutineCount,
                               long activeHouseCount,
                               long recentCompletionCount) {
        AdminUserAccountStatus accountStatus = user.isDeleted()
                ? AdminUserAccountStatus.WITHDRAWN
                : AdminUserAccountStatus.ACTIVE;
        return new AdminUserRow(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getCreatedAt(),
                user.getLastAccessedAt(),
                user.getDeletedAt(),
                accountStatus,
                onboardingCompleted,
                activeRoutineCount,
                activeHouseCount,
                recentCompletionCount,
                wallets.getOrDefault(CurrencyType.COIN, 0L),
                wallets.getOrDefault(CurrencyType.DIAMOND, 0L));
    }

    private Instant accessedAfter(AdminUserActivityFilter activity, Instant now) {
        return switch (activity) {
            case WITHIN_1_DAY -> now.minus(Duration.ofDays(1));
            case WITHIN_7_DAYS -> now.minus(Duration.ofDays(7));
            case WITHIN_30_DAYS -> now.minus(Duration.ofDays(30));
            case ALL, NEVER -> null;
        };
    }

    private Map<Long, Long> toCountMap(Collection<UserMetricCount> counts) {
        return counts.stream().collect(Collectors.toMap(
                UserMetricCount::getUserId,
                UserMetricCount::getMetricCount));
    }

    private Map<Long, Long> mergeCounts(Collection<UserMetricCount> first,
                                        Collection<UserMetricCount> second) {
        Map<Long, Long> result = new HashMap<>();
        first.forEach(count -> result.merge(count.getUserId(), count.getMetricCount(), Long::sum));
        second.forEach(count -> result.merge(count.getUserId(), count.getMetricCount(), Long::sum));
        return result;
    }
}
