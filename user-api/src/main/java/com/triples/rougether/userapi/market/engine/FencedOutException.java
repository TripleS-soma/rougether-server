package com.triples.rougether.userapi.market.engine;

// 처리 트랜잭션의 펜싱 확인 실패: 리스 행의 번호가 이 엔진이 받은 번호와 다름(다른 인스턴스가 인수함).
public class FencedOutException extends RuntimeException {

    private final long staleToken;

    public FencedOutException(long staleToken, long currentToken) {
        super("fenced out: token " + staleToken + " but lease is at " + currentToken);
        this.staleToken = staleToken;
    }

    public long staleToken() {
        return staleToken;
    }
}
