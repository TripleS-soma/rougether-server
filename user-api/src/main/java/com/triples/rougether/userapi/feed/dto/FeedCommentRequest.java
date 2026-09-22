package com.triples.rougether.userapi.feed.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record FeedCommentRequest(@NotNull UUID clientCommentId, @NotBlank @Size(max = 500) String content) { }
