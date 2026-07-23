package com.triples.rougether.userapi.bugreport.dto;

import java.util.List;

// GET /api/v1/me/bug-reports 응답. 본인 제보만, 최신순.
public record BugReportListResponse(List<BugReportResponse> items) {
}
