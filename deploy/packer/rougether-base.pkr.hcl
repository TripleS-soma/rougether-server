# Rougether half-baked base AMI.
#
# 공통 런타임(Docker, AWS CLI, python3)과 rougether systemd 유닛 정의를 AMI 에 굽는다.
# 시크릿(SSM), 이미지 태그, env 파일, 서비스 기동은 굽지 않는다 — 부팅 시 user-data 가 처리.
# 유닛 파일 정본은 files/*.service (terraform user-data 폴백 경로도 같은 파일을 주입받는다).
#
# 빌드:
#   cd deploy/packer
#   packer init .
#   packer validate .
#   packer build .
#
# 롤아웃(terraform):
#   deploy/terraform/ec2 에서 use_baked_ami=true 로 apply 하되, 인스턴스 lifecycle 이
#   ami 변경을 무시하므로 실제 교체는 terraform apply -replace=aws_instance.app 로 의도적으로 수행한다.

packer {
  required_plugins {
    amazon = {
      source  = "github.com/hashicorp/amazon"
      version = ">= 1.3.0"
    }
  }
}

variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

# 빌드용 임시 인스턴스 타입 (베이크 시간만 영향, 결과 AMI 와 무관)
variable "build_instance_type" {
  type    = string
  default = "t3.small"
}

variable "ami_name_prefix" {
  type    = string
  default = "rougether-base"
}

# 빌드 인스턴스 SSH(22) 인바운드를 허용할 CIDR. 기본값을 두지 않아 명시적으로 넣게 강제한다 —
# 미지정 시 packer 기본 동작이 0.0.0.0/0 으로 여는 것을 막기 위함. 운영자 IP /32 권장 (README 참고).
variable "build_ssh_cidrs" {
  type = list(string)
}

locals {
  timestamp = regex_replace(timestamp(), "[- TZ:]", "")

  # IAM cleanup 권한이 이 태그를 조건으로 제한된다. run_tags 에 넣어야 임시
  # key pair/security group/instance와 CreateImage 결과 AMI·snapshot에 생성 시점부터 붙는다.
  packer_resource_tags = {
    Project   = "rougether"
    ManagedBy = "packer"
  }
}

source "amazon-ebs" "al2023" {
  region        = var.aws_region
  instance_type = var.build_instance_type
  ssh_username  = "ec2-user"
  ami_name      = "${var.ami_name_prefix}-${local.timestamp}"

  temporary_security_group_source_cidrs = var.build_ssh_cidrs

  run_tags = merge(local.packer_resource_tags, {
    Name = "${var.ami_name_prefix}-build-${local.timestamp}"
  })

  source_ami_filter {
    filters = {
      name                = "al2023-ami-2023.*-x86_64"
      virtualization-type = "hvm"
      root-device-type    = "ebs"
    }
    owners      = ["amazon"]
    most_recent = true
  }

  tags = merge(local.packer_resource_tags, {
    Name    = "${var.ami_name_prefix}-${local.timestamp}"
    BaseAMI = "{{ .SourceAMIName }}"
  })

  snapshot_tags = merge(local.packer_resource_tags, {
    Name = "${var.ami_name_prefix}-${local.timestamp}"
  })
}

build {
  sources = ["source.amazon-ebs.al2023"]

  # 유닛 파일은 ssh 사용자 권한으로 /tmp 에 올린 뒤 root 로 제자리 이동
  provisioner "file" {
    sources     = ["${path.root}/files/rougether-user-api.service", "${path.root}/files/rougether-admin-api.service", "${path.root}/files/rougether-batch.service"]
    destination = "/tmp/"
  }

  provisioner "shell" {
    execute_command = "sudo -E bash '{{ .Path }}'"
    inline = [
      "set -euo pipefail",

      # 공통 런타임 — 부팅 때마다 하던 update/install 을 베이크 시점으로 이동
      "dnf update -y",
      "dnf install -y docker awscli python3",
      "systemctl enable docker",

      # rougether 유닛 정의 설치. enable/start 는 하지 않는다 —
      # env 파일·이미지가 없는 첫 부팅에서 crash-loop 하지 않도록 user-data 가 준비 후 기동한다.
      # install 을 쓰는 이유: mv 는 /tmp 의 SELinux 라벨(tmp_t)을 보존해 enforcing 전환 시
      # systemd 가 유닛 로드를 거부한다 — install 은 대상 디렉터리 컨텍스트를 상속한다.
      "mkdir -p /etc/rougether",
      "chmod 700 /etc/rougether",
      "install -o root -g root -m 644 /tmp/rougether-user-api.service /tmp/rougether-admin-api.service /tmp/rougether-batch.service /etc/systemd/system/",
      "rm -f /tmp/rougether-user-api.service /tmp/rougether-admin-api.service /tmp/rougether-batch.service",

      # 이미지 다이어트 + 다음 부팅에서 cloud-init(user-data)이 새로 돌게 초기화
      "dnf clean all",
      "rm -rf /var/cache/dnf",
      "cloud-init clean --logs",
      # AMI 하이진: 빌드 인스턴스의 정체성이 스냅샷에 남지 않게 제거 —
      # ssh 접속키/호스트키는 launch 시 cloud-init 이 재주입·재생성하고, machine-id 는 첫 부팅에 재생성된다
      "rm -f /home/ec2-user/.ssh/authorized_keys /root/.ssh/authorized_keys",
      "rm -f /etc/ssh/ssh_host_*",
      "truncate -s 0 /etc/machine-id",
    ]
  }
}
