locals {
  name = "${var.project_name}-${var.environment}"
  tags = merge(var.tags, {
    Project     = var.project_name
    Environment = var.environment
    ManagedBy   = "terraform"
  })

  db_password_param         = "/${local.name}/db/password"
  admin_seed_password_param = "/${local.name}/admin/seed-password"
  jwt_secret_param          = "/${local.name}/jwt/secret"
}

data "aws_caller_identity" "current" {}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

resource "random_password" "db" {
  length  = 32
  special = false
}

resource "random_password" "admin_seed" {
  count   = var.admin_seed_password == null ? 1 : 0
  length  = 24
  special = false
}

resource "random_password" "jwt" {
  count   = var.jwt_secret == null ? 1 : 0
  length  = 64
  special = false
}

locals {
  admin_seed_password_value = var.admin_seed_password == null ? random_password.admin_seed[0].result : var.admin_seed_password
  jwt_secret_value          = var.jwt_secret == null ? random_password.jwt[0].result : var.jwt_secret
}

resource "aws_ssm_parameter" "db_password" {
  name        = local.db_password_param
  description = "Rougether RDS password"
  type        = "SecureString"
  value       = random_password.db.result
  tags        = local.tags
}

resource "aws_ssm_parameter" "admin_seed_password" {
  name        = local.admin_seed_password_param
  description = "Rougether initial admin seed password"
  type        = "SecureString"
  value       = local.admin_seed_password_value
  tags        = local.tags
}

resource "aws_ssm_parameter" "jwt_secret" {
  name        = local.jwt_secret_param
  description = "Rougether user-api JWT secret"
  type        = "SecureString"
  value       = local.jwt_secret_value
  tags        = local.tags
}

resource "aws_security_group" "ec2" {
  name        = "${local.name}-ec2"
  description = "Rougether EC2 application access"
  vpc_id      = data.aws_vpc.default.id
  tags        = merge(local.tags, { Name = "${local.name}-ec2" })
}

resource "aws_vpc_security_group_ingress_rule" "user_api" {
  for_each          = toset(var.allowed_user_api_cidrs)
  security_group_id = aws_security_group.ec2.id
  cidr_ipv4         = each.value
  from_port         = 8080
  ip_protocol       = "tcp"
  to_port           = 8080
  description       = "user-api"
}

resource "aws_vpc_security_group_ingress_rule" "admin_api" {
  for_each          = toset(var.allowed_admin_api_cidrs)
  security_group_id = aws_security_group.ec2.id
  cidr_ipv4         = each.value
  from_port         = 8081
  ip_protocol       = "tcp"
  to_port           = 8081
  description       = "admin-api"
}

resource "aws_vpc_security_group_ingress_rule" "ssh" {
  for_each          = toset(var.allowed_ssh_cidrs)
  security_group_id = aws_security_group.ec2.id
  cidr_ipv4         = each.value
  from_port         = 22
  ip_protocol       = "tcp"
  to_port           = 22
  description       = "ssh"
}

resource "aws_vpc_security_group_egress_rule" "ec2_all" {
  security_group_id = aws_security_group.ec2.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "all outbound"
}

resource "aws_security_group" "rds" {
  name        = "${local.name}-rds"
  description = "Rougether RDS access from EC2"
  vpc_id      = data.aws_vpc.default.id
  tags        = merge(local.tags, { Name = "${local.name}-rds" })
}

resource "aws_vpc_security_group_ingress_rule" "rds_mysql_from_ec2" {
  security_group_id            = aws_security_group.rds.id
  referenced_security_group_id = aws_security_group.ec2.id
  from_port                    = 3306
  ip_protocol                  = "tcp"
  to_port                      = 3306
  description                  = "mysql from rougether ec2"
}

resource "aws_vpc_security_group_egress_rule" "rds_all" {
  security_group_id = aws_security_group.rds.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "all outbound"
}

