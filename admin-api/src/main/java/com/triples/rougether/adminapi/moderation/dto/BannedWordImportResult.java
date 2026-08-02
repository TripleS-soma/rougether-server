package com.triples.rougether.adminapi.moderation.dto;

import java.util.List;

// 벌크 적재 결과. skip 은 이미 등록된 단어(멱등), invalid 는 정규화 결과가 빈/과길이 항목.
public record BannedWordImportResult(int added, int skipped, List<String> invalid) {
}
