package com.triples.rougether.adminapi.asset;

import java.util.Set;

// 에셋 종류(=S3 key prefix) 값 집합. 업로드/조회가 공유한다.
public final class AssetKinds {

    public static final Set<String> ALLOWED =
            Set.of("characters", "categories", "themes", "items", "house");

    private AssetKinds() {
    }
}
