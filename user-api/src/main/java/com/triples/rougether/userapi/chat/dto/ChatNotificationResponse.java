package com.triples.rougether.userapi.chat.dto;

// 내 방별 채팅 푸시 음소거 상태(#435). 방 상태(ChatRoomResponse)는 구성원 전원이 같은 내용을 받는 공용 스냅샷이라
// 사용자별 값인 음소거는 따로 둠.
public record ChatNotificationResponse(Long roomId, boolean muted) {}
