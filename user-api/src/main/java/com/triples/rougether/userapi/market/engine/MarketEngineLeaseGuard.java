package com.triples.rougether.userapi.market.engine;

// 이 인스턴스가 엔진 담당(리스 보유)인지. 리스·펜싱 구현은 #402 에서 이 인터페이스를 대체함.
public interface MarketEngineLeaseGuard {

    boolean holdsLease();
}
