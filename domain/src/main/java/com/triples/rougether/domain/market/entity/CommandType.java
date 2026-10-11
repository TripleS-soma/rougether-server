package com.triples.rougether.domain.market.entity;

// 접수 종류. EXPIRE 는 만료 스케줄러가 넣음. AMEND 는 발행 재고 판매 주문 수량 수정(#433).
public enum CommandType {
    PLACE, CANCEL, EXPIRE, AMEND
}
