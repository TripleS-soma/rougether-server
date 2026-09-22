package com.triples.rougether.userapi.feed.dto;

import com.triples.rougether.domain.feed.entity.FeedImage;

// 비공개 S3 key이며 CDN URL로 조합하지 않음. 실제 바이트는 인증된 피드 이미지 API로 읽음.
public record FeedImageResponse(Long imageId, String storageKey, int width, int height, String contentType) {
    public static FeedImageResponse of(FeedImage image) {
        return new FeedImageResponse(image.getId(), image.getStorageKey(), image.getWidth(), image.getHeight(), "image/jpeg");
    }
}
