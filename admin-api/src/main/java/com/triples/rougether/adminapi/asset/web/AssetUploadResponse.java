package com.triples.rougether.adminapi.asset.web;

// 업로드 결과. 전체 URL 이 아니라 object key 를 반환한다(spec 원칙). CDN base 조합은 클라이언트가 한다.
public record AssetUploadResponse(String key, String contentType, long size) {
}
