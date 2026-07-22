package com.triples.rougether.userapi.gacha.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import com.triples.rougether.domain.gacha.entity.GachaRarity;
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
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 뽑기 조회 + 실행. 2단계 추첨(등급 70/25/5 -> 등급 pool 균등), COIN 차감.
// 보상은 아이템(가구) 또는 캐릭터. 중복 보유 시 아이템은 다이아, 캐릭터는 코인으로 전환(프론트 재화 규칙).
@Service
public class GachaService {

    private static final int ITEM_REFUND_DIA = 30;           // 아이템 중복 -> 다이아 전환 (프론트 DUPLICATE_DIA)
    private static final int CHARACTER_REFUND_COIN = 200;    // 캐릭터 중복 -> 코인 환급 (spec)
    private static final int TIER_NORMAL_MAX = 70;   // roll 0~69 -> 일반
    private static final int TIER_RARE_MAX = 95;     // 70~94 -> 희귀, 95~99 -> 전설
    private static final int MULTI_COUNT = 10;
    private static final int MULTI_MULTIPLIER = 5;   // 10연 = 단챠 비용 x5

    private final GachaRepository gachaRepository;
    private final GachaPoolEntryRepository poolRepository;
    private final UserItemRepository userItemRepository;
    private final UserCharacterRepository userCharacterRepository;
    private final UserWalletRepository walletRepository;
    private final UserRepository userRepository;
    private final Random random = new Random();

