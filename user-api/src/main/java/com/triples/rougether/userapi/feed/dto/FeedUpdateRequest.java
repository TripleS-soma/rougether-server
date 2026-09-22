package com.triples.rougether.userapi.feed.dto;

import jakarta.validation.constraints.*;

public record FeedUpdateRequest(@NotNull @Size(max = 2000) String content) { }
