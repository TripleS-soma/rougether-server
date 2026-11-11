package com.triples.rougether.adminapi.moderation.web;

import com.triples.rougether.adminapi.asset.service.StoredAsset;
import com.triples.rougether.adminapi.moderation.dto.AdminFeedPostDeleteResponse;
import com.triples.rougether.adminapi.moderation.dto.AdminFeedPostPageResponse;
import com.triples.rougether.adminapi.moderation.error.ContentReportAdminException;
import com.triples.rougether.adminapi.moderation.service.FeedPostAdminService;
import com.triples.rougether.common.error.ErrorResponse;
import java.security.Principal;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 어드민 피드 관리(#534). 화면(/feed-posts)에서 호출함. 세션 + CSRF 는 공통 보안 설정을 따름.
@RestController
@RequestMapping("/admin/feed-posts")
public class FeedPostAdminController {

    private static final int MAX_SIZE = 100;

    private final FeedPostAdminService service;

    public FeedPostAdminController(FeedPostAdminService service) {
        this.service = service;
    }

    // 최신 글순. includeDeleted=true 면 삭제된 글(tombstone)도 함께 보여줌.
    @GetMapping
    public AdminFeedPostPageResponse list(
            @RequestParam(value = "includeDeleted", defaultValue = "false") boolean includeDeleted,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new ContentReportAdminException("VALIDATION_FAILED", "입력값이 올바르지 않습니다.", 400);
        }
        return service.list(includeDeleted, page, size);
    }

    // 글을 삭제하고 그 글의 대기 신고를 닫음. 이미 삭제된 글이면 deleted=false.
    @DeleteMapping("/{id}")
    public AdminFeedPostDeleteResponse delete(@PathVariable Long id, Principal principal) {
        return service.delete(id, principal.getName());
    }

    // 게시물에 붙은 비공개 피드 사진 열람. 캐시하지 않음.
    @GetMapping("/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable Long imageId) {
        StoredAsset image = service.image(imageId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.IMAGE_JPEG)
                .body(image.content());
    }

    @ExceptionHandler(ContentReportAdminException.class)
    public ResponseEntity<ErrorResponse> handle(ContentReportAdminException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(ErrorResponse.of(exception.getCode(), exception.getMessage()));
    }
}
