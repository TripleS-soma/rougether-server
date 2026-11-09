package com.triples.rougether.userapi.gacha.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.gacha.entity.GachaCategory;
import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import com.triples.rougether.domain.gacha.entity.RewardType;
import com.triples.rougether.domain.gacha.repository.GachaPoolEntryRepository;
import com.triples.rougether.domain.gacha.repository.GachaRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.gacha.dto.GachaDrawRequest;
import com.triples.rougether.userapi.gacha.dto.GachaDrawResponse;
import com.triples.rougether.userapi.gacha.dto.GachaDrawResponse.DrawResult;
import com.triples.rougether.userapi.gacha.dto.GachaDrawResponse.WalletSummary;
import com.triples.rougether.userapi.gacha.dto.GachaListResponse;
import com.triples.rougether.userapi.gacha.dto.GachaRewardListResponse;
import com.triples.rougether.userapi.gacha.dto.GachaRewardListResponse.GachaRewardResponse;
import com.triples.rougether.userapi.gacha.dto.GachaResponse;
import com.triples.rougether.userapi.character.service.CharacterUnlockPolicy;
import com.triples.rougether.userapi.gacha.error.GachaErrorCode;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.time.Instant;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import com.triples.rougether.domain.shop.policy.EventItemEligibility;
import com.triples.rougether.userapi.christmas.service.ChristmasEventPolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.triples.rougether.userapi.global.i18n.CatalogNames;

// 뽑기 조회와 거래 실행. 후보 선택은 GachaDrawPolicy에 위임함.
// 보상은 아이템(가구) 또는 캐릭터. 중복 보유 시 아이템은 다이아, 캐릭터는 코인으로 전환(프론트 재화 규칙).
@Service
public class GachaService {

    // 환급값은 단가에 연동한다 — 단가만 낮추면 "뽑기 = 코인을 다이아로 바꾸는 환전기"가 된다.
    private static final int ITEM_REFUND_DIA = 3;            // 아이템 중복 -> 다이아 전환 (단가 25 의 12%, 프론트 DUPLICATE_DIA)
    private static final int CHARACTER_REFUND_COIN = 100;    // 캐릭터 중복 -> 코인 환급 (단가 500 의 20%, spec)
    private static final int SINGLE_DRAW_COUNT = 1;
    private static final int BONUS_DRAW_COUNT = 6;
    private static final int LEGACY_MULTI_DRAW_COUNT = 10;
    private static final int BONUS_DRAW_COST_MULTIPLIER = 5;   // 5+1회 = 단챠 비용 x5

    private final GachaRepository gachaRepository;
    private final GachaPoolEntryRepository poolRepository;
    private final UserItemRepository userItemRepository;
    private final UserCharacterRepository userCharacterRepository;
    private final UserWalletRepository walletRepository;
    private final UserRepository userRepository;
    private final WalletHistoryRecorder walletHistoryRecorder;
    private final CharacterUnlockPolicy characterUnlockPolicy;
    private final GachaDrawPolicy gachaDrawPolicy;
    private final ChristmasGachaDrawPolicy christmasDrawPolicy;
    private final ChristmasEventPolicy christmasEventPolicy;
    private final Clock clock;

    public GachaService(GachaRepository gachaRepository,
                        GachaPoolEntryRepository poolRepository,
                        UserItemRepository userItemRepository,
                        UserCharacterRepository userCharacterRepository,
                        UserWalletRepository walletRepository,
                        UserRepository userRepository,
                        WalletHistoryRecorder walletHistoryRecorder,
                        CharacterUnlockPolicy characterUnlockPolicy,
                        GachaDrawPolicy gachaDrawPolicy) {
        this(gachaRepository, poolRepository, userItemRepository, userCharacterRepository,
                walletRepository, userRepository, walletHistoryRecorder, characterUnlockPolicy,
                gachaDrawPolicy, new ChristmasGachaDrawPolicy(), new ChristmasEventPolicy(false), Clock.systemUTC());
    }

