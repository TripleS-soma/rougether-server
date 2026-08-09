# user-api HTTPS 진입점.
#
# iOS ATS(App Transport Security)가 앱의 평문 HTTP 호출을 기본 차단하므로, 앱스토어 제출용으로
# 유효한 공인 인증서가 붙은 HTTPS 주소가 필요하다. 도메인 없이 이를 해결하기 위해 CloudFront 의
# 기본 도메인(*.cloudfront.net)과 기본 인증서를 사용한다. 도메인을 확보하면 aliases + ACM(us-east-1)
# 인증서를 추가하는 것으로 전환한다.
#
# user-api(:8080)와 admin-api(:8081)는 서로 다른 CloudFront 배포를 사용한다. admin-api origin은
# CloudFront origin-facing 관리형 prefix list에서만 접근할 수 있어 EC2 public IP:8081 직접 접속은
# 차단된다. SSM 포트 포워딩은 CloudFront 장애 시 비상 접근 경로로 유지한다.
#
# CloudFront → EC2 origin 구간은 HTTP 다(origin 에 인증서가 없어 https 불가). dev 스택에서 수용한
# 트레이드오프이며, 외부(앱↔CloudFront) 구간은 TLS 로 보호된다. 기존 :8080 직접 접속도 배포
# workflow 의 public health check 가 사용하므로 그대로 열어 둔다.

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# API 응답을 캐시하지 않고 쿼리스트링·쿠키·헤더(Authorization 포함)를 모두 origin 으로 전달한다.
# Host 는 제외해야 한다 — viewer 의 Host(xxx.cloudfront.net)가 그대로 가면 custom origin 라우팅이 깨진다.
data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

data "aws_cloudfront_response_headers_policy" "security_headers" {
  name = "Managed-SecurityHeadersPolicy"
}

data "aws_ec2_managed_prefix_list" "cloudfront_origin" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_cloudfront_distribution" "user_api" {
  enabled         = true
  comment         = "${local.name} user-api HTTPS entrypoint"
  http_version    = "http2and3"
  is_ipv6_enabled = true

  # PriceClass_100 은 아시아 엣지가 빠져 한국 사용자 지연이 커진다. 서울 엣지를 포함하는 200 사용.
  price_class = "PriceClass_200"

  origin {
    # EIP 의 public DNS 라 EC2 stop/start·재생성에도 주소가 유지된다. 인스턴스가 아닌
    # aws_instance.app.public_dns 를 쓰면 stop/start 때 origin 이 stale 호스트명으로 끊긴다.
    domain_name = aws_eip.app.public_dns
    origin_id   = "${local.name}-user-api-ec2"

    custom_origin_config {
      http_port              = 8080
      https_port             = 443
      origin_protocol_policy = "http-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    target_origin_id       = "${local.name}-user-api-ec2"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
    cached_methods         = ["GET", "HEAD"]

    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    # 기본 *.cloudfront.net 인증서. TLS 1.2+ 라 ATS 요건을 충족한다.
    cloudfront_default_certificate = true
  }

  tags = merge(local.tags, { Name = "${local.name}-user-api-cdn" })
}

resource "aws_cloudfront_distribution" "admin_api" {
  enabled         = true
  comment         = "${local.name} admin-api HTTPS entrypoint"
  http_version    = "http2and3"
  is_ipv6_enabled = true
  price_class     = "PriceClass_200"

  origin {
    domain_name = aws_eip.app.public_dns
    origin_id   = "${local.name}-admin-api-ec2"

    # CloudFront 전체 IP 대역 허용만으로는 다른 계정의 배포를 구분할 수 없으므로, 이 배포만 아는
    # 공유 비밀값을 origin 요청에 덮어쓰고 admin-api가 상수 시간 비교로 검증한다.
    custom_header {
      name  = "X-Rougether-Admin-Origin"
      value = random_password.admin_origin.result
    }

    custom_origin_config {
      http_port                = 8081
      https_port               = 443
      origin_keepalive_timeout = 5
      origin_protocol_policy   = "http-only"
      origin_read_timeout      = 60
      origin_ssl_protocols     = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    target_origin_id       = "${local.name}-admin-api-ec2"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    # 로그인 세션 쿠키, CSRF 토큰, multipart 요청을 원본에 그대로 전달하고 응답은 캐시하지 않는다.
    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id   = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
    response_headers_policy_id = data.aws_cloudfront_response_headers_policy.security_headers.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }

  tags = merge(local.tags, { Name = "${local.name}-admin-api-cdn" })
}
