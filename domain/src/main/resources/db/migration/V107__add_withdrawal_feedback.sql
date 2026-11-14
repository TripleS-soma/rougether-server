-- 선택형 탈퇴 사유. 계정 FK, 개인정보, 자유 입력, 정밀 시각 없이 KST 날짜별 통계만 보관함.
-- 기존 탈퇴 이력은 소급 생성하지 않음. reason NULL은 선택하지 않은 탈퇴를 뜻함.
CREATE TABLE withdrawal_feedback (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reason VARCHAR(30) NULL,
    response_date DATE NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_withdrawal_feedback_date ON withdrawal_feedback (response_date);
