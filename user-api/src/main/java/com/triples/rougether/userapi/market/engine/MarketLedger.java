package com.triples.rougether.userapi.market.engine;

import com.triples.rougether.domain.market.entity.MarketAsset;
import com.triples.rougether.domain.market.entity.MarketCommand;
import com.triples.rougether.domain.market.entity.MarketOrder;
import com.triples.rougether.domain.market.entity.OrderSide;
import com.triples.rougether.domain.market.entity.OrderSource;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.time.Instant;
import org.springframework.stereotype.Component;

// 엔진 트랜잭션 안에서 코인 적립과 맡긴 것 돌려주기를 담당함. 호출 측 트랜잭션에 참여함.
@Component
public class MarketLedger {

    private final UserWalletRepository userWalletRepository;
    private final UserRepository userRepository;
    private final UserItemRepository userItemRepository;
    private final WalletHistoryRecorder walletHistoryRecorder;

    public MarketLedger(UserWalletRepository userWalletRepository, UserRepository userRepository,
                        UserItemRepository userItemRepository, WalletHistoryRecorder walletHistoryRecorder) {
        this.userWalletRepository = userWalletRepository;
        this.userRepository = userRepository;
        this.userItemRepository = userItemRepository;
        this.walletHistoryRecorder = walletHistoryRecorder;
    }

    // 여러 사용자의 코인 지갑을 사용자 ID 오름차순으로 먼저 잠금(다른 다중 지갑 경로와 같은 순서, 데드락 방지).
    // 이후 credit 의 잠금 조회는 같은 트랜잭션에서 이미 잡은 행이라 대기 없이 통과함.
    public void lockWallets(java.util.Collection<Long> userIds) {
        userIds.stream().filter(java.util.Objects::nonNull).distinct().sorted()
                .forEach(id -> userWalletRepository.findWithLockByUserIdAndCurrencyType(id, CurrencyType.COIN));
    }

    // 코인 적립. 지갑이 없으면 만듦. 0 이하는 기록하지 않음(원장 규칙: 지급액 0 은 남기지 않음).
    public void credit(Long userId, int amount, WalletHistoryReason reason, String sourceType, Long sourceId) {
        if (amount <= 0) {
            return;
        }
        UserWallet wallet = userWalletRepository.findWithLockByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseGet(() -> userWalletRepository.save(
                        UserWallet.create(userRepository.getReferenceById(userId), CurrencyType.COIN)));
        wallet.add(amount);
        walletHistoryRecorder.record(wallet, amount, reason, sourceType, sourceId);
    }

    // 주문이 생기기 전 접수를 거절할 때 맡긴 것을 돌려줌. 발행 재고면 asset 은 잠금 조회한 인스턴스여야 함.
    public void refundCommand(MarketCommand command, MarketAsset lockedAssetOrNull, Instant now) {
        if (command.getSide() == OrderSide.BUY) {
            credit(command.getUserId(), command.getEscrowAmount(), WalletHistoryReason.MARKET_ESCROW_REFUND,
                    WalletHistory.SOURCE_MARKET_COMMAND, command.getId());
        } else if (command.getSource() == OrderSource.INVENTORY) {
            userItemRepository.findById(command.getEscrowUserItemId()).orElseThrow().reactivate();
        } else {
            lockedAssetOrNull.returnUnissued(command.getEscrowAmount(), now);
        }
    }

    // 취소·만료된 주문의 남은 에스크로를 돌려줌. 발행 재고면 asset 은 잠금 조회한 인스턴스여야 함.
    public void refundOrder(MarketOrder order, MarketAsset lockedAssetOrNull, Instant now) {
        if (order.getSide() == OrderSide.BUY) {
            credit(order.getUserId(), order.getEscrowRemaining(), WalletHistoryReason.MARKET_ESCROW_REFUND,
                    WalletHistory.SOURCE_MARKET_ORDER, order.getId());
        } else if (order.getSource() == OrderSource.INVENTORY) {
            userItemRepository.findById(order.getUserItemId()).orElseThrow().reactivate();
        } else {
            lockedAssetOrNull.returnUnissued(order.getEscrowRemaining(), now);
        }
    }
}
