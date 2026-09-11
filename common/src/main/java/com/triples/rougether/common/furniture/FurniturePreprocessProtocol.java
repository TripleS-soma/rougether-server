package com.triples.rougether.common.furniture;

public final class FurniturePreprocessProtocol {
    private FurniturePreprocessProtocol() { }
    public enum Phase { START, COMPLETE, FAIL }
    public enum Status { READY, DONE, TERMINAL, STALE }
    public record Command(String kind, Phase phase, String jobId, String sourceKey, String sourceVersion,
                          String outputKey, String outputVersion, String pngSha256, String failureCode) {
        public static Command start(String job, String key, String version) {
            return new Command("PREPROCESS", Phase.START, job, key, version, null, null, null, null);
        }
        public Command complete(String key, String version, String sha256) {
            return new Command(kind, Phase.COMPLETE, jobId, sourceKey, sourceVersion, key, version, sha256, null);
        }
        public Command fail(String code) {
            return new Command(kind, Phase.FAIL, jobId, sourceKey, sourceVersion, null, null, null, code);
        }
    }
    public record Input(String sha256, long bytes, String contentType) { }
    public record Reply(Status status, Input input) {
        public static Reply of(Status status) { return new Reply(status, null); }
    }
    public static String sourceKey(String jobId) { return "private/furniture-generation/raw/" + jobId; }
    public static String outputKey(String jobId) { return "private/furniture-generation/" + jobId + "/source/preprocessed.png"; }
}
