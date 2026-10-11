package com.triples.rougether.userapi.chat.dto;

import jakarta.validation.constraints.NotNull;

// 방별 채팅 푸시 음소거 변경(#435)
public record ChatNotificationRequest(@NotNull Boolean muted) {}
