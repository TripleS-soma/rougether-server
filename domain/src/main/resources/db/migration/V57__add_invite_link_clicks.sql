-- 초대 링크 랜딩(/i/{code}, /h/{code}) 클릭 로그.
-- 초대 퍼널의 분모(링크 클릭 수)를 만든다 — 분자는 invite_rewards(친구 초대)·house_members(집 참여) 쪽에 이미 있다.
-- 코드 원본 테이블에 FK 를 걸지 않는다: 무효·회전된 코드의 클릭도 기록 대상이고, 로그가 원본 정리를 막으면 안 된다.
-- IP·User-Agent 원문은 저장하지 않는다(개인정보 최소화). os 는 UA 로 추정한 분류값만 남긴다.
CREATE TABLE invite_link_clicks (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    link_type  VARCHAR(10) NOT NULL,
    code       VARCHAR(20) NOT NULL,
    valid      BOOLEAN     NOT NULL,
    os         VARCHAR(10) NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (id)
);

-- 기간별 클릭 집계(초대 퍼널 관측)용.
CREATE INDEX idx_invite_link_clicks_type_created ON invite_link_clicks (link_type, created_at);
