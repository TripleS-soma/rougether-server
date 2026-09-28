package com.triples.rougether.userapi.market.engine;

// 이 인스턴스가 엔진 담당(리스 보유)인지와 담당 번호(펜싱 토큰). 구현은 EngineLeaseManager.
// holdsLease 는 이 인스턴스의 믿음일 뿐이라, 쓰기의 정합성은 처리 트랜잭션 안의 펜싱 확인이 보장함.
public interface MarketEngineLeaseGuard {

    boolean holdsLease();

    // 인수할 때 받은 펜싱 번호. 리스가 없으면 의미 없음.
    long fencingToken();

    // 엔진이 한 번 폴링을 마칠 때마다 호출. 오래 없으면 heartbeat 가 엔진 정체로 보고 리스를 내려놓음.
    void recordProgress();

    // 처리 트랜잭션에서 펜싱에 막힘 = 이미 다른 인스턴스가 담당. 해당 번호의 믿음을 버림.
    void fencedOut(long token);
}
