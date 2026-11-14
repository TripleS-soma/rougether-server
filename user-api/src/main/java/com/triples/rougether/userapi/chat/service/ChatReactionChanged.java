package com.triples.rougether.userapi.chat.service;

// 메시지의 공감이 바뀜. 커밋 후 공감 수신을 켠 소켓에만 알림. 내용은 소켓이 쓰기 직전에 DB에서 다시 읽음.
public record ChatReactionChanged(Long roomId, Long messageId) {}
