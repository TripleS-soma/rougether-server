package com.triples.rougether.adminapi.moderation.service;

import com.triples.rougether.adminapi.asset.service.AssetStorageService;
import com.triples.rougether.adminapi.asset.service.StoredAsset;
import com.triples.rougether.adminapi.moderation.dto.AdminContentReportPageResponse;
import com.triples.rougether.adminapi.moderation.dto.AdminContentReportResolveResponse;
import com.triples.rougether.adminapi.moderation.dto.AdminContentReportResponse;
import com.triples.rougether.adminapi.moderation.dto.AdminContentReportResponse.TargetPreview;
import com.triples.rougether.adminapi.moderation.error.ContentReportAdminException;
import com.triples.rougether.domain.admin.repository.AdminUserRepository;
import com.triples.rougether.domain.feed.entity.FeedComment;
import com.triples.rougether.domain.feed.entity.FeedImage;
import com.triples.rougether.domain.feed.entity.FeedPost;
import com.triples.rougether.domain.feed.repository.FeedCommentRepository;
import com.triples.rougether.domain.feed.repository.FeedImageRepository;
import com.triples.rougether.domain.feed.repository.FeedLikeRepository;
import com.triples.rougether.domain.feed.repository.FeedPostRepository;
import com.triples.rougether.domain.market.entity.MarketAsset;
import com.triples.rougether.domain.market.repository.MarketAssetRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.moderation.entity.ContentReport;
import com.triples.rougether.domain.moderation.entity.ContentReportStatus;
import com.triples.rougether.domain.moderation.entity.ContentReportTargetType;
import com.triples.rougether.domain.moderation.repository.ContentReportRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

// 신고 운영자 대기열·처리(#399). HIDE 는 각 도메인의 기존 삭제·정지 경로를 그대로 탐:
// 게시물 = 작성자 삭제와 같은 tombstone + 댓글·좋아요 제거(사진은 user-api 정리 작업이 비동기 파기),
// 댓글 = 댓글 삭제, 거래소 종목 = SUSPENDED(새 주문 거절, 목록 제외). 같은 대상의 대기 신고를 함께 닫음.
@Service
@Transactional(readOnly = true)
public class ContentReportAdminService {

    public enum Action { HIDE, DISMISS }

    private static final int PREVIEW_MAX = 200;

    private final ContentReportRepository reports;
    private final FeedPostRepository posts;
    private final FeedCommentRepository comments;
    private final FeedLikeRepository likes;
    private final FeedImageRepository images;
    private final MarketAssetRepository assets;
    private final ItemRepository items;
    private final UserRepository users;
    private final AdminUserRepository adminUsers;
    private final AssetStorageService storage;
    private final Clock clock;

    public ContentReportAdminService(ContentReportRepository reports, FeedPostRepository posts,
                                     FeedCommentRepository comments, FeedLikeRepository likes,
                                     FeedImageRepository images, MarketAssetRepository assets,
                                     ItemRepository items, UserRepository users, AdminUserRepository adminUsers,
                                     AssetStorageService storage, Clock clock) {
        this.reports = reports;
        this.posts = posts;
        this.comments = comments;
        this.likes = likes;
        this.images = images;
        this.assets = assets;
        this.items = items;
        this.users = users;
        this.adminUsers = adminUsers;
        this.storage = storage;
        this.clock = clock;
    }

