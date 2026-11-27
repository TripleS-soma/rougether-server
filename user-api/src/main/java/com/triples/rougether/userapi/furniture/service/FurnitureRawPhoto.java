package com.triples.rougether.userapi.furniture.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.furniture.error.FurnitureGenerationErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import org.springframework.web.multipart.MultipartFile;

/** 원본을 디코딩하지 않고 제한된 버퍼로 형식 식별과 재시도 해시만 계산함. */
record FurnitureRawPhoto(long bytes, String contentType, String sha256, String requestDigest) {
    static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    static FurnitureRawPhoto inspect(MultipartFile file, String hint) {
        if (file == null || file.getSize() < 1 || file.getSize() > MAX_BYTES) throw invalid();
        try (var input = file.getInputStream()) {
            var sourceHash = MessageDigest.getInstance("SHA-256");
            var requestHash = MessageDigest.getInstance("SHA-256");
            byte[] signature = input.readNBytes((int) Math.min(8, file.getSize() + 1));
            long total = signature.length;
            sourceHash.update(signature);
            requestHash.update(signature);
            byte[] buffer = new byte[8192];
            while (total <= file.getSize()) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, file.getSize() + 1 - total));
                if (read < 0) break;
                total += read;
                sourceHash.update(buffer, 0, read);
                requestHash.update(buffer, 0, read);
            }
            if (total != file.getSize()) throw invalid();
            String type;
            if (Arrays.equals(signature, PNG)) type = "image/png";
            else if (signature.length >= 3 && (signature[0] & 255) == 255
                    && (signature[1] & 255) == 216 && (signature[2] & 255) == 255) type = "image/jpeg";
            else throw invalid();
            // 기존 multipart 요청의 digest와 같아 전환 전후 동일 requestId를 재사용할 수 있음.
            requestHash.update((byte) 0);
            return new FurnitureRawPhoto(total, type, HexFormat.of().formatHex(sourceHash.digest()),
                    HexFormat.of().formatHex(requestHash.digest(hint.getBytes(StandardCharsets.UTF_8))));
        } catch (IOException e) { throw invalid(); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static BusinessException invalid() {
        return new BusinessException(FurnitureGenerationErrorCode.FURNITURE_PHOTO_INVALID);
    }
}
