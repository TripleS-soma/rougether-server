-- 금칙어 필터 (#209). 닉네임·집 이름·방명록·미션 제목의 비속어 차단용 목록.
-- word 는 정규화(소문자화 + 한글/영문/숫자 외 제거) 결과를 저장한다 - 매칭과 저장이 같은 형태를 쓰도록.
-- 시드는 deploy/seed/banned_words.json 을 admin import API 로 멱등 적재한다.

CREATE TABLE banned_words (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    word       VARCHAR(50) NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_banned_word UNIQUE (word)
);
