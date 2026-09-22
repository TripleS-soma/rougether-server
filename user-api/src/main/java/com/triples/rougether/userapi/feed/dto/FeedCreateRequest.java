package com.triples.rougether.userapi.feed.dto;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record FeedCreateRequest(@NotNull UUID clientPostId, @Size(max = 2000) String content,
                                @NotNull @Size(min = 1, max = 10) List<@NotNull @Positive Long> imageIds) { }
