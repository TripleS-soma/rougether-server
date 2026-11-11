package com.triples.rougether.adminapi.feedback.dto;

import java.util.List;

public record FeedbackPage<T>(List<T> items, Long nextCursor) {}
