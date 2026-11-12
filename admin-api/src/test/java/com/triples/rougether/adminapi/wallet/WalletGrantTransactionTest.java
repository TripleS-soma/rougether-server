package com.triples.rougether.adminapi.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.triples.rougether.adminapi.wallet.dto.WalletGrantRequest;
import com.triples.rougether.adminapi.wallet.service.WalletGrantService;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.member.repository.WalletHistoryRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 테스트 자체에 트랜잭션을 두지 않고 서비스의 실제 커밋과 롤백 결과를 확인함.
@SpringBootTest
class WalletGrantTransactionTest {

    @Autowired private WalletGrantService service;
    @Autowired private UserRepository users;
    @Autowired private UserWalletRepository wallets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @MockitoSpyBean private WalletHistoryRepository histories;

    private Long userId;

    @AfterEach
    void cleanup() {
        if (userId != null) {
            jdbc.update("delete from wallet_histories where user_id = ?", userId);
            jdbc.update("delete from user_wallets where user_id = ?", userId);
            users.deleteById(userId);
        }
    }

    @ParameterizedTest
    @EnumSource(CurrencyType.class)
    void 신규_지갑과_재지급의_잔액과_원장이_함께_커밋된다(CurrencyType currency) {
        createUser(false);

        var first = service.grant(userId, new WalletGrantRequest(currency, 100));
        var second = service.grant(userId, new WalletGrantRequest(currency, 50));

        assertThat(first.created()).isTrue();
        assertThat(first.balance()).isEqualTo(100);
        assertThat(second.created()).isFalse();
        assertThat(second.balance()).isEqualTo(150);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(jdbc.queryForObject(
                "select balance from user_wallets where user_id = ? and currency_type = ?",
                Integer.class, userId, currency.name())).isEqualTo(150);
        assertThat(histories.findHistories(userId, currency, null, PageRequest.of(0, 10)).getContent())
                .extracting(WalletHistory::getCurrencyType, WalletHistory::getAmount,
                        WalletHistory::getReason, WalletHistory::getBalanceAfter)
                .containsExactly(
                        tuple(currency, 50, WalletHistoryReason.ADMIN_GRANT, 150),
                        tuple(currency, 100, WalletHistoryReason.ADMIN_GRANT, 100));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 원장_저장이_실패하면_신규_지갑과_기존_잔액_변경이_롤백된다(boolean existingWallet) {
        createUser(existingWallet);
        doThrow(new IllegalStateException("원장 저장 실패"))
                .when(histories).save(any(WalletHistory.class));

        assertThatThrownBy(() -> service.grant(userId, new WalletGrantRequest(CurrencyType.COIN, 100)))
                .isInstanceOf(IllegalStateException.class).hasMessage("원장 저장 실패");

        assertRolledBack(existingWallet);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 잔액과_원장의_SQL_반영_후_실패해도_둘_다_롤백된다(boolean existingWallet) {
        createUser(existingWallet);
        doAnswer(invocation -> {
            entityManager.persist(invocation.getArgument(0, WalletHistory.class));
            entityManager.flush();
            // 실제 DB 쓰기 후 예외를 발생시켜 단순 미호출이 아닌 롤백을 검증함.
            assertThat(jdbc.queryForObject("select balance from user_wallets where user_id = ?",
                    Integer.class, userId)).isEqualTo(existingWallet ? 180 : 100);
            assertThat(historyCount()).isEqualTo(1);
            throw new IllegalStateException("SQL 반영 후 실패");
        }).when(histories).save(any(WalletHistory.class));

        assertThatThrownBy(() -> service.grant(userId, new WalletGrantRequest(CurrencyType.COIN, 100)))
                .isInstanceOf(IllegalStateException.class).hasMessage("SQL 반영 후 실패");

        assertRolledBack(existingWallet);
    }

    private void createUser(boolean existingWallet) {
        User user = users.save(User.signUp("wallet-grant-" + UUID.randomUUID() + "@rougether.dev"));
        userId = user.getId();
        if (existingWallet) {
            wallets.save(UserWallet.createWithBalance(user, CurrencyType.COIN, 80));
        }
    }

    private void assertRolledBack(boolean existingWallet) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(historyCount()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_wallets where user_id = ?",
                Integer.class, userId)).isEqualTo(existingWallet ? 1 : 0);
        if (existingWallet) {
            assertThat(jdbc.queryForObject("select balance from user_wallets where user_id = ?",
                    Integer.class, userId)).isEqualTo(80);
        }
    }

    private int historyCount() {
        return jdbc.queryForObject("select count(*) from wallet_histories where user_id = ?",
                Integer.class, userId);
    }
}
