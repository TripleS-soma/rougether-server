import com.triples.rougether.preprocessing.VipsPhotoPreprocessor;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import tools.jackson.databind.json.JsonMapper;

/** 사적 사진 대신 합성 입력으로 배포 ZIP의 Linux 네이티브 동작을 검증함. */
public class NativeProbe {
    static byte[] encode(int w, int h, String format) throws Exception {
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }
    static byte[] orientation(byte[] jpeg, int orientation) throws Exception {
        // EXIF APP1, little-endian TIFF의 orientation 단일 항목임.
        byte[] exif = {'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,1,0,0x12,1,3,0,1,0,0,0,(byte) orientation,0,0,0,0,0,0,0};
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2); out.write(255); out.write(225);
        out.write((exif.length + 2) >>> 8); out.write((exif.length + 2) & 255);
        out.write(exif); out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        var codec = new VipsPhotoPreprocessor();
        byte[] jpeg = encode(2000, 1000, "jpeg");
        for (int o = 1; o <= 8; o++) {
            try (var photo = codec.prepare(orientation(jpeg, o)); var stream = photo.openStream(); var retry = photo.openStream()) {
                int w = o < 5 ? 1000 : 500, h = o < 5 ? 500 : 1000;
                if (photo.width() != w || photo.height() != h) throw new AssertionError("회전/축소 오류 " + o);
                byte[] bytes = stream.readAllBytes();
                if (!Arrays.equals(bytes, retry.readAllBytes())) throw new AssertionError("재시도 본문 불일치");
                var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
                if (decoded.getWidth() != w || decoded.getHeight() != h) throw new AssertionError("PNG 치수 오류");
            }
        }
        var alpha = new BufferedImage(128, 96, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 96; y++) for (int x = 0; x < 128; x++) alpha.setRGB(x, y, (x * 2 << 24) | 0x204060);
        var png = new ByteArrayOutputStream(); ImageIO.write(alpha, "png", png);
        for (int i = 0; i < 40; i++) {
            var photo = codec.prepare(png.toByteArray());
            var escaped = photo.openStream();
            try (photo; var stream = photo.openStream()) {
                var decoded = ImageIO.read(stream);
                for (int x = 0; x < 128; x++) if ((decoded.getRGB(x, 20) >>> 24) != x * 2) throw new AssertionError("alpha 변경");
            }
            try { escaped.read(); throw new AssertionError("해제 후 읽기 허용"); }
            catch (IllegalStateException expected) { }
            finally { escaped.close(); }
        }
        var invalid = List.of(new byte[0], "GIF89a".getBytes(), Arrays.copyOf(jpeg, 200),
                new byte[10 * 1024 * 1024 + 1], encode(31, 40, "png"), encode(8193, 32, "png"), encode(6000, 6000, "jpeg"));
        for (byte[] bad : invalid) {
            try (var photo = codec.prepare(bad)) { throw new AssertionError("잘못된 입력 수락"); }
            catch (IllegalArgumentException expected) { }
        }
        var result = Map.of("orientationCases", 8, "alphaAndCloseLoops", 40, "invalidInputs", invalid.size(), "externalCalls", 0);
        JsonMapper.builder().build().writeValue(Path.of(args[0], "results.json").toFile(), result);
        System.out.println("NATIVE_OK " + result);
    }
}
