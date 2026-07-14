# Rougether half-baked 베이스 AMI

공통 런타임을 AMI 에 미리 굽고(half-baked), 인스턴스 고유·가변 레이어만 부팅 시 user-data 로 처리합니다.

- AMI 에 굽는 것 — dnf update, Docker, AWS CLI, python3, rougether systemd 유닛 정의(`files/*.service`)
- 부팅 시 처리하는 것 — SSM 시크릿 → env 파일, Firebase 자격증명, ECR 로그인, 이미지 pull, 서비스 enable/start
- 굽지 않는 것 — 시크릿 전부(AMI 스냅샷에 남으면 안 됨), 앱 이미지(태그가 배포마다 바뀜)

유닛 파일 사본은 현재 두 곳에 존재합니다 — 유닛을 수정할 때는 둘을 함께 갱신해야 합니다.

- `files/*.service` (이 디렉터리) — packer 가 AMI 에 굽고, terraform 순정 AMI 폴백(user-data)도 같은 파일을 주입받는다.
- `.github/scripts/deploy-ec2-with-rollback.sh` 의 `write_units()` — GitHub Actions 배포가 **매 배포마다** 자체 인라인 사본으로 유닛을 덮어쓴다. 즉 구운 유닛은 첫 배포까지만 유효하고, 여기만 빼먹으면 유닛 변경이 다음 배포에서 소리 없이 되돌아간다.

배포 스크립트도 `files/*.service` 를 소비하도록 단일화하는 것이 후속 과제입니다. 유닛의 `__FIREBASE_MOUNT_OPTION__` 는 부팅/배포 시 sed 로 치환되는 자리표시자이므로 그대로 두어야 합니다.

## 빌드

packer 와 AWS 자격증명(EC2/AMI 생성 권한)이 필요합니다. Terraform 배포 권한만으로는
AMI 베이크에 필요한 이미지·스냅샷·볼륨·임시 키페어 작업이 허용되지 않을 수 있습니다.

현재 `TripleS` 사용자는 고객 관리형 정책 `RougetherPackerBake` 로 아래 권한을 받습니다.
사용자 인라인 정책은 총 2,048바이트 한도가 있으므로 기존 Terraform 정책이 한도에
가까우면 별도 고객 관리형 정책으로 생성해 연결합니다.

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "PackerBake",
    "Effect": "Allow",
    "Action": [
      "ec2:CreateKeyPair", "ec2:DeleteKeyPair",
      "ec2:CreateImage", "ec2:RegisterImage", "ec2:DeregisterImage", "ec2:CopyImage",
      "ec2:CreateSnapshot", "ec2:DeleteSnapshot",
      "ec2:ModifyImageAttribute", "ec2:ModifySnapshotAttribute",
      "ec2:CreateVolume", "ec2:DeleteVolume", "ec2:AttachVolume", "ec2:DetachVolume"
    ],
    "Resource": "*"
  }]
}
```

```bash
cd deploy/packer
packer init .
packer validate -var 'build_ssh_cidrs=["203.0.113.10/32"]' .
packer build -var "build_ssh_cidrs=[\"$(curl -s ifconfig.me)/32\"]" .
```

`build_ssh_cidrs` 는 빌드용 임시 인스턴스의 SSH 를 허용할 CIDR 입니다. 기본값을 두지 않은 것은 의도입니다 — 생략하면 packer 가 22 번 포트를 0.0.0.0/0 으로 열기 때문에, 위처럼 운영자 IP /32 로 제한해 넘깁니다.

결과: `rougether-base-<timestamp>` 이름의 AMI (ap-northeast-2, self 소유). 빌드용 임시 인스턴스(t3.small)는 자동 종료됩니다.

## 롤아웃

1. 위에서 AMI 를 굽는다.
2. `deploy/terraform/ec2` 에서 `use_baked_ami = true` 를 tfvars 에 넣고 apply 한다. 인스턴스의 lifecycle 이 ami 변경을 무시하므로 이 시점엔 아무 일도 일어나지 않는다.
3. 실제 교체는 의도적으로 수행한다: `terraform apply -replace=aws_instance.app`
   - dev 인스턴스는 교체되면 public IP 가 바뀐다 — docs/operations/dev-credentials.md, 팀 공유 URL, deploy 정책의 instance id 를 갱신할 것.
4. 되돌리기: `use_baked_ami = false` 로 두고 다시 `-replace` 하면 순정 AL2023 + 풀 부트스트랩 경로로 돌아간다.

## AMI 갱신 주기

베이크 시점의 dnf update 가 스냅샷에 고정되므로, 보안 패치 반영을 위해 주기적으로(또는 베이스 AMI 갱신 알림 시) 다시 굽습니다. 오래된 rougether-base-* AMI 와 그 스냅샷은 콘솔/CLI 로 정리합니다 (deregister + snapshot 삭제).
