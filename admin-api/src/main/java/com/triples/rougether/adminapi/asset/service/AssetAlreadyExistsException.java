package com.triples.rougether.adminapi.asset.service;

public class AssetAlreadyExistsException extends RuntimeException {

    public AssetAlreadyExistsException(String key) {
        super("이미 존재하는 asset key입니다: " + key);
    }
}
