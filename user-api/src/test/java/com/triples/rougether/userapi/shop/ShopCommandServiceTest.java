package com.triples.rougether.userapi.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.shop.dto.PurchaseResponse;
import com.triples.rougether.userapi.shop.error.ShopErrorCode;
import com.triples.rougether.userapi.shop.service.ShopCommandService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShopCommandServiceTest {

    @Mock private ItemRepository itemRepository;
    @Mock private UserItemRepository userItemRepository;
    @Mock private UserWalletRepository userWalletRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private ShopCommandService shopCommandService;

    private Item purchasableItem(Long id, int price) {
        Item item = mock(Item.class);
        when(item.isActive()).thenReturn(true);
        when(item.getPurchaseCurrencyType()).thenReturn(CurrencyType.DIAMOND);
        when(item.getPriceAmount()).thenReturn(price);
        return item;
    }

    @Test
    void 구매하면_다이아를_차감하고_아이템을_지급한다() {
        Item item = purchasableItem(100L, 400);
        when(item.getId()).thenReturn(100L);
        when(itemRepository.findById(100L)).thenReturn(Optional.of(item));
        when(userItemRepository.existsByUserIdAndItemIdAndDeletedAtIsNull(1L, 100L)).thenReturn(false);
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(500, 100);
        when(wallet.getCurrencyType()).thenReturn(CurrencyType.DIAMOND);
        when(userWalletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.DIAMOND)).thenReturn(Optional.of(wallet));
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));
        UserItem saved = mock(UserItem.class);
        when(saved.getId()).thenReturn(77L);
        when(saved.getAcquiredAt()).thenReturn(Instant.EPOCH);
        when(userItemRepository.save(any(UserItem.class))).thenReturn(saved);

        PurchaseResponse response = shopCommandService.purchase(1L, 100L);

        verify(wallet).spend(400);
        assertThat(response.userItemId()).isEqualTo(77L);
        assertThat(response.itemId()).isEqualTo(100L);
        assertThat(response.acquiredAt()).isEqualTo(Instant.EPOCH);
        assertThat(response.wallet().currencyType()).isEqualTo(CurrencyType.DIAMOND);
    }

    @Test
    void 잔액이_부족하면_거부하고_차감하지_않는다() {
        Item item = purchasableItem(100L, 400);
        when(itemRepository.findById(100L)).thenReturn(Optional.of(item));
        when(userItemRepository.existsByUserIdAndItemIdAndDeletedAtIsNull(1L, 100L)).thenReturn(false);
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(100);
        when(userWalletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.DIAMOND)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> shopCommandService.purchase(1L, 100L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ShopErrorCode.INSUFFICIENT_BALANCE));
        verify(wallet, never()).spend(anyInt());
        verify(userItemRepository, never()).save(any());
    }

    @Test
    void 지갑이_없으면_잔액부족으로_거부한다() {
        Item item = purchasableItem(100L, 400);
        when(itemRepository.findById(100L)).thenReturn(Optional.of(item));
        when(userItemRepository.existsByUserIdAndItemIdAndDeletedAtIsNull(1L, 100L)).thenReturn(false);
        when(userWalletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.DIAMOND)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shopCommandService.purchase(1L, 100L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ShopErrorCode.INSUFFICIENT_BALANCE));
        verify(userItemRepository, never()).save(any());
    }

    @Test
    void 이미_보유한_아이템은_중복_구매를_거부한다() {
        Item item = purchasableItem(100L, 400);
        when(itemRepository.findById(100L)).thenReturn(Optional.of(item));
        when(userItemRepository.existsByUserIdAndItemIdAndDeletedAtIsNull(1L, 100L)).thenReturn(true);

        assertThatThrownBy(() -> shopCommandService.purchase(1L, 100L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ShopErrorCode.ALREADY_OWNED));
        verify(userItemRepository, never()).save(any());
    }

    @Test
    void 가격이_없는_뽑기_전용_아이템은_구매할_수_없다() {
        Item item = mock(Item.class);
        when(item.isActive()).thenReturn(true);
        when(item.getPurchaseCurrencyType()).thenReturn(CurrencyType.DIAMOND);
        when(item.getPriceAmount()).thenReturn(null);
        when(itemRepository.findById(100L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> shopCommandService.purchase(1L, 100L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ShopErrorCode.ITEM_NOT_PURCHASABLE));
    }

    @Test
    void 비활성_아이템은_구매할_수_없다() {
        Item item = mock(Item.class);
        when(item.isActive()).thenReturn(false);
        when(itemRepository.findById(100L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> shopCommandService.purchase(1L, 100L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ShopErrorCode.ITEM_NOT_PURCHASABLE));
    }

    @Test
    void 없는_아이템은_404() {
        when(itemRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shopCommandService.purchase(1L, 999L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ShopErrorCode.ITEM_NOT_FOUND));
    }
}
