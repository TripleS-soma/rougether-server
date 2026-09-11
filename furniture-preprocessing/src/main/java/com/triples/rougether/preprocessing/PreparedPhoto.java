package com.triples.rougether.preprocessing;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

// 동기 전송이 실제로 종료될 때까지 호출자가 소유함. 스트림 close는 PNG 소유권을 해제하지 않음.
public final class PreparedPhoto implements AutoCloseable {
    private final Arena arena;
    private final MemorySegment png;
    private final String sha256;
    private final int width;
    private final int height;
    private boolean closed;

    private PreparedPhoto(Arena arena, MemorySegment png, int width, int height) {
        this.arena = arena;
        this.png = png.asReadOnly();
        this.width = width;
        this.height = height;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(this.png.asByteBuffer());
            sha256 = HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static PreparedPhoto copyOf(MemorySegment encoded, int width, int height) {
        if (encoded.byteSize() < 1 || encoded.byteSize() > VipsPhotoPreprocessor.MAX_BYTES) {
            throw new IllegalArgumentException("PNG 크기 초과");
        }
        Arena output = Arena.ofShared();
        try {
            var data = output.allocate(encoded.byteSize());
            data.copyFrom(encoded);
            return new PreparedPhoto(output, data, width, height);
        } catch (Throwable e) { output.close(); throw e; }
    }

    public long size() { return png.byteSize(); }
    public String sha256() { return sha256; }
    public int width() { return width; }
    public int height() { return height; }
    public synchronized boolean isAlive() { return !closed; }

    // SDK 재시도마다 position이 독립된 스트림을 처음부터 제공함.
    public synchronized InputStream openStream() {
        if (closed) throw new IllegalStateException("PNG 소유권이 종료됨");
        return new NativeInputStream(png.asByteBuffer());
    }

    @Override public synchronized void close() {
        if (closed) return;
        arena.close();
        closed = true;
    }

    private static final class NativeInputStream extends InputStream {
        private final ByteBuffer buffer;
        private int mark;
        private boolean closed;
        NativeInputStream(ByteBuffer buffer) { this.buffer = buffer; }
        private void check() throws IOException { if (closed) throw new IOException("스트림이 종료됨"); }
        @Override public int read() throws IOException {
            check(); return buffer.hasRemaining() ? buffer.get() & 255 : -1;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            check(); Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            if (!buffer.hasRemaining()) return -1;
            int count = Math.min(length, buffer.remaining());
            buffer.get(bytes, offset, count);
            return count;
        }
        @Override public boolean markSupported() { return true; }
        @Override public void mark(int limit) { mark = buffer.position(); }
        @Override public void reset() throws IOException { check(); buffer.position(mark); }
        @Override public void close() { closed = true; }
    }
}
