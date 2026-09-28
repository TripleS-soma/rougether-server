package com.triples.rougether.userapi.market.engine;

import org.springframework.stereotype.Component;

// #402 전 임시 구현. 여러 인스턴스가 동시에 엔진을 돌리지 않도록 market.engine.enabled 는 #402 전까지 끔.
@Component
public class AlwaysHeldLeaseGuard implements MarketEngineLeaseGuard {

    @Override
    public boolean holdsLease() {
        return true;
    }
}
