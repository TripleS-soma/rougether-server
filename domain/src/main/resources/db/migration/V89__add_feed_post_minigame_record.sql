-- 자유게시판 글이 연결한 미니게임 기록(기록 카드) 스냅샷. 게시 시점의 서버 검증 점수·순위·최고 기록 여부를
-- 그대로 보관하고 이후 바뀌지 않음. 판(run)은 탈퇴 정리로 지워질 수 있어 FK 를 두지 않음.
-- 기존 글은 연결 없이 NULL 로 남김(백필 없음).
ALTER TABLE feed_posts ADD COLUMN minigame_run_id VARCHAR(36) NULL;
ALTER TABLE feed_posts ADD COLUMN minigame_game_code VARCHAR(40) NULL;
ALTER TABLE feed_posts ADD COLUMN minigame_score INT NULL;
ALTER TABLE feed_posts ADD COLUMN minigame_rank BIGINT NULL;
ALTER TABLE feed_posts ADD COLUMN minigame_personal_best BOOLEAN NULL;
ALTER TABLE feed_posts ADD COLUMN minigame_achieved_at DATETIME(6) NULL;

-- 게임 완료 응답의 playCount(이 게임을 끝낸 판 수, 이 판 포함). 완료 시 회원 락 안에서 계산해 저장하므로
-- 같은 제출의 재요청도 같은 값을 돌려줌. 기존 완료 판은 NULL(재요청 시 완료 시각 기준으로 다시 셈).
ALTER TABLE minigame_runs ADD COLUMN play_count INT NULL;

-- playCount 를 회원·게임 범위에서 세기 위한 인덱스.
-- 기존에는 user_id FK 인덱스만 있어 회원의 모든 판(미완료 포함)을 훑어야 했음.
CREATE INDEX idx_minigame_run_user_game_finished
    ON minigame_runs (user_id, game_code, finished_at);
