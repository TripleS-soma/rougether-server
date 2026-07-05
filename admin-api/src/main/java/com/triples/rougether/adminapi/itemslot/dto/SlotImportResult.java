package com.triples.rougether.adminapi.itemslot.dto;

import java.util.List;

// 벌크 적재 결과. notFound = asset_key 미매칭, invalid = 슬롯 코드가 잘못됐거나 positioned 아이템이 아님.
public record SlotImportResult(int applied, List<String> notFound, List<String> invalid) {
}
