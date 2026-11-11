package com.triples.rougether.adminapi.moderation.service;

import com.triples.rougether.adminapi.asset.service.AssetStorageService;
import com.triples.rougether.adminapi.asset.service.StoredAsset;
import com.triples.rougether.adminapi.moderation.dto.AdminFeedPostDeleteResponse;
import com.triples.rougether.adminapi.moderation.dto.AdminFeedPostPageResponse;
import com.triples.rougether.adminapi.moderation.dto.AdminFeedPostResponse;
import com.triples.rougether.adminapi.moderation.error.ContentReportAdminException;
import com.triples.rougether.domain.admin.repository.AdminUserRepository;
import com.triples.rougether.domain.feed.entity.FeedImage;
import com.triples.rougether.domain.feed.entity.FeedPost;
import com.triples.rougether.domain.feed.repository.FeedCommentRepository;
import com.triples.rougether.domain.feed.repository.FeedImageRepository;
import com.triples.rougether.domain.feed.repository.FeedLikeRepository;
import com.triples.rougether.domain.feed.repository.FeedPostRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.moderation.entity.ContentReportStatus;
import com.triples.rougether.domain.moderation.entity.ContentReportTargetType;
import com.triples.rougether.domain.moderation.repository.ContentReportRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

// 어드민 피드 관리(#534). 신고가 없어도 운영자가 글을 직접 보고 지울 수 있게 함.
// 삭제는 작성자 삭제·신고 숨김과 같은 경로: tombstone + 댓글·좋아요 제거(사진은 user-api 정리 작업이 비동기 파기).
// 그 글에 걸린 대기 신고는 ACTIONED 로 함께 닫음.
@Service
@Transactional(readOnly = true)
public class FeedPostAdminService {

    private static final int PREVIEW_MAX = 300;

    private final FeedPostRepository posts;
    private final FeedCommentRepository comments;
    private final FeedLikeRepository likes;
    private final FeedImageRepository images;
    private final ContentReportRepository reports;
    private final AdminUserRepository adminUsers;
    private final AssetStorageService storage;
    private final Clock clock;

    public FeedPostAdminService(FeedPostRepository posts, FeedCommentRepository comments, FeedLikeRepository likes,
                                FeedImageRepository images, ContentReportRepository reports,
                                AdminUserRepository adminUsers, AssetStorageService storage, Clock clock) {
        this.posts = posts;
        this.comments = comments;
        this.likes = likes;
        this.images = images;
        this.reports = reports;
        this.adminUsers = adminUsers;
        this.storage = storage;
        this.clock = clock;
    }

    public AdminFeedPostPageResponse list(boolean includeDeleted, int page, int size) {
        Page<FeedPost> found = posts.findAdminPage(includeDeleted, PageRequest.of(page, size));
        List<FeedPost> rows = found.getContent();
        Map<Long, List<Long>> imageIds = new HashMap<>();
        if (!rows.isEmpty()) {
            for (FeedImage image : images.findByPostIdInOrderByPostIdAscSortOrderAsc(
                    rows.stream().map(FeedPost::getId).toList())) {
                if (image.isReady()) {
                    imageIds.computeIfAbsent(image.getPost().getId(), key -> new ArrayList<>()).add(image.getId());
                }
            }
        }
        List<AdminFeedPostResponse> items = rows.stream().map(post -> {
            User author = post.getAuthor();
            return new AdminFeedPostResponse(post.getId(), post.getBoardType().name(), preview(post.getContent()),
                    author.getId(), author.getNickname(), author.isDeleted(),
                    imageIds.getOrDefault(post.getId(), List.of()), post.getCreatedAt(), post.getDeletedAt());
        }).toList();
        return new AdminFeedPostPageResponse(items, page, size, found.getTotalElements());
    }

    // 이미 삭제된 글이면 조치 없이 성공으로 돌려줌(멱등). 대기 신고는 그 경우에도 닫음.
    @Transactional
    public AdminFeedPostDeleteResponse delete(Long postId, String adminUsername) {
        Long adminId = adminUsers.findByUsername(adminUsername)
                .orElseThrow(() -> new ContentReportAdminException(
                        "ADMIN_NOT_FOUND", "어드민 계정을 찾을 수 없습니다: " + adminUsername, 401))
                .getId();
        FeedPost post = posts.findForUpdate(postId)
                .orElseThrow(() -> new ContentReportAdminException(
                        "FEED_POST_NOT_FOUND", "존재하지 않는 게시물입니다: " + postId, 404));
        Instant now = clock.instant();
        boolean deleted = post.getDeletedAt() == null;
        if (deleted) {
            post.delete(now);
            comments.deleteForPost(postId);
            likes.deleteForPost(postId);
        }
        int resolved = reports.resolveReceived(ContentReportTargetType.FEED_POST, postId,
                ContentReportStatus.ACTIONED, now, adminId);
        return new AdminFeedPostDeleteResponse(postId, deleted, resolved);
    }

    // 게시물에 붙은 사진만 열람 가능. 게시 전 업로드는 404.
    public StoredAsset image(Long imageId) {
        FeedImage image = images.findById(imageId)
                .filter(found -> found.isReady() && found.getPost() != null)
                .orElseThrow(FeedPostAdminService::imageNotFound);
        try {
            return storage.read(image.getStorageKey());
        } catch (NoSuchKeyException e) {
            throw imageNotFound();
        }
    }

    private static String preview(String text) {
        if (text == null || text.length() <= PREVIEW_MAX) {
            return text;
        }
        int end = PREVIEW_MAX - 1;
        // 서로게이트 페어 중간을 자르지 않음
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    private static ContentReportAdminException imageNotFound() {
        return new ContentReportAdminException("FEED_IMAGE_NOT_FOUND", "열람할 수 없는 사진입니다.", 404);
    }
}
