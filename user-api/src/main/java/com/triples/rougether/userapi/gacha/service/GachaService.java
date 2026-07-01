package com.triples.rougether.userapi.gacha.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import com.triples.rougether.domain.gacha.entity.RewardType;
import com.triples.rougether.domain.gacha.repository.GachaPoolEntryRepository;
import com.triples.rougether.domain.gacha.repository.GachaRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.gacha.dto.GachaDrawRequest;
import com.triples.rougether.userapi.gacha.dto.GachaDrawResponse;
import com.triples.rougether.userapi.gacha.dto.GachaDrawResponse.DrawResult;
import com.triples.rougether.userapi.gacha.dto.GachaDrawResponse.WalletSummary;
import com.triples.rougether.userapi.gacha.dto.GachaListResponse;
import com.triples.rougether.userapi.gacha.dto.GachaResponse;
import com.triples.rougether.userapi.gacha.error.GachaErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 뽑기 조회 + 실행. 2단계 추첨(등급 70/25/5 -> 등급 pool 균등), COIN 차감, 중복 시 40 COIN 환급.
@Service
public class GachaService {

    private static final int REFUND_COIN = 40;
    private static final int TIER_NORMAL_MAX = 70;   // roll 0~69 -> 일반
    private static final int TIER_RARE_MAX = 95;     // 70~94 -> 희귀, 95~99 -> 전설
    private static final int MULTI_COUNT = 10;
    private static final int MULTI_MULTIPLIER = 5;    // 10연 = 단챠 비용 x5 (250 -> 1250)

    private final GachaRepository gachaRepository;
    private final GachaPoolEntryRepository poolRepository;
    private final UserItemRepository userItemRepository;
    private final UserWalletRepository walletRepository;
    private final UserRepository userRepository;
    private final Random random = new Random();

    public GachaService(GachaRepository gachaRepository,
                        GachaPoolEntryRepository poolRepository,
                        UserItemRepository userItemRepository,
                        UserWalletRepository walletRepository,
                        UserRepository userRepository) {
        this.gachaRepository = gachaRepository;
        this.poolRepository = poolRepository;
        this.userItemRepository = userItemRepository;
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public GachaListResponse getGachaList() {
        List<GachaResponse> items = gachaRepository.findAll().stream()
                .filter(Gacha::isActive)
                .map(GachaResponse::of)
                .toList();
        return new GachaListResponse(items);
    }

    @Transactional(readOnly = true)
    public GachaResponse getGacha(Long gachaId) {
        Gacha gacha = gachaRepository.findById(gachaId)
                .orElseThrow(() -> new BusinessException(GachaErrorCode.GACHA_NOT_FOUND));
        return GachaResponse.of(gacha);
    }

    @Transactional
    public GachaDrawResponse draw(Long userId, Long gachaId, GachaDrawRequest request) {
        int count = request.count() == null ? 0 : request.count();
        if (count != 1 && count != MULTI_COUNT) {
            throw new BusinessException(GachaErrorCode.INVALID_DRAW_COUNT);
        }

        Gacha gacha = gachaRepository.findById(gachaId)
                .orElseThrow(() -> new BusinessException(GachaErrorCode.GACHA_NOT_FOUND));
        if (!gacha.isActive()) {
            throw new BusinessException(GachaErrorCode.GACHA_INACTIVE);
        }

        int cost = count == 1 ? gacha.getCostAmount() : gacha.getCostAmount() * MULTI_MULTIPLIER;
        UserWallet wallet = walletRepository.findByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseThrow(() -> new BusinessException(GachaErrorCode.INSUFFICIENT_COIN));
        if (wallet.getBalance() < cost) {
            throw new BusinessException(GachaErrorCode.INSUFFICIENT_COIN);
        }
        wallet.subtract(cost);

        List<GachaPoolEntry> pool = poolRepository.findByGachaIdAndActiveIsTrue(gachaId).stream()
                .filter(e -> e.getRewardType() == RewardType.ITEM && e.getItem() != null)
                .toList();
        if (pool.isEmpty()) {
            throw new BusinessException(GachaErrorCode.EMPTY_POOL);
        }
        Map<String, List<GachaPoolEntry>> byRarity = pool.stream()
                .collect(Collectors.groupingBy(e -> e.getRarity() == null ? "일반" : e.getRarity()));

        Set<Long> ownedIds = userItemRepository.findByUserIdAndDeletedAtIsNull(userId).stream()
                .map(ui -> ui.getItem().getId())
                .collect(Collectors.toCollection(HashSet::new));
        User user = userRepository.getReferenceById(userId);

        List<DrawResult> results = new ArrayList<>();
        int refundTotal = 0;
        for (int i = 0; i < count; i++) {
            GachaPoolEntry picked = pickEntry(pool, byRarity);
            Item item = picked.getItem();
            if (ownedIds.contains(item.getId())) {
                refundTotal += REFUND_COIN;
                results.add(new DrawResult("CURRENCY", item.getId(), item.getName(),
                        item.getAssetKey(), picked.getRarity(), true, REFUND_COIN));
            } else {
                userItemRepository.save(UserItem.create(user, item));
                ownedIds.add(item.getId());
                results.add(new DrawResult("ITEM", item.getId(), item.getName(),
                        item.getAssetKey(), picked.getRarity(), false, null));
            }
        }
        wallet.add(refundTotal);

        return new GachaDrawResponse(results, new WalletSummary(CurrencyType.COIN, wallet.getBalance()));
    }

    // 등급을 먼저 뽑고(일반70/희귀25/전설5) 해당 등급 pool 에서 균등 추첨. 등급 pool 이 비면 전체에서.
    private GachaPoolEntry pickEntry(List<GachaPoolEntry> pool, Map<String, List<GachaPoolEntry>> byRarity) {
        int roll = random.nextInt(100);
        String rarity = roll < TIER_NORMAL_MAX ? "일반" : roll < TIER_RARE_MAX ? "희귀" : "전설";
        List<GachaPoolEntry> tier = byRarity.getOrDefault(rarity, pool);
        if (tier.isEmpty()) {
            tier = pool;
        }
        return tier.get(random.nextInt(tier.size()));
    }
}
