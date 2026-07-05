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
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
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
    @Mock private UserCharacterRepository userCharacterRepository;
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
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> gachaService.draw(1L, 10L, new GachaDrawRequest(1)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(GachaErrorCode.INSUFFICIENT_COIN));
        verify(wallet, never()).spend(anyInt());
    }

    @Test
    void 미소유_아이템은_지급되고_코인이_차감된다() {
        Gacha g = activeGacha(250);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(1000);
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN)).thenReturn(Optional.of(wallet));
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.DIAMOND)).thenReturn(Optional.empty());
        singleItemPool(100L, 10L);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(1L)).thenReturn(List.of());
        when(userCharacterRepository.findByUserId(1L)).thenReturn(List.of());
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        GachaDrawResponse res = gachaService.draw(1L, 10L, new GachaDrawRequest(1));

        verify(wallet).spend(250);
        verify(userItemRepository).save(any(UserItem.class));
        verify(wallet).add(0);
        assertThat(res.results()).hasSize(1);
        assertThat(res.results().get(0).converted()).isFalse();
        assertThat(res.results().get(0).rewardType()).isEqualTo("ITEM");
        // 응답에 두 재화 잔액이 모두 담긴다 (다이아 지갑이 없으면 0)
        assertThat(res.wallets()).extracting(w -> w.currencyType())
                .containsExactly(CurrencyType.COIN, CurrencyType.DIAMOND);
        assertThat(res.wallets().get(1).balance()).isZero();
    }

    @Test
    void 이미_소유한_아이템은_지급대신_다이아30으로_전환된다() {
        Gacha g = activeGacha(250);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(1000);
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN)).thenReturn(Optional.of(wallet));
        UserWallet diaWallet = mock(UserWallet.class);
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.DIAMOND)).thenReturn(Optional.of(diaWallet));
        Item item = singleItemPool(100L, 10L);
        UserItem owned = mock(UserItem.class);
        when(owned.getItem()).thenReturn(item);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(1L)).thenReturn(List.of(owned));
        when(userCharacterRepository.findByUserId(1L)).thenReturn(List.of());
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        GachaDrawResponse res = gachaService.draw(1L, 10L, new GachaDrawRequest(1));

        verify(wallet).spend(250);
        verify(wallet).add(0);
        verify(diaWallet).add(30);
        verify(userItemRepository, never()).save(any());
        assertThat(res.results().get(0).converted()).isTrue();
        assertThat(res.results().get(0).refundCurrencyType()).isEqualTo(CurrencyType.DIAMOND);
        assertThat(res.results().get(0).refundAmount()).isEqualTo(30);
    }

    @Test
    void 아이템_중복_전환시_다이아_지갑이_없으면_새로_발급한다() {
        Gacha g = activeGacha(250);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(1000);
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN)).thenReturn(Optional.of(wallet));
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.DIAMOND)).thenReturn(Optional.empty());
        UserWallet createdDia = mock(UserWallet.class);
        when(createdDia.getBalance()).thenReturn(30);
        when(walletRepository.save(any(UserWallet.class))).thenReturn(createdDia);
        Item item = singleItemPool(100L, 10L);
        UserItem owned = mock(UserItem.class);
        when(owned.getItem()).thenReturn(item);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(1L)).thenReturn(List.of(owned));
        when(userCharacterRepository.findByUserId(1L)).thenReturn(List.of());
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        GachaDrawResponse res = gachaService.draw(1L, 10L, new GachaDrawRequest(1));

        verify(walletRepository).save(any(UserWallet.class));
        verify(createdDia).add(30);
        assertThat(res.wallets().get(1).balance()).isEqualTo(30);
    }

    // 캐릭터 pool 을 1개(rarity 미부여)로 만들어 추첨 결과를 결정적으로 고정.
    private Character singleCharacterPool(Long characterId, Long gachaId) {
        Character ch = mock(Character.class);
        when(ch.getId()).thenReturn(characterId);
        when(ch.getName()).thenReturn("곰");
        when(ch.getBaseAssetKey()).thenReturn("characters/bear.png");
        GachaPoolEntry entry = mock(GachaPoolEntry.class);
        when(entry.getRewardType()).thenReturn(RewardType.CHARACTER);
        when(entry.getCharacter()).thenReturn(ch);
        when(poolRepository.findByGachaIdAndActiveIsTrue(gachaId)).thenReturn(List.of(entry));
        return ch;
    }

    @Test
    void 미소유_캐릭터는_지급되고_코인1000이_차감된다() {
        Gacha g = activeGacha(1000);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(2000);
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN)).thenReturn(Optional.of(wallet));
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.DIAMOND)).thenReturn(Optional.empty());
        singleCharacterPool(5L, 10L);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(1L)).thenReturn(List.of());
        when(userCharacterRepository.findByUserId(1L)).thenReturn(List.of());
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        GachaDrawResponse res = gachaService.draw(1L, 10L, new GachaDrawRequest(1));

        verify(wallet).spend(1000);
        verify(userCharacterRepository).save(any(UserCharacter.class));
        assertThat(res.results().get(0).rewardType()).isEqualTo("CHARACTER");
        assertThat(res.results().get(0).characterId()).isEqualTo(5L);
        assertThat(res.results().get(0).converted()).isFalse();
    }

    @Test
    void 이미_소유한_캐릭터는_지급대신_코인200_환급된다() {
        Gacha g = activeGacha(1000);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(2000);
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN)).thenReturn(Optional.of(wallet));
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.DIAMOND)).thenReturn(Optional.empty());
        Character ch = singleCharacterPool(5L, 10L);
        UserCharacter owned = mock(UserCharacter.class);
        when(owned.getCharacter()).thenReturn(ch);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(1L)).thenReturn(List.of());
        when(userCharacterRepository.findByUserId(1L)).thenReturn(List.of(owned));
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        GachaDrawResponse res = gachaService.draw(1L, 10L, new GachaDrawRequest(1));

        verify(wallet).spend(1000);
        verify(wallet).add(200);
        verify(userCharacterRepository, never()).save(any());
        assertThat(res.results().get(0).converted()).isTrue();
        assertThat(res.results().get(0).refundCurrencyType()).isEqualTo(CurrencyType.COIN);
        assertThat(res.results().get(0).refundAmount()).isEqualTo(200);
    }
}
