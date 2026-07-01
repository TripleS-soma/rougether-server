package com.triples.rougether.userapi.gacha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.gacha.dto.GachaDrawRequest;
import com.triples.rougether.userapi.gacha.dto.GachaDrawResponse;
import com.triples.rougether.userapi.gacha.error.GachaErrorCode;
import com.triples.rougether.userapi.gacha.service.GachaService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GachaServiceTest {

    @Mock private GachaRepository gachaRepository;
    @Mock private GachaPoolEntryRepository poolRepository;
    @Mock private UserItemRepository userItemRepository;
    @Mock private UserWalletRepository walletRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private GachaService gachaService;

    private Gacha activeGacha(int cost) {
        Gacha g = mock(Gacha.class);
        when(g.isActive()).thenReturn(true);
        when(g.getCostAmount()).thenReturn(cost);
        return g;
    }

    // pool 을 1개 아이템(rarity 일반)으로 만들어 추첨 결과를 결정적으로 고정.
    private Item singleItemPool(Long itemId, Long gachaId) {
        Item item = mock(Item.class);
        when(item.getId()).thenReturn(itemId);
        when(item.getName()).thenReturn("가구A");
        when(item.getAssetKey()).thenReturn("items/a.png");
        GachaPoolEntry entry = mock(GachaPoolEntry.class);
        when(entry.getRewardType()).thenReturn(RewardType.ITEM);
        when(entry.getItem()).thenReturn(item);
        when(entry.getRarity()).thenReturn("일반");
        when(poolRepository.findByGachaIdAndActiveIsTrue(gachaId)).thenReturn(List.of(entry));
        return item;
    }

    @Test
    void 뽑기횟수가_1이나_10이_아니면_거부한다() {
        assertThatThrownBy(() -> gachaService.draw(1L, 10L, new GachaDrawRequest(2)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(GachaErrorCode.INVALID_DRAW_COUNT));
    }

    @Test
    void 비활성_뽑기는_거부한다() {
        Gacha g = mock(Gacha.class);
        when(g.isActive()).thenReturn(false);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));

        assertThatThrownBy(() -> gachaService.draw(1L, 10L, new GachaDrawRequest(1)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(GachaErrorCode.GACHA_INACTIVE));
    }

    @Test
    void 코인이_부족하면_거부하고_차감하지_않는다() {
        Gacha g = activeGacha(250);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(100);
        when(walletRepository.findByUserIdAndCurrencyType(1L, "COIN")).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> gachaService.draw(1L, 10L, new GachaDrawRequest(1)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(GachaErrorCode.INSUFFICIENT_COIN));
        verify(wallet, never()).deduct(anyInt());
    }

    @Test
    void 미소유_아이템은_지급되고_코인이_차감된다() {
        Gacha g = activeGacha(250);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(1000);
        when(walletRepository.findByUserIdAndCurrencyType(1L, "COIN")).thenReturn(Optional.of(wallet));
        singleItemPool(100L, 10L);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(1L)).thenReturn(List.of());
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        GachaDrawResponse res = gachaService.draw(1L, 10L, new GachaDrawRequest(1));

        verify(wallet).deduct(250);
        verify(userItemRepository).save(any(UserItem.class));
        verify(wallet).add(0);
        assertThat(res.results()).hasSize(1);
        assertThat(res.results().get(0).converted()).isFalse();
        assertThat(res.results().get(0).rewardType()).isEqualTo("ITEM");
    }

    @Test
    void 이미_소유한_아이템은_지급대신_코인40_환급된다() {
        Gacha g = activeGacha(250);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(1000);
        when(walletRepository.findByUserIdAndCurrencyType(1L, "COIN")).thenReturn(Optional.of(wallet));
        Item item = singleItemPool(100L, 10L);
        UserItem owned = mock(UserItem.class);
        when(owned.getItem()).thenReturn(item);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(1L)).thenReturn(List.of(owned));
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        GachaDrawResponse res = gachaService.draw(1L, 10L, new GachaDrawRequest(1));

        verify(wallet).deduct(250);
        verify(wallet).add(40);
        verify(userItemRepository, never()).save(any());
        assertThat(res.results().get(0).converted()).isTrue();
        assertThat(res.results().get(0).refundAmount()).isEqualTo(40);
    }
}