    @Autowired
    public GachaService(GachaRepository gachaRepository,
                        GachaPoolEntryRepository poolRepository,
                        UserItemRepository userItemRepository,
                        UserCharacterRepository userCharacterRepository,
                        UserWalletRepository walletRepository,
                        UserRepository userRepository,
                        WalletHistoryRecorder walletHistoryRecorder,
                        CharacterUnlockPolicy characterUnlockPolicy,
                        GachaDrawPolicy gachaDrawPolicy,
                        ChristmasGachaDrawPolicy christmasDrawPolicy,
                        ChristmasEventPolicy christmasEventPolicy,
                        Clock clock) {
        this.gachaRepository = gachaRepository;
        this.poolRepository = poolRepository;
        this.userItemRepository = userItemRepository;
        this.userCharacterRepository = userCharacterRepository;
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
        this.walletHistoryRecorder = walletHistoryRecorder;
        this.characterUnlockPolicy = characterUnlockPolicy;
        this.gachaDrawPolicy = gachaDrawPolicy;
        this.christmasDrawPolicy = christmasDrawPolicy;
        this.christmasEventPolicy = christmasEventPolicy;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GachaListResponse getGachaList() {
        Instant now = clock.instant();
        List<GachaResponse> items = gachaRepository.findAllWithTheme().stream()
                .filter(gacha -> gacha.getCategory() == null)
                .filter(gacha -> isAvailable(gacha, now))
                .map(gacha -> GachaResponse.of(gacha, GachaGiftBoxCatalog.assetKeyFor(gacha)))
                .toList();
        return new GachaListResponse(items);
    }

    @Transactional(readOnly = true)
    public GachaListResponse getCategoryGachaList() {
        Instant now = clock.instant();
        List<GachaResponse> items = gachaRepository.findAllWithTheme().stream()
                .filter(gacha -> gacha.getCategory() != null)
                .filter(gacha -> isAvailable(gacha, now))
                .sorted(Comparator.comparing(Gacha::getCategory))
                .map(gacha -> GachaResponse.of(gacha, GachaGiftBoxCatalog.assetKeyFor(gacha)))
                .toList();
        return new GachaListResponse(items);
    }

    @Transactional(readOnly = true)
    public GachaResponse getGacha(Long gachaId) {
        Gacha gacha = gachaRepository.findById(gachaId)
                .orElseThrow(() -> new BusinessException(GachaErrorCode.GACHA_NOT_FOUND));
        return GachaResponse.of(gacha, GachaGiftBoxCatalog.assetKeyFor(gacha),
                (isChristmas(gacha) || isChristmasTheme(gacha))
                        ? isAvailable(gacha, clock.instant()) : gacha.isActive());
    }

    @Transactional(readOnly = true)
    public GachaRewardListResponse getRewards(Long userId, Long gachaId) {
        Gacha gacha = gachaRepository.findById(gachaId)
                .orElseThrow(() -> new BusinessException(GachaErrorCode.GACHA_NOT_FOUND));
        if (!isAvailable(gacha, clock.instant())) {
            throw new BusinessException(GachaErrorCode.GACHA_INACTIVE);
        }

        Set<Long> ownedItemIds = new HashSet<>(userItemRepository.findOwnedItemIdsByUserId(userId));
        Set<Long> ownedCharacterIds =
                new HashSet<>(userCharacterRepository.findOwnedCharacterIdsByUserId(userId));

        List<GachaRewardResponse> rewards;
        if (isChristmas(gacha)) {
            EventPools pools = eventPools(gacha, clock.instant());
            var prepared = christmasDrawPolicy.prepare(pools.christmas(), pools.regular());
            rewards = new ArrayList<>();
            for (GachaPoolEntry entry : pools.christmas()) {
                rewards.add(toRewardResponse(entry, ownedItemIds, ownedCharacterIds)
                        .withProbabilityPercent(prepared.probabilityPercent(entry, true)));
            }
            for (GachaPoolEntry entry : pools.regular()) {
                rewards.add(toRewardResponse(entry, ownedItemIds, ownedCharacterIds)
                        .withProbabilityPercent(prepared.probabilityPercent(entry, false)));
            }
        } else {
            rewards = poolRepository.findActiveRewardsByGachaId(gachaId).stream()
                    .filter(entry -> isEligibleReward(gacha, entry))
                    .map(entry -> toRewardResponse(entry, ownedItemIds, ownedCharacterIds)).toList();
        }
        return new GachaRewardListResponse(rewards);
    }

    @Transactional
    public GachaDrawResponse draw(Long userId, Long gachaId, GachaDrawRequest request) {
        int count = request.count() == null ? 0 : request.count();
        boolean bonusDraw = count == BONUS_DRAW_COUNT || count == LEGACY_MULTI_DRAW_COUNT;
        if (count != SINGLE_DRAW_COUNT && !bonusDraw) {
            throw new BusinessException(GachaErrorCode.INVALID_DRAW_COUNT);
        }

        // 캐릭터 보유 판정(중복 환급)을 다른 획득 경로(온보딩 선택·착용 교체·어드민 지급)와 직렬화한다 —
        // 첫 일반 SELECT 전에 user → wallet 순서로 잠가 RR snapshot도 이전 지급 커밋 뒤에 생성함.
        userRepository.findByIdForUpdate(userId)
                .filter(user -> !user.isDeleted())
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));
        // 행 락으로 동시 뽑기의 이중 차감을 막는다.
        UserWallet wallet = walletRepository.findWithLockByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseThrow(() -> new BusinessException(GachaErrorCode.INSUFFICIENT_COIN));

