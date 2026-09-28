package com.triples.rougether.userapi.market.dto;

import com.triples.rougether.domain.market.entity.OrderStatus;
import java.util.List;

// 내 주문 목록 필터. OPEN = 대기 중, CLOSED = 체결 완료·취소·만료.
public enum MyMarketOrderStatus {
    OPEN(List.of(OrderStatus.OPEN)),
    CLOSED(List.of(OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.EXPIRED));

    private final List<OrderStatus> statuses;

    MyMarketOrderStatus(List<OrderStatus> statuses) {
        this.statuses = statuses;
    }

    public List<OrderStatus> statuses() {
        return statuses;
    }
}
