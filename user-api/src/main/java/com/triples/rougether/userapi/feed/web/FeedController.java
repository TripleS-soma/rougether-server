package com.triples.rougether.userapi.feed.web;

import com.triples.rougether.userapi.feed.dto.*;
import com.triples.rougether.userapi.feed.service.*;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Feed", description = "전체 회원 공개 사진 피드")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/feed")
public class FeedController {
    private final FeedQueryService query;
    private final FeedCommandService commands;
    private final FeedImageService images;

    @Operation(summary = "전체 또는 작성자별 최신 피드", description = "authorId 생략은 전체 피드, 지정하면 해당 회원 게시물입니다.")
    @GetMapping("/posts")
    public FeedPageResponse<FeedPostResponse> list(@CurrentUser AuthUser user,
            @RequestParam(required = false) @Positive Long authorId,
            @RequestParam(required = false) @Positive Long cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return query.list(user.id(), authorId, cursor, size);
    }
    @Operation(summary = "게시물 상세")
    @GetMapping("/posts/{postId}")
    public FeedPostResponse get(@CurrentUser AuthUser user, @PathVariable @Positive Long postId) {
        return query.get(user.id(), postId);
    }
    @Operation(summary = "게시물 등록", description = "업로드한 imageId를 표시 순서로 전달합니다. clientPostId는 재시도 시 동일 UUID를 유지합니다.")
    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    public FeedPostResponse create(@CurrentUser AuthUser user, @Valid @RequestBody FeedCreateRequest request) {
        return query.get(user.id(), commands.create(user.id(), request));
    }
    @Operation(summary = "내 게시물 본문 수정", description = "사진 교체·순서 변경은 지원하지 않습니다.")
    @PatchMapping("/posts/{postId}")
    public FeedPostResponse update(@CurrentUser AuthUser user, @PathVariable @Positive Long postId,
                                   @Valid @RequestBody FeedUpdateRequest request) {
        commands.update(user.id(), postId, request.content());
        return query.get(user.id(), postId);
    }
    @Operation(summary = "내 게시물 삭제")
    @DeleteMapping("/posts/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthUser user, @PathVariable @Positive Long postId) { commands.delete(user.id(), postId); }

    @Operation(summary = "좋아요", description = "중복 요청에도 좋아요는 한 번만 저장됩니다.")
    @PutMapping("/posts/{postId}/like")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void like(@CurrentUser AuthUser user, @PathVariable @Positive Long postId) { commands.like(user.id(), postId, true); }
    @Operation(summary = "좋아요 취소")
    @DeleteMapping("/posts/{postId}/like")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlike(@CurrentUser AuthUser user, @PathVariable @Positive Long postId) { commands.like(user.id(), postId, false); }

    @Operation(summary = "댓글 목록", description = "댓글은 오래된 순서이며 다음 cursor 이후를 조회합니다.")
    @GetMapping("/posts/{postId}/comments")
    public FeedPageResponse<FeedCommentResponse> comments(@CurrentUser AuthUser user,
            @PathVariable @Positive Long postId, @RequestParam(required = false) @Positive Long cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return query.comments(user.id(), postId, cursor, size);
    }
    @Operation(summary = "댓글 등록")
    @PostMapping("/posts/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public FeedCommentResponse comment(@CurrentUser AuthUser user, @PathVariable @Positive Long postId,
                                      @Valid @RequestBody FeedCommentRequest request) {
        return commands.comment(user.id(), postId, request);
    }
    @Operation(summary = "내 댓글 삭제")
    @DeleteMapping("/posts/{postId}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(@CurrentUser AuthUser user, @PathVariable @Positive Long postId,
                              @PathVariable @Positive Long commentId) { commands.deleteComment(user.id(), postId, commentId); }

    @Operation(summary = "피드 사진 한 장 업로드", description = "JPEG/PNG 10MB 이하. 게시 전 본인만 조회할 수 있고 24시간 안에 게시물에 연결해야 합니다.")
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FeedImageResponse upload(@CurrentUser AuthUser user, @RequestPart("file") MultipartFile file) {
        return images.upload(user.id(), file);
    }
    @Operation(summary = "피드 사진 조회", description = "Authorization 헤더 필요. 비공개 key를 CDN에 직접 연결하지 않습니다.")
    @GetMapping("/images/{imageId}")
    public ResponseEntity<byte[]> image(@CurrentUser AuthUser user, @PathVariable @Positive Long imageId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff").body(images.read(user.id(), imageId));
    }
    @Operation(summary = "미사용 업로드 취소", description = "게시물에 연결하기 전 본인 이미지에만 사용합니다.")
    @DeleteMapping("/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelImage(@CurrentUser AuthUser user, @PathVariable @Positive Long imageId) { images.cancel(user.id(), imageId); }
}
