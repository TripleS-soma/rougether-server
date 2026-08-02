package com.triples.rougether.userapi.shop.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.shop.dto.PurchaseResponse;
import com.triples.rougether.userapi.shop.dto.PurchaseResponse.WalletSummary;
import com.triples.rougether.userapi.shop.error.ShopErrorCode;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 아이템 구매. 지갑 차감 + user_items 지급을 하나의 트랜잭션으로 묶는다 (spec 확정).
// 중복 보유 재구매는 불허 (프론트: 보유 아이템은 구매 버튼 없음).
@Service
public class ShopCommandService {

    private static final String PLACEMENT_CHARACTER = "character";

    private final ItemRepository itemRepository;
    private final UserItemRepository userItemRepository;
    private final UserWalletRepository userWalletRepository;
    private final UserRepository userRepository;
    private final WalletHistoryRecorder walletHistoryRecorder;

    public ShopCommandService(ItemRepository itemRepository,
                              UserItemRepository userItemRepository,
                              UserWalletRepository userWalletRepository,
                              UserRepository userRepository,
                              WalletHistoryRecorder walletHistoryRecorder) {
        this.itemRepository = itemRepository;
        this.userItemRepository = userItemRepository;
        this.userWalletRepository = userWalletRepository;
        this.userRepository = userRepository;
        this.walletHistoryRecorder = walletHistoryRecorder;
    }

    @Transactional
    public PurchaseResponse purchase(Long userId, Long itemId) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(ShopErrorCode.ITEM_NOT_FOUND));
        // 캐릭터 악세사리는 카탈로그 값과 무관하게 뽑기 전용이다.
        // 그 외에도 비활성이거나 구매 재화/가격이 없는 아이템은 구매 불가.
        if (!item.isActive() || PLACEMENT_CHARACTER.equals(item.getPlacementType())
                || item.getPurchaseCurrencyType() == null
                || item.getPriceAmount() == null || item.getPriceAmount() <= 0) {
            throw new BusinessException(ShopErrorCode.ITEM_NOT_PURCHASABLE);
        }
        if (userItemRepository.existsByUserIdAndItemIdAndDeletedAtIsNull(userId, itemId)) {
            throw new BusinessException(ShopErrorCode.ALREADY_OWNED);
        }

        // 지갑이 아직 없으면(다이아 미발급) 잔액 0 취급. 행 락으로 동시 구매의 이중 차감을 막고,
        // 이중 지급은 uq_user_items_user_item 이 막는다.
        UserWallet wallet = userWalletRepository.findWithLockByUserIdAndCurrencyType(userId, item.getPurchaseCurrencyType())
                .orElseThrow(() -> new BusinessException(ShopErrorCode.INSUFFICIENT_BALANCE));
        if (wallet.getBalance() < item.getPriceAmount()) {
            throw new BusinessException(ShopErrorCode.INSUFFICIENT_BALANCE);
        }
        wallet.spend(item.getPriceAmount());
        walletHistoryRecorder.record(wallet, -item.getPriceAmount(), WalletHistoryReason.SHOP_PURCHASE,
                WalletHistory.SOURCE_ITEM, item.getId());

        UserItem saved = userItemRepository.save(
                UserItem.create(userRepository.getReferenceById(userId), item));

        return new PurchaseResponse(saved.getId(), item.getId(), saved.getAcquiredAt(),
                new WalletSummary(wallet.getCurrencyType(), wallet.getBalance()));
    }
}
