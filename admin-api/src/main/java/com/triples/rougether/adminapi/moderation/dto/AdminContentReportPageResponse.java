package com.triples.rougether.adminapi.moderation.dto;

import java.util.List;

// offset 페이지 규약({items, page, size, totalElements}).
public record AdminContentReportPageResponse(List<AdminContentReportResponse> items, int page, int size,
                                             long totalElements) {
}
