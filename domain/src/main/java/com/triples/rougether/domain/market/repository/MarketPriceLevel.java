package com.triples.rougether.domain.market.repository;

// 가격대별 남은 수량 합계(호가 한 줄)
public interface MarketPriceLevel {

    Integer getPrice();

    Long getQuantity();
}
