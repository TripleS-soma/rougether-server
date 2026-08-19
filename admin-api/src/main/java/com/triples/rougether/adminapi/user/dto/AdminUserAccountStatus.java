package com.triples.rougether.adminapi.user.dto;

// 어드민 유저 관측 계정 상태. ALL/ACTIVE/WITHDRAWN 은 사람 회원만 포함하고, 동거 봇(#308)은 BOT 으로만 조회된다.
public enum AdminUserAccountStatus {
    ALL,
    ACTIVE,
    WITHDRAWN,
    BOT
}
