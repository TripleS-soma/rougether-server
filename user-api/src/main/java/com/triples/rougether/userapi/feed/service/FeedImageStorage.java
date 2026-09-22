package com.triples.rougether.userapi.feed.service;

public interface FeedImageStorage {
    void put(String key, byte[] bytes);
    byte[] read(String key);
    void delete(String key);
}
