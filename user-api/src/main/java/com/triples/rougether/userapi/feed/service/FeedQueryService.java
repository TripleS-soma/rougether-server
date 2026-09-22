package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.feed.entity.*;
import com.triples.rougether.domain.feed.repository.*;
import com.triples.rougether.userapi.feed.dto.*;
import static com.triples.rougether.userapi.feed.error.FeedErrorCode.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedQueryService {
    private final FeedAccess access;
    private final FeedPostRepository posts;
    private final FeedImageRepository images;
    private final FeedLikeRepository likes;
    private final FeedCommentRepository comments;

    public FeedPageResponse<FeedPostResponse> list(Long viewer, Long authorId, Long cursor, int size) {
        access.active(viewer);
        validatePage(cursor, size);
        if (authorId != null && authorId <= 0) throw new BusinessException(FEED_INPUT_INVALID);
        List<FeedPost> found = posts.findPage(authorId, cursor, PageRequest.of(0, size + 1));
        boolean more = found.size() > size;
        List<FeedPost> page = more ? found.subList(0, size) : found;
        return new FeedPageResponse<>(render(viewer, page), more ? page.getLast().getId() : null, more);
    }
    public FeedPostResponse get(Long viewer, Long postId) {
        access.active(viewer);
        return render(viewer, List.of(visible(postId))).getFirst();
    }
    public FeedPageResponse<FeedCommentResponse> comments(Long viewer, Long postId, Long cursor, int size) {
        access.active(viewer);
        validatePage(cursor, size);
        visible(postId);
        List<FeedComment> found = comments.findPage(postId, cursor, PageRequest.of(0, size + 1));
        boolean more = found.size() > size;
        List<FeedComment> page = more ? found.subList(0, size) : found;
        return new FeedPageResponse<>(page.stream().map(c -> FeedCommentResponse.of(c, viewer)).toList(),
                more ? page.getLast().getId() : null, more);
    }
    private FeedPost visible(Long id) {
        return posts.findVisible(id).orElseThrow(() -> new BusinessException(FEED_POST_NOT_FOUND));
    }
    private void validatePage(Long cursor, int size) {
        if (size < 1 || size > 50 || (cursor != null && cursor <= 0)) throw new BusinessException(FEED_INPUT_INVALID);
    }
    // 페이지 크기와 무관하게 4회 일괄 조회함. 컬렉션 fetch join의 페이지 잘림을 피함.
    private List<FeedPostResponse> render(Long viewer, List<FeedPost> page) {
        if (page.isEmpty()) return List.of();
        List<Long> ids = page.stream().map(FeedPost::getId).toList();
        Map<Long, List<FeedImageResponse>> byPost = new HashMap<>();
        for (FeedImage image : images.findByPostIdInOrderByPostIdAscSortOrderAsc(ids)) {
            byPost.computeIfAbsent(image.getPost().getId(), key -> new ArrayList<>()).add(FeedImageResponse.of(image));
        }
        Map<Long, Long> likeCounts = counts(likes.countForPosts(ids));
        Map<Long, Long> commentCounts = counts(comments.countForPosts(ids));
        Set<Long> liked = new HashSet<>(likes.findLikedPostIds(viewer, ids));
        return page.stream().map(p -> new FeedPostResponse(p.getId(), FeedAuthorResponse.of(p.getAuthor()),
                p.getContent(), byPost.getOrDefault(p.getId(), List.of()), likeCounts.getOrDefault(p.getId(), 0L),
                commentCounts.getOrDefault(p.getId(), 0L), liked.contains(p.getId()), p.getAuthor().getId().equals(viewer),
                p.getCreatedAt(), p.getUpdatedAt())).toList();
    }
    private Map<Long, Long> counts(List<FeedCount> counts) {
        return counts.stream().collect(Collectors.toMap(FeedCount::getPostId, FeedCount::getTotal));
    }
}
