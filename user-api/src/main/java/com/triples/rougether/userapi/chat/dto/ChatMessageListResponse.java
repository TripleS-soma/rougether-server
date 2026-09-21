package com.triples.rougether.userapi.chat.dto;

import java.util.List;

public record ChatMessageListResponse(List<ChatMessageResponse> items, Long nextCursor, boolean hasNext,
                                      ChatRoomResponse room) {}
