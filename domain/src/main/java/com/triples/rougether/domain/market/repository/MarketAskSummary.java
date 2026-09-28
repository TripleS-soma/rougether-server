package com.triples.rougether.domain.market.repository;

// 종목별 판매 대기 요약(최저 매도가·남은 매도 총수량)
public interface MarketAskSummary {

    Long getAssetId();

    Integer getBestAskPrice();

    Long getAskQuantity();
}
