terraform {
  required_version = ">= 1.5.0"
  # 메인 스택(../ec2)의 로컬 state 는 옛 계정 것이라 쓰지 않는다. ai-ec2 와 같이 -backend-config 로 주입한다.
  backend "s3" {}

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.7"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "rougether"
      Environment = var.environment_name
      Component   = "monitoring"
      ManagedBy   = "terraform"
    }
  }
}
