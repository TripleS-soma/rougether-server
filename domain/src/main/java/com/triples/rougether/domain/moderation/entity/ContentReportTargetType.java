package com.triples.rougether.domain.moderation.entity;

// 신고 대상 종류(#399). target_id 는 종류별 테이블 id(feed_posts / feed_comments / market_assets).
public enum ContentReportTargetType {
    FEED_POST, FEED_COMMENT, MARKET_ASSET
}
