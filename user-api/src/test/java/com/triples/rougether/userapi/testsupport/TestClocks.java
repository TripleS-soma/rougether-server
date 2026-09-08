package com.triples.rougether.userapi.testsupport;

import java.time.Clock;
import java.time.ZoneId;

// 서비스 생성자에 넣는 시계. 경계 시각을 고정해야 하는 테스트는 MutableClock을 쓴다
public final class TestClocks {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 운영과 같은 시스템 시계(KST). 기존 테스트가 "지금"을 기준으로 데이터를 만들 때 사용
    public static final Clock KST_SYSTEM = Clock.system(KST);

    private TestClocks() {
    }
}
