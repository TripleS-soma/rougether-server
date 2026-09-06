package com.triples.rougether.userapi.furniture.service;

// 공급자의 본문/키/사진/프롬프트를 예외 로그에 싣지 않고 안전한 분류 코드만 보존함.
public class FurnitureAiFailure extends RuntimeException {
    public FurnitureAiFailure(String code) { super(code); }
    public String code() { return getMessage(); }
}
