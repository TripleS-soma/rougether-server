package com.triples.rougether.adminapi.user.service;

import com.triples.rougether.adminapi.user.dto.AdminUserListResponse;
import com.triples.rougether.adminapi.user.dto.AdminUserListResponse.AdminUserRow;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 어드민 유저 조회 - 검색(email/nickname) + 잔액 표시. QA 재화 지급 대상 찾기용.
@Service
public class AdminUserQueryService {

    private final UserRepository userRepository;
    private final UserWalletRepository userWalletRepository;

    public AdminUserQueryService(UserRepository userRepository, UserWalletRepository userWalletRepository) {
        this.userRepository = userRepository;
        this.userWalletRepository = userWalletRepository;
    }

    @Transactional(readOnly = true)
    public AdminUserListResponse getUsers(String query, int page, int size) {
        String normalized = (query == null || query.isBlank()) ? null : query.trim();
        Page<User> users = userRepository.searchForAdmin(
                normalized, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));

        List<Long> userIds = users.getContent().stream().map(User::getId).toList();
        // 페이지의 지갑을 한 번에 조회해 N+1 을 피한다. 지갑 미발급 재화는 0.
        Map<Long, Map<CurrencyType, Long>> balances = userIds.isEmpty() ? Map.of()
                : userWalletRepository.findByUserIdIn(userIds).stream()
                        .collect(Collectors.groupingBy(wallet -> wallet.getUser().getId(),
                                Collectors.toMap(UserWallet::getCurrencyType,
                                        wallet -> (long) wallet.getBalance())));

        List<AdminUserRow> items = users.getContent().stream()
                .map(user -> {
                    Map<CurrencyType, Long> wallets = balances.getOrDefault(user.getId(), Map.of());
                    return new AdminUserRow(
                            user.getId(), user.getEmail(), user.getNickname(), user.getCreatedAt(),
                            wallets.getOrDefault(CurrencyType.COIN, 0L),
                            wallets.getOrDefault(CurrencyType.DIAMOND, 0L));
                })
                .toList();
        return new AdminUserListResponse(items, page, size, users.getTotalElements());
    }
}
