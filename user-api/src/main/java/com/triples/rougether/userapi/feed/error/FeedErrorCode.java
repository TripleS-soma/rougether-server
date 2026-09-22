package com.triples.rougether.userapi.feed.error;

import com.triples.rougether.common.error.ErrorCode;

public enum FeedErrorCode implements ErrorCode {
    FEED_POST_NOT_FOUND("게시물을 찾을 수 없습니다.", 404),
    FEED_COMMENT_NOT_FOUND("댓글을 찾을 수 없습니다.", 404),
    FEED_IMAGE_NOT_FOUND("이미지를 찾을 수 없습니다.", 404),
    FEED_FORBIDDEN("본인이 작성한 내용만 변경할 수 있습니다.", 403),
    FEED_INPUT_INVALID("피드 입력값이 올바르지 않습니다.", 400),
    FEED_CONTENT_BANNED("사용할 수 없는 단어가 포함되어 있습니다.", 400),
    FEED_REQUEST_CONFLICT("같은 요청 식별자로 다른 내용이나 삭제된 내용을 등록할 수 없습니다.", 409),
    FEED_IMAGE_INVALID("사진은 JPEG 또는 PNG, 10MB 이하만 허용됩니다.", 400),
    FEED_IMAGE_UNAVAILABLE("본인의 준비된 미사용 이미지만 등록할 수 있습니다.", 409),
    FEED_UPLOAD_LIMIT("미사용 사진은 최대 30장까지 보관할 수 있습니다.", 429),
    FEED_STORAGE_UNAVAILABLE("이미지 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.", 503);

    private final String message;
    private final int status;
    FeedErrorCode(String message, int status) { this.message = message; this.status = status; }
    public String code() { return name(); }
    public String message() { return message; }
    public int status() { return status; }
}
