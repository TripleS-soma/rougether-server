package com.triples.rougether.userapi.chat.service;

public interface ChatImageStorage {
    void put(String key, byte[] bytes);
    byte[] read(String key);
    void delete(String key);
}