        Gacha gacha = gachaRepository.findById(gachaId)
                .orElseThrow(() -> new BusinessException(GachaErrorCode.GACHA_NOT_FOUND));
        Instant drawTime = clock.instant();
        if (!isAvailable(gacha, drawTime)) {
            throw new BusinessException(GachaErrorCode.GACHA_INACTIVE);
        }

        int resultCount = bonusDraw ? BONUS_DRAW_COUNT : SINGLE_DRAW_COUNT;
        int cost = bonusDraw
                ? gacha.getCostAmount() * BONUS_DRAW_COST_MULTIPLIER
                : gacha.getCostAmount();

        EventPools eventPools = isChristmas(gacha) ? eventPools(gacha, drawTime) : null;
        List<GachaPoolEntry> pool = eventPools != null
                ? eventPools.christmas() : poolRepository.findByGachaIdAndActiveIsTrue(gachaId).stream()
                .filter(entry -> isEligibleReward(gacha, entry)).toList();
        // 그룹과 모든 양수 확률 등급이 준비된 뒤에만 차감한다.
        Supplier<GachaPoolEntry> picker = eventPools != null
                ? christmasDrawPolicy.prepare(eventPools.christmas(), eventPools.regular())::pick
                : gachaDrawPolicy.prepare(pool)::pick;
        // 캐릭터가 나올 수 있는 머신은 방 레벨 10부터. 코인 차감 전에 막고, 방은 잠그지 않고 읽어 락 순서를 유지함.
        // 잔액 부족보다 먼저 판정해 해금 전 사용자가 "코인을 모으면 된다"로 오해하지 않게 함. 미리보기는 열어 둠.
        if (pool.stream().anyMatch(entry -> entry.getRewardType() == RewardType.CHARACTER)
                && !characterUnlockPolicy.isUnlocked(userId)) {
            throw new BusinessException(GachaErrorCode.CHARACTER_GACHA_LOCKED);
        }
        if (wallet.getBalance() < cost) {
            throw new BusinessException(GachaErrorCode.INSUFFICIENT_COIN);
        }
        if (pool.isEmpty()) {
            throw new BusinessException(GachaErrorCode.EMPTY_POOL);
        }
        wallet.spend(cost);
        walletHistoryRecorder.record(wallet, -cost, WalletHistoryReason.GACHA_DRAW,
                WalletHistory.SOURCE_GACHA, gachaId);

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
        for (int i = 0; i < resultCount; i++) {
            GachaPoolEntry picked = picker.get();
            if (picked.getRewardType() == RewardType.CHARACTER) {
                coinRefund += drawCharacter(user, picked, ownedCharacterIds, results);
            } else {
                diaRefund += drawItem(user, picked, ownedItemIds, results);
            }
        }
        wallet.add(coinRefund);
        // 여러 번 뽑기의 중복 전환은 재화별 합산 1 row 로 기록함(0 이면 recorder 가 건너뜀)
        walletHistoryRecorder.record(wallet, coinRefund, WalletHistoryReason.GACHA_DUPLICATE_CONVERT,
                WalletHistory.SOURCE_GACHA, gachaId);

