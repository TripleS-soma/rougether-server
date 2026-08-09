data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_vpc" "main" {
  count = var.create_network ? 1 : 0

  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(local.tags, { Name = "${local.name}-vpc" })
}

resource "aws_internet_gateway" "main" {
  count = var.create_network ? 1 : 0

  vpc_id = aws_vpc.main[0].id
  tags   = merge(local.tags, { Name = "${local.name}-igw" })
}

# NAT Gateway 비용을 만들지 않고 EC2 부트스트랩과 SSM/ECR 접근을 허용하는 public subnet 2개를 둠.
# RDS는 같은 subnet group을 쓰더라도 publicly_accessible=false이면 public IP를 받지 않음.
resource "aws_subnet" "public" {
  count = var.create_network ? length(var.public_subnet_cidrs) : 0

  vpc_id                  = aws_vpc.main[0].id
  cidr_block              = var.public_subnet_cidrs[count.index]
  availability_zone       = data.aws_availability_zones.available.names[count.index]
  map_public_ip_on_launch = true

  tags = merge(local.tags, {
    Name = "${local.name}-public-${count.index + 1}"
    Tier = "public"
  })
}

resource "aws_route_table" "public" {
  count = var.create_network ? 1 : 0

  vpc_id = aws_vpc.main[0].id
  tags   = merge(local.tags, { Name = "${local.name}-public" })
}

resource "aws_route" "internet" {
  count = var.create_network ? 1 : 0

  route_table_id         = aws_route_table.public[0].id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.main[0].id
}

resource "aws_route_table_association" "public" {
  count = var.create_network ? length(var.public_subnet_cidrs) : 0

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public[0].id
}

# Admin 인증 정보가 공용 인터넷 구간을 다시 통과하지 않도록 CloudFront VPC origin 전용
# private subnet을 둔다. NAT/인터넷 기본 경로는 필요하지 않다.
resource "aws_subnet" "admin_origin" {
  count = var.create_network ? 1 : 0

  vpc_id                  = aws_vpc.main[0].id
  cidr_block              = var.admin_origin_private_subnet_cidr
  availability_zone       = data.aws_availability_zones.available.names[0]
  map_public_ip_on_launch = false

  tags = merge(local.tags, {
    Name = "${local.name}-admin-origin-private"
    Tier = "private"
  })
}

resource "aws_route_table" "admin_origin_private" {
  count = var.create_network ? 1 : 0

  vpc_id = aws_vpc.main[0].id
  tags   = merge(local.tags, { Name = "${local.name}-admin-origin-private" })
}

resource "aws_route_table_association" "admin_origin_private" {
  count = var.create_network ? 1 : 0

  subnet_id      = aws_subnet.admin_origin[0].id
  route_table_id = aws_route_table.admin_origin_private[0].id
}

locals {
  vpc_id = var.create_network ? aws_vpc.main[0].id : data.aws_vpc.default[0].id
  subnet_ids = var.create_network ? aws_subnet.public[*].id : tolist(
    data.aws_subnets.default[0].ids
  )
}
