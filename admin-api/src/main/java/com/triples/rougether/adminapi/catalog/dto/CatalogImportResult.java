package com.triples.rougether.adminapi.catalog.dto;

// 적재 결과 — 새로 생성된 개수(이미 있던 건 멱등 skip).
public record CatalogImportResult(int themesCreated, int charactersCreated, int itemsCreated) {
}
