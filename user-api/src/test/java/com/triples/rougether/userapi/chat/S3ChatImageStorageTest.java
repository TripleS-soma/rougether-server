package com.triples.rougether.userapi.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.triples.rougether.infra.assets.AssetProperties;
import com.triples.rougether.infra.assets.AssetStorageService;
import com.triples.rougether.userapi.chat.service.S3ChatImageStorage;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class S3ChatImageStorageTest {
    private final S3Client s3 = mock(S3Client.class);
    private final AssetStorageService assets = mock(AssetStorageService.class);
    private final S3ChatImageStorage storage = new S3ChatImageStorage(s3,
            new AssetProperties(new AssetProperties.S3("assets-test", "ap-northeast-2", false)), assets);

    @Test
    void 채팅전용_key에_캐시와_덮어쓰기를_금지하고_전송시간을_제한한다() {
        String key = "private/chat/11111111-2222-3333-4444-555555555555.jpg";
        storage.put(key, new byte[]{1, 2});
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("assets-test");
        assertThat(request.getValue().key()).isEqualTo(key);
        assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(request.getValue().cacheControl()).isEqualTo("private, no-store");
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        assertThat(request.getValue().overrideConfiguration().orElseThrow().apiCallTimeout())
                .contains(Duration.ofSeconds(30));
        storage.delete(key);
        verify(assets).delete(key);
    }

    @Test
    void 다른_도메인의_key와_경로탈출은_S3요청_전에_거절한다() {
        for (String key : new String[]{"profile/a.jpg", "private/feed/11111111-2222-3333-4444-555555555555.jpg",
                "private/chat/../a.jpg", "private/chat/a.png"}) {
            assertThatThrownBy(() -> storage.put(key, new byte[0])).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.read(key)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(s3, assets);
    }
}
