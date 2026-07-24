package com.triples.rougether.userapi.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "카테고리 삭제 모드. "
        + "UNASSIGN(미분류 전환 — 참조하던 루틴·투두는 남기고 categoryId만 null로 바꿈), "
        + "PURGE(완전 삭제 — 과거 수행 기록과 살아있는 투두까지 삭제)")
public enum CategoryDeleteMode {
    UNASSIGN,
    PURGE
}
