package com.triples.rougether.userapi.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.wallet.dto.WalletListResponse;
import com.triples.rougether.userapi.wallet.service.WalletQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletQueryServiceTest {

    @Mock private UserWalletRepository userWalletRepository;
    @InjectMocks private WalletQueryService walletQueryService;

    private UserWallet wallet(CurrencyType type, int balance) {
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getCurrencyType()).thenReturn(type);
        when(wallet.getBalance()).thenReturn(balance);
        return wallet;
    }

    @Test
    void 모든_재화_잔액을_내려준다() {
        UserWallet coin = wallet(CurrencyType.COIN, 5600);
        UserWallet dia = wallet(CurrencyType.DIAMOND, 20);
        when(userWalletRepository.findByUserId(1L)).thenReturn(List.of(coin, dia));

        WalletListResponse response = walletQueryService.getWallets(1L);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).currencyType()).isEqualTo(CurrencyType.COIN);
        assertThat(response.items().get(0).balance()).isEqualTo(5600);
        assertThat(response.items().get(1).currencyType()).isEqualTo(CurrencyType.DIAMOND);
        assertThat(response.items().get(1).balance()).isEqualTo(20);
    }

    @Test
    void 지갑이_없는_재화는_잔액_0으로_내려준다() {
        UserWallet coin = wallet(CurrencyType.COIN, 100);
        when(userWalletRepository.findByUserId(1L)).thenReturn(List.of(coin));

        WalletListResponse response = walletQueryService.getWallets(1L);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(1).currencyType()).isEqualTo(CurrencyType.DIAMOND);
        assertThat(response.items().get(1).balance()).isZero();
    }
}
