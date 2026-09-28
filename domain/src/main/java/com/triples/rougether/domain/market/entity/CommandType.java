package com.triples.rougether.domain.market.entity;

// 접수 종류. EXPIRE 는 만료 스케줄러가 넣음.
public enum CommandType {
    PLACE, CANCEL, EXPIRE
}
