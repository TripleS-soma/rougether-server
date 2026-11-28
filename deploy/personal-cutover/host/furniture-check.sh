#!/usr/bin/env bash
set -euo pipefail
umask 077
exec 9>/run/rougether-deploy.lock
flock -w 60 9
dir=/var/lib/rougether-personal-cutover/furniture-fixture
test -s "$dir/ready"
db=rougether-furniture-fixture
test "$(docker inspect -f '{{.HostConfig.NetworkMode}}' "$db")" = rougether-furniture-isolated
sql() { docker exec -i "$db" mysql --defaults-extra-file=/run/private.cnf -N -B rougether_furniture_fixture; }
[[ "$(echo 'SELECT DATABASE(),@@event_scheduler,@@require_secure_transport' | sql)" = $'rougether_furniture_fixture\tDISABLED\t1' ]]
if [[ "$(echo 'SELECT COUNT(*) FROM users' | sql)" = 0 ]]; then
 sql >/dev/null <<'SQL'
START TRANSACTION;
INSERT INTO users(id,nickname,created_at,updated_at) VALUES(1,'migration_fixture',UTC_TIMESTAMP(),UTC_TIMESTAMP());
INSERT INTO themes(code,name,cover_image_key,is_active) VALUES('photo_furniture','Fixture',NULL,FALSE);
INSERT INTO furniture_worker_capacity(id,max_in_flight,execution_enabled,execution_mode) VALUES(1,2,TRUE,'LAMBDA');
INSERT INTO furniture_credit_accounts(user_id,account_token,balance,reserved) VALUES(1,UUID(),0,2);
SET @ok='161f4e19-5ad9-4ae6-9f9b-707abef1ce13',@fail='05245a3c-f8be-47ce-b76f-a1851ac5a530',@exec1='465113db-493f-4c28-9f73-fa63497c3ba9',@exec2='b0233796-3251-4ed1-a47c-631f38523d2f';
INSERT INTO furniture_generation_jobs(id,user_id,request_id,input_digest,target_hint,status,action,source_key,feedback,correction_prompt,next_run_at,expires_at,created_at,updated_at,execution_id)
SELECT id,1,UUID(),SHA2(id,256),'chair','QUEUED','EXTRACT',CONCAT('private/furniture-generation/',id,'/source.jpg'),'','',UTC_TIMESTAMP(6),UTC_TIMESTAMP()+INTERVAL 1 DAY,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),execid FROM (SELECT @ok id,@exec1 execid UNION ALL SELECT @fail,@exec2) j;
INSERT INTO furniture_lambda_execution(id,job_id,state,sequence,publish_after,created_at) VALUES(@exec1,@ok,'SENT',-1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),(@exec2,@fail,'SENT',-1,UTC_TIMESTAMP(),UTC_TIMESTAMP());
INSERT INTO furniture_credit_reservations(job_id,user_id,status,created_at,updated_at) VALUES(@ok,1,'RESERVED',UTC_TIMESTAMP(),UTC_TIMESTAMP()),(@fail,1,'RESERVED',UTC_TIMESTAMP(),UTC_TIMESTAMP());
COMMIT;
SQL
fi
test "$(echo "SELECT COUNT(*) FROM users WHERE id=1 AND nickname='migration_fixture'" | sql)" = 1
echo "SELECT JSON_OBJECT('fixture',CAST('true' AS JSON),'jobs',(SELECT JSON_OBJECTAGG(id,status) FROM furniture_generation_jobs),'charges',(SELECT COUNT(*) FROM furniture_credit_reservations WHERE status='SPENT'),'refunds',(SELECT COUNT(*) FROM furniture_credit_entries WHERE reason='RELEASE'),'pending',(SELECT reserved FROM furniture_credit_accounts WHERE user_id=1),'balance',(SELECT balance FROM furniture_credit_accounts WHERE user_id=1),'inventory',(SELECT COUNT(*) FROM user_items))" | sql
