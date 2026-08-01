package com.triples.rougether.infra.s3;

import java.time.Instant;

public record StoredAsset(String key, long size, Instant lastModified) {
}