    public AdminContentReportPageResponse list(ContentReportStatus status, int page, int size) {
        Page<ContentReport> found = status == null
                ? reports.findAllByOrderByIdDesc(PageRequest.of(page, size))
                : reports.findByStatusOrderByIdDesc(status, PageRequest.of(page, size));
        List<ContentReport> rows = found.getContent();
        Map<ContentReportTargetType, Map<Long, TargetPreview>> previews = previews(rows);
        Map<Long, String> nicknames = users.findAllById(rows.stream().map(ContentReport::getTargetOwnerUserId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .filter(user -> user.getNickname() != null)
                .collect(Collectors.toMap(User::getId, User::getNickname));
        List<AdminContentReportResponse> responses = rows.stream()
                .map(r -> AdminContentReportResponse.of(r, nicknames.get(r.getTargetOwnerUserId()),
                        previews.getOrDefault(r.getTargetType(), Map.of())
                                .getOrDefault(r.getTargetId(), TargetPreview.missing())))
                .toList();
        return new AdminContentReportPageResponse(responses, page, size, found.getTotalElements());
    }

    @Transactional
    public AdminContentReportResolveResponse resolve(Long reportId, Action action, String adminUsername) {
        ContentReport report = reports.findById(reportId)
                .orElseThrow(() -> new ContentReportAdminException(
                        "REPORT_NOT_FOUND", "존재하지 않는 신고입니다: " + reportId, 404));
        if (!report.isReceived()) {
            throw new ContentReportAdminException("REPORT_ALREADY_RESOLVED", "이미 처리된 신고입니다.", 409);
        }
        Long adminId = adminUsers.findByUsername(adminUsername)
                .orElseThrow(() -> new ContentReportAdminException(
                        "ADMIN_NOT_FOUND", "어드민 계정을 찾을 수 없습니다: " + adminUsername, 401))
                .getId();
        Instant now = clock.instant();
        if (action == Action.HIDE) {
            hide(report.getTargetType(), report.getTargetId(), now);
        }
        ContentReportStatus status = action == Action.HIDE ? ContentReportStatus.ACTIONED : ContentReportStatus.DISMISSED;
        int resolved = reports.resolveReceived(report.getTargetType(), report.getTargetId(), status, now, adminId);
        return new AdminContentReportResolveResponse(reportId, status, resolved);
    }

    // 신고된 게시물 사진만 열람 가능. 게시물에 붙지 않은 업로드·신고와 무관한 사진은 404.
    public StoredAsset feedImage(Long imageId) {
        FeedImage image = images.findById(imageId)
                .filter(found -> found.isReady() && found.getPost() != null)
                .filter(found -> reports.existsByTargetTypeAndTargetId(ContentReportTargetType.FEED_POST,
                        found.getPost().getId()))
                .orElseThrow(ContentReportAdminService::imageNotFound);
        try {
            return storage.read(image.getStorageKey());
        } catch (NoSuchKeyException e) {
            throw imageNotFound();
        }
    }

    // 이미 삭제·정지된 대상이면 조치 없이 신고만 닫음(멱등).
    private void hide(ContentReportTargetType type, Long targetId, Instant now) {
        switch (type) {
            case FEED_POST -> posts.findForUpdate(targetId).ifPresent(post -> {
                if (post.getDeletedAt() == null) {
                    // 작성자 삭제(FeedCommandService.delete)와 같은 경로. 사진은 정리 작업이 post.deleted_at 을 보고 파기.
                    post.delete(now);
                    comments.deleteForPost(targetId);
                    likes.deleteForPost(targetId);
                }
            });
            case FEED_COMMENT -> comments.findById(targetId).ifPresent(comment -> comment.delete(now));
            case MARKET_ASSET -> assets.findWithLockById(targetId).ifPresent(asset -> asset.suspend(now));
        }
    }

    private Map<ContentReportTargetType, Map<Long, TargetPreview>> previews(List<ContentReport> rows) {
        Map<ContentReportTargetType, List<Long>> ids = rows.stream().collect(Collectors.groupingBy(
                ContentReport::getTargetType, Collectors.mapping(ContentReport::getTargetId, Collectors.toList())));
        Map<ContentReportTargetType, Map<Long, TargetPreview>> result = new HashMap<>();
        List<Long> postIds = ids.getOrDefault(ContentReportTargetType.FEED_POST, List.of());
        if (!postIds.isEmpty()) {
            Map<Long, List<Long>> imageIds = new HashMap<>();
            for (FeedImage image : images.findByPostIdInOrderByPostIdAscSortOrderAsc(postIds.stream().distinct().toList())) {
                imageIds.computeIfAbsent(image.getPost().getId(), key -> new ArrayList<>()).add(image.getId());
            }
            result.put(ContentReportTargetType.FEED_POST, posts.findAllById(postIds).stream()
                    .collect(Collectors.toMap(FeedPost::getId, post -> new TargetPreview(preview(post.getContent()),
                            post.getId(), imageIds.getOrDefault(post.getId(), List.of()), null,
                            post.getDeletedAt() != null))));
        }
        List<Long> commentIds = ids.getOrDefault(ContentReportTargetType.FEED_COMMENT, List.of());
        if (!commentIds.isEmpty()) {
            result.put(ContentReportTargetType.FEED_COMMENT, comments.findAllById(commentIds).stream()
                    .collect(Collectors.toMap(FeedComment::getId, comment -> new TargetPreview(
                            preview(comment.getContent()), comment.getPost().getId(), List.of(), null,
                            comment.getDeletedAt() != null))));
        }
        List<Long> assetIds = ids.getOrDefault(ContentReportTargetType.MARKET_ASSET, List.of());
        if (!assetIds.isEmpty()) {
            List<MarketAsset> found = assets.findAllById(assetIds);
            Map<Long, Item> itemById = items.findAllById(found.stream().map(MarketAsset::getItemId).distinct().toList())
                    .stream().collect(Collectors.toMap(Item::getId, Function.identity()));
            result.put(ContentReportTargetType.MARKET_ASSET, found.stream()
                    .collect(Collectors.toMap(MarketAsset::getId, asset -> {
                        Item item = itemById.get(asset.getItemId());
                        return new TargetPreview(item == null ? null : item.getName(), null, List.of(),
                                item == null ? null : item.getAssetKey(), asset.isSuspended());
                    })));
        }
        return result;
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
        return new ContentReportAdminException("REPORT_IMAGE_NOT_FOUND", "열람할 수 없는 피드 사진입니다.", 404);
    }
}
