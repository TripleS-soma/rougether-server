package com.triples.rougether.userapi.chat.service;

// 새 메시지가 저장됨(#435). 커밋 후 채팅 푸시가 받음. clientMessageId 재전송으로 기존 메시지를 돌려줄 때는 발행하지 않음.
public record ChatMessageSent(Long messageId) {}
