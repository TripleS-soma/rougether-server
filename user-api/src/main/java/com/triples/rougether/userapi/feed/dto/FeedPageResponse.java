package com.triples.rougether.userapi.feed.dto;

import java.util.List;

public record FeedPageResponse<T>(List<T> items, Long nextCursor, boolean hasNext) { }
