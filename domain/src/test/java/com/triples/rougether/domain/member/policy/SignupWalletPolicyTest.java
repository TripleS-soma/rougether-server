package com.triples.rougether.domain.member.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.shared.CurrencyType;
import java.util.List;
import org.junit.jupiter.api.Test;

class SignupWalletPolicyTest {

    @Test
    void 가입_시_모든_통화의_지갑을_발급한다() {
        User user = User.signUp("a@b.com");

        List<UserWallet> wallets = SignupWalletPolicy.issueAll(user);

        assertThat(wallets)
                .extracting(UserWallet::getCurrencyType)
                .containsExactlyInAnyOrder(CurrencyType.values());
        assertThat(wallets).allSatisfy(wallet -> assertThat(wallet.getUser()).isSameAs(user));
    }

    @Test
    void 초기_코인은_750_나머지_통화는_0이다() {
        // 750 = 온보딩에서 가구 뽑기 단챠(250) 1회 체험 후 500(단챠 2회분)이 남는 값.
        List<UserWallet> wallets = SignupWalletPolicy.issueAll(User.signUp("a@b.com"));

        assertThat(wallets)
                .filteredOn(wallet -> wallet.getCurrencyType() == CurrencyType.COIN)
                .singleElement()
                .extracting(UserWallet::getBalance)
                .isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE)
                .isEqualTo(750);

        assertThat(wallets)
                .filteredOn(wallet -> wallet.getCurrencyType() != CurrencyType.COIN)
                .allSatisfy(wallet -> assertThat(wallet.getBalance()).isZero());
    }
}
