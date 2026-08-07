resource "aws_s3_bucket" "assets" {
  count = var.create_asset_bucket ? 1 : 0

  bucket        = var.asset_bucket_name
  force_destroy = false
  tags          = merge(local.tags, { Name = var.asset_bucket_name })
}

resource "aws_s3_bucket_ownership_controls" "assets" {
  count = var.create_asset_bucket ? 1 : 0

  bucket = aws_s3_bucket.assets[0].id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "assets" {
  count = var.create_asset_bucket ? 1 : 0

  bucket                  = aws_s3_bucket.assets[0].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "assets" {
  count = var.create_asset_bucket ? 1 : 0

  bucket = aws_s3_bucket.assets[0].id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "assets" {
  count = var.create_asset_bucket ? 1 : 0

  bucket = aws_s3_bucket.assets[0].id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_cloudfront_origin_access_control" "assets" {
  count = var.create_asset_bucket ? 1 : 0

  name                              = "${local.name}-assets-oac"
  description                       = "Private S3 access for Rougether assets"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "aws_cloudfront_cache_policy" "caching_optimized" {
  count = var.create_asset_bucket ? 1 : 0
  name  = "Managed-CachingOptimized"
}

resource "aws_cloudfront_distribution" "assets" {
  count = var.create_asset_bucket ? 1 : 0

  enabled         = true
  comment         = "${local.name} private asset CDN"
  http_version    = "http2and3"
  is_ipv6_enabled = true
  price_class     = "PriceClass_200"

  origin {
    domain_name              = aws_s3_bucket.assets[0].bucket_regional_domain_name
    origin_id                = "${local.name}-asset-s3"
    origin_access_control_id = aws_cloudfront_origin_access_control.assets[0].id
  }

  default_cache_behavior {
    target_origin_id       = "${local.name}-asset-s3"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized[0].id
    compress               = true
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }

  tags = merge(local.tags, { Name = "${local.name}-asset-cdn" })
}

resource "aws_s3_bucket_policy" "assets_cloudfront_read" {
  count = var.create_asset_bucket ? 1 : 0

  bucket = aws_s3_bucket.assets[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowCloudFrontReadOnly"
      Effect    = "Allow"
      Principal = { Service = "cloudfront.amazonaws.com" }
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.assets[0].arn}/*"
      Condition = {
        StringEquals = {
          "AWS:SourceArn" = aws_cloudfront_distribution.assets[0].arn
        }
      }
    }]
  })
}

locals {
  asset_bucket_name_value = var.asset_bucket_name
  asset_public_base_url_value = var.create_asset_bucket ? (
    "https://${aws_cloudfront_distribution.assets[0].domain_name}"
  ) : var.asset_public_base_url
}
