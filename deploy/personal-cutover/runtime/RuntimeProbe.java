import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sqs.SqsClient;

// The probe runs in the same SDK credential chain as the application JAR.
class RuntimeProbe {
    static final String BUCKET = "rougether-fallback-940482450790-assets";
    static final String ACCOUNT = "940482450790";
    static final String QUEUE = "https://sqs.ap-northeast-2.amazonaws.com/" + ACCOUNT + "/rougether-personal-furniture-jobs";
    static final byte[] PIXEL = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aS3sAAAAASUVORK5CYII=");
    static final String COMPLETED_FIXTURE = "{\"jobId\":\"161f4e19-5ad9-4ae6-9f9b-707abef1ce13\",\"executionId\":\"465113db-493f-4c28-9f73-fa63497c3ba9\"}";

    public static void main(String[] args) {
        String key = "private/chat/" + UUID.fromString(args[1]) + ".png";
        try (var s3 = S3Client.builder().region(Region.AP_NORTHEAST_2).build()) {
            switch (args[0]) {
                case "upload" -> {
                    var put = s3.putObject(r -> r.bucket(BUCKET).expectedBucketOwner(ACCOUNT).key(key)
                            .contentType("image/png").ifNoneMatch("*"), RequestBody.fromBytes(PIXEL));
                    var version = put.versionId();
                    if (version == null || version.equals("null")) throw new IllegalStateException("versioning required");
                    verifyBytes(s3, key);
                    System.out.print(version);
                }
                case "moderate" -> {
                    verifyBytes(s3, key);
                    try {
                        s3.deleteObject(r -> r.bucket(BUCKET).expectedBucketOwner(ACCOUNT).key(key).versionId(args[2]));
                        throw new IllegalStateException("admin deleted a private media version");
                    } catch (S3Exception expected) {
                        if (expected.statusCode() != 403) throw expected;
                    }
                    System.out.print("verified");
                }
                case "cleanup" -> {
                    var versions = s3.listObjectVersions(r -> r.bucket(BUCKET).expectedBucketOwner(ACCOUNT).prefix(key));
                    if (versions.versions().size() != 1 || !versions.versions().get(0).versionId().equals(args[2]))
                        throw new IllegalStateException("unexpected fixture versions");
                    s3.deleteObject(r -> r.bucket(BUCKET).expectedBucketOwner(ACCOUNT).key(key).versionId(args[2]));
                    var after = s3.listObjectVersions(r -> r.bucket(BUCKET).expectedBucketOwner(ACCOUNT).prefix(key));
                    if (!after.versions().isEmpty() || !after.deleteMarkers().isEmpty())
                        throw new IllegalStateException("fixture version remains");
                    System.out.print("verified");
                }
                case "publish" -> {
                    try (var sqs = SqsClient.builder().region(Region.AP_NORTHEAST_2).build()) {
                        System.out.print(sqs.sendMessage(r -> r.queueUrl(QUEUE).messageBody(COMPLETED_FIXTURE)).messageId());
                    }
                }
                default -> throw new IllegalArgumentException("unknown stage");
            }
        }
    }

    static void verifyBytes(S3Client s3, String key) {
        var bytes = s3.getObjectAsBytes(r -> r.bucket(BUCKET).expectedBucketOwner(ACCOUNT).key(key));
        if (!Arrays.equals(bytes.asByteArray(), PIXEL)) throw new IllegalStateException("fixture mismatch");
    }
}
