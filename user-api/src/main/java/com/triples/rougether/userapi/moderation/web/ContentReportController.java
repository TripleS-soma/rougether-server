package com.triples.rougether.userapi.moderation.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.moderation.dto.ContentReportRequest;
import com.triples.rougether.userapi.moderation.dto.ContentReportResponse;
import com.triples.rougether.userapi.moderation.service.ContentReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 콘텐츠 신고(#399). 피드 게시물·댓글과 거래소 종목(AI 사진 가구). App Store 1.2 신고 요건.
@Tag(name = "Report", description = "콘텐츠 신고 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class ContentReportController {

    private static final String COMMON = " 같은 대상을 다시 신고하면 새로 저장하지 않고 처음 신고를 201로 돌려줍니다(사유·설명은 처음 값 유지). "
            + "신고만으로 콘텐츠가 숨겨지지 않고 운영자가 검토합니다. 내가 올린 콘텐츠는 REPORT_SELF_TARGET(400)입니다.";

    private final ContentReportService reports;

    @Operation(summary = "피드 게시물 신고",
            description = "없거나 삭제·탈퇴로 숨겨진 글은 FEED_POST_NOT_FOUND(404). 차단한 회원의 글도 신고할 수 있습니다." + COMMON)
    @PostMapping("/feed/posts/{postId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ContentReportResponse reportPost(@CurrentUser AuthUser user, @PathVariable @Positive Long postId,
                                            @Valid @RequestBody ContentReportRequest request) {
        return reports.reportFeedPost(user.id(), postId, request);
    }

    @Operation(summary = "피드 댓글 신고",
            description = "부모 글이 없으면 FEED_POST_NOT_FOUND, 댓글이 없거나 삭제됐으면 FEED_COMMENT_NOT_FOUND(404)." + COMMON)
    @PostMapping("/feed/posts/{postId}/comments/{commentId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ContentReportResponse reportComment(@CurrentUser AuthUser user, @PathVariable @Positive Long postId,
                                               @PathVariable @Positive Long commentId,
                                               @Valid @RequestBody ContentReportRequest request) {
        return reports.reportFeedComment(user.id(), postId, commentId, request);
    }

    @Operation(summary = "거래소 종목(가구) 신고",
            description = "없는 종목은 MARKET_ASSET_NOT_FOUND(404). 거래 정지된 종목도 신고할 수 있으며, 운영자가 숨김 조치하면 SUSPENDED 가 됩니다."
                    + COMMON)
    @PostMapping("/market/assets/{assetId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ContentReportResponse reportAsset(@CurrentUser AuthUser user, @PathVariable @Positive Long assetId,
                                             @Valid @RequestBody ContentReportRequest request) {
        return reports.reportMarketAsset(user.id(), assetId, request);
    }
}
