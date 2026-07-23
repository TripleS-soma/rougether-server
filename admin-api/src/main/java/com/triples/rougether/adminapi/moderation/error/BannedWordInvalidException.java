package com.triples.rougether.adminapi.moderation.error;

public class BannedWordInvalidException extends RuntimeException {

    private final int status;

    public BannedWordInvalidException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
