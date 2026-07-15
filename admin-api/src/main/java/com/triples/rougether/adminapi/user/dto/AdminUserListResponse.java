package com.triples.rougether.adminapi.user.dto;

import java.time.Instant;
import java.util.List;

// GET /admin/users 응답. 유저 목록 + 재화 잔액(지갑 미발급이면 0).
public record AdminUserListResponse(List<AdminUserRow> items, int page, int size, long total) {

    public record AdminUserRow(
            Long userId,
            String email,
            String nickname,
            Instant createdAt,
            long coinBalance,
            long diamondBalance) {
    }
}
