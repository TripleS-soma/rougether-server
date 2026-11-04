package com.triples.rougether.userapi.market.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.common.error.AuthErrorCode;
import com.triples.rougether.domain.market.entity.*;
import com.triples.rougether.domain.market.repository.*;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.domain.member.entity.*;
import com.triples.rougether.domain.member.repository.*;
import com.triples.rougether.domain.shared.*;
import com.triples.rougether.domain.shop.entity.*;
import com.triples.rougether.domain.shop.repository.*;
import com.triples.rougether.userapi.market.dto.*;
import com.triples.rougether.userapi.market.error.MarketErrorCode;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 제작자는 원본을 보관하고 무제한 판매함. 구매는 사용자 → 상품 → 지갑 순서로 잠근 단일 트랜잭션임.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MarketShopService {
    private final MarketAssetRepository assets;
    private final MarketPurchaseRepository purchases;
    private final MarketOrderRepository orders;
    private final MarketCommandRepository commands;
    private final UserRepository users;
    private final UserWalletRepository wallets;
    private final UserItemRepository inventory;
    private final ItemRepository items;
    private final FurnitureGenerationJobRepository jobs;
    private final WalletHistoryRecorder histories;
    private final Clock clock;
    @Value("${market.fixed-price.enabled:true}") private boolean enabled;

    public MarketListingResponse.Listings list(Long viewer, boolean mine, int page, int size) {
        requireEnabled();
        var result = mine ? assets.findByCreatorUserIdOrderByIdDesc(viewer, PageRequest.of(page, size))
                : assets.findFixedPriceListings(viewer, PageRequest.of(page, size));
        var itemMap = items.findAllById(result.stream().map(MarketAsset::getItemId).toList()).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
        var ownerMap = users.findAllById(result.stream().map(MarketAsset::getCreatorUserId)
                .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Set<Long> owned = inventory.findByUserIdAndDeletedAtIsNull(viewer).stream()
                .map(i -> i.getItem().getId()).collect(Collectors.toSet());
        return new MarketListingResponse.Listings(result.stream().map(a -> view(a, viewer,
                itemMap.get(a.getItemId()), ownerMap.get(a.getCreatorUserId()), owned.contains(a.getItemId()))).toList(),
                page, size, result.getTotalElements());
    }

    public MarketListingResponse get(Long viewer, Long id) {
        requireEnabled();
        MarketAsset asset = asset(id);
        User creator = asset.getCreatorUserId() == null ? null : users.findById(asset.getCreatorUserId()).orElse(null);
        return view(asset, viewer, items.findById(asset.getItemId()).orElseThrow(), creator,
                inventory.existsByUserIdAndItemIdAndDeletedAtIsNull(viewer, asset.getItemId()));
    }

    @Transactional
    public MarketListingResponse sell(Long creatorId, Long userItemId, int price) {
        requireEnabled();
        active(lockedUser(creatorId));
        var original = inventory.findOwnedWithItem(creatorId, userItemId)
                .orElseThrow(() -> error(MarketErrorCode.ITEM_NOT_OWNED));
        Item item = original.getItem();
        if (!MarketAssetService.TRADABLE_THEME_CODE.equals(item.getTheme().getCode()))
            throw error(MarketErrorCode.ITEM_NOT_TRADABLE);
        var job = jobs.findGenerationJobIdByItemId(item.getId()).flatMap(jobs::findForUpdate)
                .orElseThrow(() -> error(MarketErrorCode.NOT_CREATOR));
        if (!job.getUserId().equals(creatorId)) throw error(MarketErrorCode.NOT_CREATOR);
        if (job.isInProgress()) throw error(MarketErrorCode.ITEM_REVIEW_IN_PROGRESS);
        MarketAsset asset = assets.findIdByItemId(item.getId()).map(id -> assets.findWithLockById(id).orElseThrow())
                .orElseGet(() -> assets.save(MarketAsset.issue(item.getId(), creatorId, 1, clock.instant())));
        if (!asset.isCreator(creatorId)) throw error(MarketErrorCode.NOT_CREATOR);
        if (asset.isSuspended()) throw error(MarketErrorCode.ASSET_SUSPENDED);
        asset.updateSale(price, true, clock.instant());
        return get(creatorId, asset.getId());
    }

    @Transactional
    public MarketListingResponse update(Long creatorId, Long id, int price, boolean selling) {
        requireEnabled();
        active(lockedUser(creatorId));
        MarketAsset asset = assets.findWithLockById(id).orElseThrow(() -> error(MarketErrorCode.ASSET_NOT_FOUND));
        if (!asset.isCreator(creatorId)) throw error(MarketErrorCode.NOT_CREATOR);
        if (asset.isSuspended()) throw error(MarketErrorCode.ASSET_SUSPENDED);
        asset.updateSale(price, selling, clock.instant());
        return get(creatorId, id);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public MarketPurchaseResponse buy(Long buyerId, Long id, String requestId, int expectedPrice) {
        requireEnabled();
        var replay = purchases.findByBuyerUserIdAndRequestId(buyerId, requestId);
        if (replay.isPresent()) return replay(replay.get(), id, expectedPrice);
        Long creatorId = assets.findCreatorId(id).orElseThrow(() -> error(MarketErrorCode.ASSET_NOT_FOUND));
        // 상호 구매도 같은 순서로 사용자 행을 잠금. 탈퇴 역시 사용자 행을 먼저 잠금.
        Map<Long, User> locked = new HashMap<>();
        for (Long uid : java.util.stream.Stream.of(buyerId, creatorId).distinct().sorted().toList())
            locked.put(uid, lockedUser(uid));
        active(locked.get(buyerId));
        replay = purchases.findByBuyerUserIdAndRequestId(buyerId, requestId);
        if (replay.isPresent()) return replay(replay.get(), id, expectedPrice);
        active(locked.get(creatorId));
        MarketAsset asset = assets.findWithLockById(id).orElseThrow(() -> error(MarketErrorCode.ASSET_NOT_FOUND));
        if (!creatorId.equals(asset.getCreatorUserId())) throw error(MarketErrorCode.ASSET_NOT_FOUND);
        if (asset.isCreator(buyerId)) throw error(MarketErrorCode.ALREADY_HOLDING);
        if (asset.isSuspended()) throw error(MarketErrorCode.ASSET_SUSPENDED);
        if (!asset.isSaleActive() || asset.getSalePrice() == null) throw error(MarketErrorCode.NOT_FOR_SALE);
        if (asset.getSalePrice() != expectedPrice) throw error(MarketErrorCode.PRICE_CHANGED);
        // 전환 중 맡긴 가구와 코인이 모두 정리된 뒤 새 구매를 받음.
        if (orders.existsByStatus(OrderStatus.OPEN) || commands.hasUnsettledLegacyEscrow())
            throw error(MarketErrorCode.TRANSITION_PENDING);
        if (inventory.existsByUserIdAndItemIdAndDeletedAtIsNull(buyerId, asset.getItemId())
                || purchases.existsByBuyerUserIdAndAssetId(buyerId, id)) throw error(MarketErrorCode.ALREADY_HOLDING);
        Map<Long, UserWallet> money = new HashMap<>();
        for (Long uid : locked.keySet().stream().sorted().toList()) {
            money.put(uid, wallets.findWithLockByUserIdAndCurrencyType(uid, CurrencyType.COIN)
                    .orElseGet(() -> wallets.save(UserWallet.create(locked.get(uid), CurrencyType.COIN))));
        }
        UserWallet buyer = money.get(buyerId), seller = money.get(creatorId);
        if (buyer.getBalance() < expectedPrice) throw error(MarketErrorCode.INSUFFICIENT_COIN);
        int fee = expectedPrice * 5 / 100;
        buyer.spend(expectedPrice);
        seller.add(expectedPrice - fee);
        var copy = inventory.save(UserItem.create(locked.get(buyerId), items.getReferenceById(asset.getItemId())));
        var purchase = purchases.save(new MarketPurchase(id, buyerId, creatorId, requestId, expectedPrice,
                fee, copy.getId(), clock.instant()));
        histories.record(buyer, -expectedPrice, WalletHistoryReason.MARKET_PURCHASE,
                WalletHistory.SOURCE_MARKET_PURCHASE, purchase.getId());
        histories.record(seller, expectedPrice - fee, WalletHistoryReason.MARKET_SALE,
                WalletHistory.SOURCE_MARKET_PURCHASE, purchase.getId());
        return MarketPurchaseResponse.from(purchase);
    }

    private MarketPurchaseResponse replay(MarketPurchase purchase, Long assetId, int price) {
        if (!purchase.getAssetId().equals(assetId) || purchase.getPrice() != price)
            throw error(MarketErrorCode.REQUEST_CONFLICT);
        return MarketPurchaseResponse.from(purchase);
    }

    private MarketAsset asset(Long id) { return assets.findById(id).orElseThrow(() -> error(MarketErrorCode.ASSET_NOT_FOUND)); }
    private User lockedUser(Long id) { return users.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN)); }
    private void active(User user) { if (user.getDeletedAt() != null) throw error(MarketErrorCode.ASSET_NOT_FOUND); }
    private void requireEnabled() { if (!enabled) throw error(MarketErrorCode.TRANSITION_PENDING); }
    private BusinessException error(MarketErrorCode code) { return new BusinessException(code); }
    private MarketListingResponse view(MarketAsset a, Long viewer, Item item, User creator, boolean owned) {
        return new MarketListingResponse(a.getId(), item.getId(), item.getName(), item.getAssetKey(),
                creator == null || creator.getDeletedAt() != null ? null : creator.getNickname(), a.isCreator(viewer),
                a.getSalePrice(), a.isSaleActive() && !a.isSuspended() && creator != null && creator.getDeletedAt() == null, owned);
    }
}
