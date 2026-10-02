-- refresh 토큰 재사용 감지를 기기(로그인) 단위로 좁힌다(mobile #1388).
-- family_id: 로그인 1회마다 새 UUID, 회전은 같은 값을 물려받는다. 기존 row 는 NULL(레거시)이며,
--            아직 살아있는 레거시 토큰은 다음 회전 때 새 family 를 받는다.
-- revoke_reason: 폐기 사유(ROTATED·SUPERSEDED·LOGOUT·REUSE·WITHDRAWAL). 기존 폐기 row 는 NULL.
--               ROTATED·SUPERSEDED 로 폐기된 지 60초(jwt.refresh-reuse-grace) 안의 재제출은 응답 유실 재시도로 보고 재발급한다.
-- 주의: V81(서버 #423)보다 먼저 머지되면 Flyway 검증이 실패한다.

ALTER TABLE refresh_tokens ADD COLUMN family_id VARCHAR(36) NULL;
ALTER TABLE refresh_tokens ADD COLUMN revoke_reason VARCHAR(20) NULL;
CREATE INDEX idx_refresh_family ON refresh_tokens (family_id);
