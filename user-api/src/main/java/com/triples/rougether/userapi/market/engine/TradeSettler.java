package com.triples.rougether.userapi.market.engine;

import com.triples.rougether.domain.market.entity.MarketAsset;
import com.triples.rougether.domain.market.entity.MarketOrder;
import com.triples.rougether.domain.market.entity.MarketTrade;
import com.triples.rougether.domain.market.entity.OrderSide;
import com.triples.rougether.domain.market.repository.MarketTradeRepository;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;

// 체결 1건 정산(#401). 엔진 처리 트랜잭션 안에서 호출됨.
// 로열티 10%(제작자, 판매자가 제작자이거나 탈퇴했으면 0)·수수료 5%(소각)를 각각 내림 계산해 판매 대금에서 뗌.
@Component
public class TradeSettler {

    static final int ROYALTY_PERCENT = 10;
    static final int FEE_PERCENT = 5;

    private final MarketTradeRepository marketTradeRepository;
    private final UserItemRepository userItemRepository;
    private final UserRepository userRepository;
    private final ItemRepository itemRepository;
    private final MarketLedger ledger;

    public TradeSettler(MarketTradeRepository marketTradeRepository, UserItemRepository userItemRepository,
                        UserRepository userRepository, ItemRepository itemRepository, MarketLedger ledger) {
        this.marketTradeRepository = marketTradeRepository;
        this.userItemRepository = userItemRepository;
        this.userRepository = userRepository;
        this.itemRepository = itemRepository;
        this.ledger = ledger;
    }

    public MarketTrade settle(MarketAsset asset, MarketOrder taker, MarketOrder maker, int price, int quantity,
                              long engineSeq, Instant now) {
        MarketOrder buy = taker.getSide() == OrderSide.BUY ? taker : maker;
        MarketOrder sell = taker.getSide() == OrderSide.BUY ? maker : taker;
        int gross = price * quantity;
        Long creator = asset.getCreatorUserId();
        Long royaltyUser = creator != null && !creator.equals(sell.getUserId()) ? creator : null;
        int royalty = royaltyUser == null ? 0 : gross * ROYALTY_PERCENT / 100;
        int fee = gross * FEE_PERCENT / 100;
        ledger.lockWallets(java.util.Arrays.asList(buy.getUserId(), sell.getUserId(), royaltyUser));

        // 매수자는 자기 가격으로 맡겼으므로 더 싸게 체결되면 차액이 남음
        int buyEscrowUsed = buy.getPrice() * quantity;
        buy.fill(quantity, buyEscrowUsed, now);
        sell.fill(quantity, quantity, now);
        MarketTrade trade = marketTradeRepository.save(MarketTrade.of(asset.getId(), engineSeq, buy, sell, royaltyUser,
                price, quantity, royalty, fee, now));

        ledger.credit(buy.getUserId(), buyEscrowUsed - gross, WalletHistoryReason.MARKET_ESCROW_REFUND,
                WalletHistory.SOURCE_MARKET_ORDER, buy.getId());
        ledger.credit(sell.getUserId(), gross - royalty - fee, WalletHistoryReason.MARKET_SALE,
                WalletHistory.SOURCE_MARKET_TRADE, trade.getId());
        if (royaltyUser != null) {
            ledger.credit(royaltyUser, royalty, WalletHistoryReason.MARKET_ROYALTY,
                    WalletHistory.SOURCE_MARKET_TRADE, trade.getId());
        }
        // 매수는 항상 수량 1. 판매자의 맡긴 user_item 은 비활성으로 남고 구매자에게 새 row 를 줌.
        userItemRepository.save(UserItem.create(userRepository.getReferenceById(buy.getUserId()),
                itemRepository.getReferenceById(asset.getItemId())));
        return trade;
    }
}