resource "aws_db_subnet_group" "main" {
  name       = "${local.name}-db-subnet"
  subnet_ids = data.aws_subnets.default.ids
  tags       = merge(local.tags, { Name = "${local.name}-db-subnet" })
}

resource "aws_db_instance" "mysql" {
  identifier             = "${local.name}-mysql"
  engine                 = "mysql"
  engine_version         = "8.0"
  instance_class         = var.db_instance_class
  allocated_storage      = var.db_allocated_storage
  storage_type           = "gp2"
  db_name                = var.db_name
  username               = var.db_username
  password               = random_password.db.result
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period = var.db_backup_retention_period
  deletion_protection     = var.db_deletion_protection
  skip_final_snapshot     = var.db_skip_final_snapshot

  tags = merge(local.tags, { Name = "${local.name}-mysql" })
}

resource "aws_iam_role" "ec2" {
  name = "${local.name}-ec2-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "ec2.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })

  tags = local.tags
}

resource "aws_iam_role_policy_attachment" "ssm_managed_instance" {
  role       = aws_iam_role.ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "app" {
  name = "${local.name}-app-policy"
  role = aws_iam_role.ec2.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "ssm:GetParameter"
        ]
        Resource = concat(
          [
            aws_ssm_parameter.db_password.arn,
            aws_ssm_parameter.admin_seed_password.arn,
            aws_ssm_parameter.jwt_secret.arn
          ],
          var.container_registry_password_ssm_parameter == null ? [] : [
            "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter/${trim(var.container_registry_password_ssm_parameter, "/")}"
          ]
        )
      },
      {
        Effect = "Allow"
        Action = [
          "s3:PutObject"
        ]
        Resource = [
          for prefix in var.asset_allowed_prefixes :
          "arn:aws:s3:::${var.asset_bucket_name}/${prefix}"
        ]
      }
    ]
  })
}

resource "aws_iam_instance_profile" "ec2" {
  name = "${local.name}-ec2-profile"
  role = aws_iam_role.ec2.name
  tags = local.tags
}

resource "aws_instance" "app" {
  ami                         = data.aws_ami.al2023.id
  instance_type               = var.instance_type
  subnet_id                   = data.aws_subnets.default.ids[0]
  vpc_security_group_ids      = [aws_security_group.ec2.id]
  iam_instance_profile        = aws_iam_instance_profile.ec2.name
  key_name                    = var.key_name
  associate_public_ip_address = true

  credit_specification {
    cpu_credits = "standard"
  }

  root_block_device {
    volume_size = var.root_volume_size
    volume_type = "gp3"
    encrypted   = true
  }

  user_data_replace_on_change = true
  user_data = templatefile("${path.module}/templates/user-data.sh.tftpl", {
    aws_region                = var.aws_region
    user_api_image            = var.user_api_image
    admin_api_image           = var.admin_api_image
    registry_server           = var.container_registry_server
    registry_username         = var.container_registry_username == null ? "" : var.container_registry_username
    registry_password_param   = var.container_registry_password_ssm_parameter == null ? "" : var.container_registry_password_ssm_parameter
    db_url                    = "jdbc:mysql://${aws_db_instance.mysql.address}:3306/${var.db_name}?serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
    db_username               = var.db_username
    db_password_param         = aws_ssm_parameter.db_password.name
    jwt_secret_param          = aws_ssm_parameter.jwt_secret.name
    admin_seed_enabled        = tostring(var.admin_seed_enabled)
    admin_seed_username       = var.admin_seed_username
    admin_seed_password_param = aws_ssm_parameter.admin_seed_password.name
    asset_bucket_name         = var.asset_bucket_name
    asset_region              = var.asset_region
    asset_public_base_url     = var.asset_public_base_url
  })

  depends_on = [
    aws_db_instance.mysql,
    aws_iam_role_policy.app,
    aws_iam_role_policy_attachment.ssm_managed_instance
  ]

  tags = merge(local.tags, { Name = "${local.name}-app" })
}
