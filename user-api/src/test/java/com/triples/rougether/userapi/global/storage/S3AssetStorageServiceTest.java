package com.triples.rougether.userapi.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;

class S3AssetStorageServiceTest {

    private static final String BUCKET = "assets-test";
    private static final String PROFILE_KEY = "profile/member-image.png";

    private final S3Client s3Client = mock(S3Client.class);
    private final S3AssetStorageService service = new S3AssetStorageService(
            s3Client,
            new AssetProperties(new AssetProperties.S3(BUCKET, "ap-northeast-2", true)));

    @Test
    void deletePurgesEveryVersionAndDeleteMarkerForTheExactProfileKey() {
        when(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(
                        ListObjectVersionsResponse.builder()
                                .versions(
                                        objectVersion(PROFILE_KEY, "version-2"),
                                        objectVersion(PROFILE_KEY + ".backup", "unrelated-version"))
                                .deleteMarkers(deleteMarker(PROFILE_KEY, "delete-marker"))
                                .isTruncated(true)
                                .nextKeyMarker(PROFILE_KEY)
                                .nextVersionIdMarker("delete-marker")
                                .build(),
                        ListObjectVersionsResponse.builder()
                                .versions(objectVersion(PROFILE_KEY, "version-1"))
                                .isTruncated(false)
                                .build());

        service.delete(PROFILE_KEY);

        ArgumentCaptor<ListObjectVersionsRequest> listCaptor =
                ArgumentCaptor.forClass(ListObjectVersionsRequest.class);
        verify(s3Client, times(2)).listObjectVersions(listCaptor.capture());
        assertThat(listCaptor.getAllValues())
                .extracting(
                        ListObjectVersionsRequest::bucket,
                        ListObjectVersionsRequest::prefix,
                        ListObjectVersionsRequest::keyMarker,
                        ListObjectVersionsRequest::versionIdMarker)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(BUCKET, PROFILE_KEY, null, null),
                        org.assertj.core.groups.Tuple.tuple(
                                BUCKET, PROFILE_KEY, PROFILE_KEY, "delete-marker"));

        ArgumentCaptor<DeleteObjectRequest> deleteCaptor =
                ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(3)).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getAllValues())
                .extracting(DeleteObjectRequest::bucket, DeleteObjectRequest::key, DeleteObjectRequest::versionId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(BUCKET, PROFILE_KEY, "version-2"),
                        org.assertj.core.groups.Tuple.tuple(BUCKET, PROFILE_KEY, "delete-marker"),
                        org.assertj.core.groups.Tuple.tuple(BUCKET, PROFILE_KEY, "version-1"));
    }

    @Test
    void deleteKeepsPlainDeleteSemanticsForUnversionedBuckets() {
        S3AssetStorageService unversionedService = new S3AssetStorageService(
                s3Client,
                new AssetProperties(new AssetProperties.S3(BUCKET, "ap-northeast-2", false)));

        unversionedService.delete(PROFILE_KEY);

        ArgumentCaptor<DeleteObjectRequest> deleteCaptor =
                ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(deleteCaptor.getValue().key()).isEqualTo(PROFILE_KEY);
        assertThat(deleteCaptor.getValue().versionId()).isNull();
        verify(s3Client, times(0)).listObjectVersions(any(ListObjectVersionsRequest.class));
    }

    private ObjectVersion objectVersion(String key, String versionId) {
        return ObjectVersion.builder().key(key).versionId(versionId).build();
    }

    private DeleteMarkerEntry deleteMarker(String key, String versionId) {
        return DeleteMarkerEntry.builder().key(key).versionId(versionId).build();
    }
}