    public GachaService(GachaRepository gachaRepository,
                        GachaPoolEntryRepository poolRepository,
                        UserItemRepository userItemRepository,
                        UserCharacterRepository userCharacterRepository,
                        UserWalletRepository walletRepository,
                        UserRepository userRepository) {
        this.gachaRepository = gachaRepository;
        this.poolRepository = poolRepository;
        this.userItemRepository = userItemRepository;
        this.userCharacterRepository = userCharacterRepository;
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
        // 캐릭터 보유 판정(중복 환급)을 다른 획득 경로(온보딩 선택·착용 교체·어드민 지급)와 직렬화한다 —
        // 전부 같은 user 행 락을 잡으므로 동시 지급이 같은 캐릭터를 2행 만들 수 없다. 락 순서: user → wallet.
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));
        // 행 락으로 동시 뽑기의 이중 차감을 막는다.
        UserWallet wallet = walletRepository.findWithLockByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseThrow(() -> new BusinessException(GachaErrorCode.INSUFFICIENT_COIN));
        if (wallet.getBalance() < cost) {
            throw new BusinessException(GachaErrorCode.INSUFFICIENT_COIN);
        }
        wallet.spend(cost);

        List<GachaPoolEntry> pool = poolRepository.findByGachaIdAndActiveIsTrue(gachaId).stream()
                .filter(this::hasReward)
                .toList();
        if (pool.isEmpty()) {
            throw new BusinessException(GachaErrorCode.EMPTY_POOL);
        }
        Map<String, List<GachaPoolEntry>> byRarity = pool.stream()
                .collect(Collectors.groupingBy(
                        entry -> entry.getRarity() == null ? GachaRarity.NORMAL : entry.getRarity()));

        Set<Long> ownedItemIds = userItemRepository.findByUserIdAndDeletedAtIsNull(userId).stream()
                .map(ui -> ui.getItem().getId())
                .collect(Collectors.toCollection(HashSet::new));
        Set<Long> ownedCharacterIds = userCharacterRepository.findByUserId(userId).stream()
                .filter(uc -> uc.getDeletedAt() == null)
                .map(uc -> uc.getCharacter().getId())
                .collect(Collectors.toCollection(HashSet::new));
        User user = userRepository.getReferenceById(userId);

        List<DrawResult> results = new ArrayList<>();
        int coinRefund = 0;
        int diaRefund = 0;
        for (int i = 0; i < count; i++) {
            GachaPoolEntry picked = pickEntry(pool, byRarity);
            if (picked.getRewardType() == RewardType.CHARACTER) {
                coinRefund += drawCharacter(user, picked, ownedCharacterIds, results);
            } else {
                diaRefund += drawItem(user, picked, ownedItemIds, results);
            }
        }
        wallet.add(coinRefund);

        // 전환 적립도 행 락으로 조회해 동시 요청의 적립 유실을 막는다. 지갑이 없으면 최초 전환 시점에 발급
        // (가입 시엔 코인 지갑만 생성됨. 동시 발급은 uq_user_wallets_user_currency 가 막는다).
        UserWallet diaWallet = walletRepository.findWithLockByUserIdAndCurrencyType(userId, CurrencyType.DIAMOND)
                .orElse(null);
        if (diaRefund > 0) {
            if (diaWallet == null) {
                diaWallet = walletRepository.save(UserWallet.create(user, CurrencyType.DIAMOND));
            }
            diaWallet.add(diaRefund);
        }

        return new GachaDrawResponse(results, List.of(
                new WalletSummary(CurrencyType.COIN, wallet.getBalance()),
                new WalletSummary(CurrencyType.DIAMOND, diaWallet == null ? 0 : diaWallet.getBalance())));
    }

    private boolean hasReward(GachaPoolEntry e) {
        return (e.getRewardType() == RewardType.ITEM && e.getItem() != null)
                || (e.getRewardType() == RewardType.CHARACTER && e.getCharacter() != null);
    }

    // 아이템(가구) 지급. 이미 보유 시 다이아로 전환하고 전환액 반환.
    private int drawItem(User user, GachaPoolEntry picked, Set<Long> ownedItemIds, List<DrawResult> results) {
        Item item = picked.getItem();
        if (ownedItemIds.contains(item.getId())) {
            results.add(new DrawResult("CURRENCY", item.getId(), null, item.getName(),
                    item.getAssetKey(), picked.getRarity(), true, CurrencyType.DIAMOND, ITEM_REFUND_DIA));
            return ITEM_REFUND_DIA;
        }
        userItemRepository.save(UserItem.create(user, item));
        ownedItemIds.add(item.getId());
        results.add(new DrawResult("ITEM", item.getId(), null, item.getName(),
                item.getAssetKey(), picked.getRarity(), false, null, null));
        return 0;
    }

    // 캐릭터 지급. 이미 보유 시 코인 환급하고 환급액 반환.
    private int drawCharacter(User user, GachaPoolEntry picked, Set<Long> ownedCharacterIds, List<DrawResult> results) {
        Character character = picked.getCharacter();
        if (ownedCharacterIds.contains(character.getId())) {
            results.add(new DrawResult("CURRENCY", null, character.getId(), character.getName(),
                    character.getBaseAssetKey(), picked.getRarity(), true, CurrencyType.COIN, CHARACTER_REFUND_COIN));
            return CHARACTER_REFUND_COIN;
        }
        userCharacterRepository.save(UserCharacter.create(user, character));
        ownedCharacterIds.add(character.getId());
        results.add(new DrawResult("CHARACTER", null, character.getId(), character.getName(),
                character.getBaseAssetKey(), picked.getRarity(), false, null, null));
        return 0;
    }

    // 등급을 먼저 뽑고(일반70/희귀25/전설5) 해당 등급 pool 에서 균등 추첨.
    // 등급 pool 이 비면(예: 캐릭터 뽑기처럼 rarity 미부여) 전체 pool 에서 균등.
    private GachaPoolEntry pickEntry(List<GachaPoolEntry> pool, Map<String, List<GachaPoolEntry>> byRarity) {
        int roll = random.nextInt(100);
        String rarity = roll < TIER_NORMAL_MAX
                ? GachaRarity.NORMAL
                : roll < TIER_RARE_MAX ? GachaRarity.RARE : GachaRarity.LEGENDARY;
        List<GachaPoolEntry> tier = byRarity.getOrDefault(rarity, pool);
        if (tier.isEmpty()) {
            tier = pool;
        }
        return tier.get(random.nextInt(tier.size()));
    }
}
