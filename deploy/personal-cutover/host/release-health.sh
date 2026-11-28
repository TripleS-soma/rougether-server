#!/usr/bin/env bash
# 운영 후보의 이미지와 JAR, health, 최근 애플리케이션 오류 건수만 조회한다.
set -Eeuo pipefail
token=$(curl --noproxy '*' -fsS --max-time 3 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
test "$(curl --noproxy '*' -fsS --max-time 3 -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/instance-id)" = EXPECTED_MACHINE
python3 - <<'HEALTH_PY'
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess

state=Path('/var/lib/rougether-personal-cutover/serving')
ref=' '.join(os.environ['SSM_'+k] for k in ('Snapshot','Digest','ConfigDigest','Bundle'))
if ((state/'accepted-writes').read_text().strip()!=ref or (state/'selection').read_text().strip()!=ref or
    (state/'sealing').exists() or (state/'sealed').exists()):
    raise RuntimeError('Selected writer is not active')
services={'user-api':(8080,'/api/v1/health','8ae9387d6b4c8bb0741a058170c43c15518db6c00116c03fc77d4dfb91f094a2'),
          'admin-api':(8081,'/admin/health','2d0f2b7b33cd00ad7201ae2a7504f9c94c363374e915a27f900bbeeb0a92677b'),
          'batch':(8082,'/actuator/health','d0cddb42c2d814425c3830358f1014e5548d89054fc25fcfc139307856313024')}
image='eclipse-temurin@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112'
def read(*args):return subprocess.check_output(args,text=True,stderr=subprocess.DEVNULL).strip()
checked={}
for name,(port,path,digest) in services.items():
    container,=read('docker','ps','--no-trunc','-q','--filter','label=com.docker.compose.project=rougether-release','--filter','label=com.docker.compose.service='+name).splitlines()
    if not re.fullmatch('[a-f0-9]{64}',container):raise RuntimeError('Unexpected application identity')
    # Never print or persist the container environment or a raw log line.
    facts=json.loads(read('docker','inspect',container))[0]
    actual=read('docker','exec',container,'sha256sum','/app/app.jar').split()[0]
    if actual!=digest or facts['Config']['Image']!=image:raise RuntimeError('Application image or JAR differs')
    pid=str(facts['State']['Pid'])
    if not pid.isdigit() or int(pid)<=0:raise RuntimeError('Application stopped')
    status=int(read('nsenter','-t',pid,'-n','--','curl','--noproxy','*','--silent','--max-time','5','-o','/dev/null','-w','%{http_code}',f'http://127.0.0.1:{port}{path}'))
    start=dt.datetime.fromisoformat(re.sub(r'(\.\d{6})\d+',r'\1',facts['State']['StartedAt']).replace('Z','+00:00'))
    age=max(0,int((dt.datetime.now(dt.timezone.utc)-start).total_seconds()))
    errors=0
    with subprocess.Popen(['docker','logs','--since','1h',container],stdout=subprocess.PIPE,stderr=subprocess.STDOUT) as logs:
        for line in logs.stdout:
            if re.search(rb'\b(?:ERROR|FATAL)\b',line):errors+=1
        if logs.wait()!=0:raise RuntimeError('Container log reader failed')
    end=json.loads(read('docker','inspect',container))[0]['State']
    if not end['Running'] or end['StartedAt']!=facts['State']['StartedAt']:raise RuntimeError('Application restarted during inspection')
    checked[name]={'health':status,'imageDigest':facts['Image'],'jarDigest':actual,'errorOrFatalLines':errors,
                   'currentContainerSeconds':age,'coversLastHour':age>=3600}
if (state/'sealing').exists() or (state/'sealed').exists():raise RuntimeError('Target was sealed during inspection')
print(json.dumps(dict(snapshot=os.environ['SSM_Snapshot'],services=checked,windowSeconds=3600)))
HEALTH_PY
