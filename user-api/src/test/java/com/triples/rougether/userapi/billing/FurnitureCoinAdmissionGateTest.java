package com.triples.rougether.userapi.billing;

import static org.assertj.core.api.Assertions.*;

import com.triples.rougether.common.error.BillingErrorCode;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.billing.entity.FurnitureCreditReservation;
import com.triples.rougether.domain.billing.repository.FurnitureCreditReservationRepository;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"billing.require-credits=true", "billing.coin-fallback-enabled=false"})
class FurnitureCoinAdmissionGateTest {
    @Autowired UserRepository users;
    @Autowired UserWalletRepository wallets;
    @Autowired FurnitureGenerationJobRepository jobs;
    @Autowired FurnitureCreditReservationRepository reservations;
    @Autowired FurnitureGenerationTransactions generation;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock clock;

    @Test void 접수를_끄면_100코인이_있어도_작업과_차감을_만들지_않음() {
        var user = users.save(User.signUp(UUID.randomUUID() + "@coin-gate.test"));
        wallets.save(UserWallet.createWithBalance(user, CurrencyType.COIN, 100));
        assertThatThrownBy(() -> generation.reserve(user.getId(), UUID.randomUUID().toString(), "digest", "의자"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(BillingErrorCode.FURNITURE_CREDITS_REQUIRED));
        assertThat(wallets.findByUserIdAndCurrencyType(user.getId(), CurrencyType.COIN).orElseThrow().getBalance()).isEqualTo(100);
        assertThat(jobs.findAll().stream().filter(job -> job.getUserId().equals(user.getId()))).isEmpty();
    }

    @Test void 접수를_꺼도_이미_차감한_코인은_실패시_한번만_환급() {
        var user = users.save(User.signUp(UUID.randomUUID() + "@coin-gate.test"));
        wallets.save(UserWallet.createWithBalance(user, CurrencyType.COIN, 0));
        // 접수 활성화 시점에 이미 커밋된 코인 예약을 재현함.
        String jobId = new TransactionTemplate(transactionManager).execute(status -> {
            var now = clock.instant();
            var job = jobs.save(FurnitureGenerationJob.create(user.getId(), UUID.randomUUID().toString(),
                    "digest", "의자", now, now.plusSeconds(3600)));
            reservations.save(new FurnitureCreditReservation(job.getId(), user.getId(),
                    FurnitureCreditReservation.PaymentType.COIN, 100, now));
            return job.getId();
        });
        generation.uploadFailed(user.getId(), jobId);
        generation.uploadFailed(user.getId(), jobId);
        assertThat(wallets.findByUserIdAndCurrencyType(user.getId(), CurrencyType.COIN).orElseThrow().getBalance()).isEqualTo(100);
        assertThat(reservations.findById(jobId).orElseThrow().getStatus()).isEqualTo(FurnitureCreditReservation.Status.RELEASED);
        assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(FurnitureGenerationJob.Status.FAILED);
    }
}