        // 전환 적립도 행 락으로 조회해 동시 요청의 적립 유실을 막는다. 지갑이 없으면 최초 전환 시점에 발급
        // (가입 시엔 코인 지갑만 생성됨. 동시 발급은 uq_user_wallets_user_currency 가 막는다).
        UserWallet diaWallet = walletRepository.findWithLockByUserIdAndCurrencyType(userId, CurrencyType.DIAMOND)
                .orElse(null);
        if (diaRefund > 0) {
            if (diaWallet == null) {
                diaWallet = walletRepository.save(UserWallet.create(user, CurrencyType.DIAMOND));
            }
            diaWallet.add(diaRefund);
            walletHistoryRecorder.record(diaWallet, diaRefund, WalletHistoryReason.GACHA_DUPLICATE_CONVERT,
                    WalletHistory.SOURCE_GACHA, gachaId);
        }

        return new GachaDrawResponse(results, List.of(
                new WalletSummary(CurrencyType.COIN, wallet.getBalance()),
                new WalletSummary(CurrencyType.DIAMOND, diaWallet == null ? 0 : diaWallet.getBalance())));
    }

    private boolean isChristmas(Gacha gacha) {
        return ChristmasEventPolicy.CODE.equals(gacha.getCode());
    }

    private boolean isChristmasTheme(Gacha gacha) {
        return gacha.getTheme() != null
                && EventItemEligibility.CHRISTMAS_THEME_CODE.equals(gacha.getTheme().getCode());
    }

    private boolean isAvailable(Gacha gacha, Instant now) {
        // 카탈로그 import가 만든 옛 테마 머신도 이벤트의 별도 진입 경로가 될 수 없음.
        if (!isChristmas(gacha) && isChristmasTheme(gacha)) {
            return false;
        }
        return gacha.isAvailableAt(now) && (!isChristmas(gacha)
                || (christmasEventPolicy.isOpen(now) && gacha.getTheme() != null
                && gacha.getTheme().isActive()
                && EventItemEligibility.CHRISTMAS_THEME_CODE.equals(gacha.getTheme().getCode())));
    }

    // 팝업/이벤트 메타도 실제 추첨과 동일한 후보 구성으로 준비 여부를 판정함.
    @Transactional(readOnly = true)
    public boolean isChristmasReady(Gacha gacha, Instant now) {
        if (gacha == null || !isChristmas(gacha) || !isAvailable(gacha, now)) {
            return false;
        }
        try {
            EventPools pools = eventPools(gacha, now);
            christmasDrawPolicy.prepare(pools.christmas(), pools.regular());
            return true;
        } catch (BusinessException invalidPool) {
            return false;
        }
    }

    private EventPools eventPools(Gacha gacha, Instant now) {
        List<GachaPoolEntry> seasonal = poolRepository.findByGachaIdAndActiveIsTrue(gacha.getId()).stream()
                .filter(e -> e.getRewardType() == RewardType.ITEM && e.getItem() != null)
                .filter(e -> EventItemEligibility.isChristmas(e.getItem()))
                .filter(e -> ChristmasGachaCatalog.ASSET_KEYS.contains(e.getItem().getAssetKey()))
                .filter(e -> GachaCategory.fromItem(e.getItem()) != null)
                .toList();
        Set<Long> exclusiveRewards = new HashSet<>(poolRepository.findAttendanceRewardItemIds());
        Map<Long, GachaPoolEntry> regular = new LinkedHashMap<>();
        gachaRepository.findAllWithTheme().stream()
                .filter(machine -> machine.getCategory() != null && isAvailable(machine, now))
                .forEach(machine -> poolRepository.findByGachaIdAndActiveIsTrue(machine.getId()).stream()
                        .filter(e -> isEligibleReward(machine, e))
                        .filter(e -> e.getRewardType() == RewardType.ITEM)
                        .filter(e -> !exclusiveRewards.contains(e.getItem().getId()))
                        .forEach(e -> regular.putIfAbsent(e.getItem().getId(), e)));
        Map<Long, GachaPoolEntry> uniqueSeasonal = new LinkedHashMap<>();
        seasonal.forEach(e -> uniqueSeasonal.putIfAbsent(e.getItem().getId(), e));
        return new EventPools(List.copyOf(uniqueSeasonal.values()), List.copyOf(regular.values()));
    }

    private record EventPools(List<GachaPoolEntry> christmas, List<GachaPoolEntry> regular) { }

    private boolean hasReward(GachaPoolEntry e) {
        return (e.getRewardType() == RewardType.ITEM && e.getItem() != null)
                || (e.getRewardType() == RewardType.CHARACTER && e.getCharacter() != null);
    }

    private boolean isEligibleReward(Gacha gacha, GachaPoolEntry entry) {
        if (!hasReward(entry)) {
            return false;
        }
        if (entry.getRewardType() == RewardType.ITEM && EventItemEligibility.isChristmas(entry.getItem())) {
            return false;
        }
        if (entry.getRewardType() == RewardType.CHARACTER && entry.getCharacter().isRoomLevelReward()) {
            return false;
        }
        GachaCategory category = gacha.getCategory();
        return category == null || (entry.getRewardType() == RewardType.ITEM
                && category == GachaCategory.fromItem(entry.getItem()));
    }

    private GachaRewardResponse toRewardResponse(GachaPoolEntry entry,
                                                 Set<Long> ownedItemIds,
                                                 Set<Long> ownedCharacterIds) {
        if (entry.getRewardType() == RewardType.ITEM) {
            Item item = entry.getItem();
            return new GachaRewardResponse(
                    RewardType.ITEM.name(), item.getId(), null, CatalogNames.name(item), item.getAssetKey(),
                    entry.getRarity(), ownedItemIds.contains(item.getId()),
                    item.getCategoryCode(), item.getPlacementType(),
                    item.getSurfaceSlotType(), item.getCharacterSlotType());
        }

        Character character = entry.getCharacter();
        return new GachaRewardResponse(
                RewardType.CHARACTER.name(), null, character.getId(), CatalogNames.name(character),
                character.getBaseAssetKey(), entry.getRarity(),
                ownedCharacterIds.contains(character.getId()),
                null, null, null, null);
    }

    // 아이템(가구) 지급. 이미 보유 시 다이아로 전환하고 전환액 반환.
    private int drawItem(User user, GachaPoolEntry picked, Set<Long> ownedItemIds, List<DrawResult> results) {
        Item item = picked.getItem();
        if (ownedItemIds.contains(item.getId())) {
            results.add(new DrawResult("CURRENCY", item.getId(), null, CatalogNames.name(item),
                    item.getAssetKey(), picked.getRarity(), true, CurrencyType.DIAMOND, ITEM_REFUND_DIA));
            return ITEM_REFUND_DIA;
        }
        userItemRepository.save(UserItem.create(user, item));
        ownedItemIds.add(item.getId());
        results.add(new DrawResult("ITEM", item.getId(), null, CatalogNames.name(item),
                item.getAssetKey(), picked.getRarity(), false, null, null));
        return 0;
    }

    // 캐릭터 지급. 이미 보유 시 코인 환급하고 환급액 반환.
    private int drawCharacter(User user, GachaPoolEntry picked, Set<Long> ownedCharacterIds, List<DrawResult> results) {
        Character character = picked.getCharacter();
        if (ownedCharacterIds.contains(character.getId())) {
            results.add(new DrawResult("CURRENCY", null, character.getId(), CatalogNames.name(character),
                    character.getBaseAssetKey(), picked.getRarity(), true, CurrencyType.COIN, CHARACTER_REFUND_COIN));
            return CHARACTER_REFUND_COIN;
        }
        userCharacterRepository.save(UserCharacter.create(user, character));
        ownedCharacterIds.add(character.getId());
        results.add(new DrawResult("CHARACTER", null, character.getId(), CatalogNames.name(character),
                character.getBaseAssetKey(), picked.getRarity(), false, null, null));
        return 0;
    }
}
