import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

class RenewalProbe {
    private static final String BUCKET = "rougether-fallback-940482450790-assets";
    private static final String ACCOUNT = "940482450790";

    public static void main(String[] args) throws InterruptedException {
        String role = args[0];
        String suffix = "__rougether_renewal__/" + UUID.randomUUID() + "/";
        try (var credentials = DefaultCredentialsProvider.builder().build();
             var s3 = S3Client.builder().region(Region.AP_NORTHEAST_2).credentialsProvider(credentials).build()) {
            var first = (AwsSessionCredentials) credentials.resolveCredentials();
            Instant issued = Instant.now();
            // Some SDK providers cache expiration internally without including it in the returned identity.
            var expiration = first.expirationTime().orElse(issued.plusSeconds(3600));
            var remaining = Duration.between(issued, expiration).toSeconds();
            if (remaining < 3420 || remaining > 3630) throw new IllegalStateException("unexpected initial TTL");
            listEmptyPrefix(role, suffix, s3);
            System.out.println("FIRST_REQUEST_OK");
            Thread.sleep(Duration.between(Instant.now(), expiration.plusSeconds(15)));
            var renewed = (AwsSessionCredentials) credentials.resolveCredentials();
            if (renewed.accessKeyId().equals(first.accessKeyId()) || renewed.sessionToken().equals(first.sessionToken())) {
                throw new IllegalStateException("credentials did not renew");
            }
            listEmptyPrefix(role, suffix, s3);
            System.out.println("RENEWED_REQUEST_OK");
        }
    }

    private static void listEmptyPrefix(String role, String suffix, S3Client s3) {
        if (role.equals("user")) {
            var response = s3.listObjectVersions(r -> r.bucket(BUCKET).expectedBucketOwner(ACCOUNT)
                    .prefix("private/chat/" + suffix).maxKeys(1));
            if (!response.versions().isEmpty() || !response.deleteMarkers().isEmpty()) throw new IllegalStateException("unexpected object");
        } else if (role.equals("admin")) {
            var response = s3.listObjectsV2(r -> r.bucket(BUCKET).expectedBucketOwner(ACCOUNT)
                    .prefix("characters/" + suffix).maxKeys(1));
            if (response.keyCount() != 0) throw new IllegalStateException("unexpected object");
        } else {
            throw new IllegalArgumentException("unknown role");
        }
    }
}
