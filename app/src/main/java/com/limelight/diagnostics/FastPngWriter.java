package com.limelight.diagnostics;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * ULL: minimal PNG encoder (8-bit RGB, "Sub" filter, fast deflate) for video captures.
 *
 * Android's encoder (Bitmap.compress) took 0.75-7.5 s per 1080p capture on the ZTE's Cortex-A35
 * and the stream's decoding slowed down while it ran (12.2-ULL.7 data). Deflate level 1 with a
 * cheap filter does a fraction of that work; files are somewhat larger but still lossless.
 * Pure Java (no Android classes) so it can be unit tested.
 */
public final class FastPngWriter {
    /** Supplies one row of ARGB pixels. */
    public interface RowSource {
        void getRow(int y, int[] argb);
    }

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final int IDAT_CHUNK_SIZE = 64 * 1024;

    private FastPngWriter() {
    }

    public static void write(OutputStream out, int width, int height, RowSource rows, int deflateLevel)
            throws IOException {
        DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(out, IDAT_CHUNK_SIZE));
        dos.write(SIGNATURE);

        ByteArrayOutputStream ihdr = new ByteArrayOutputStream(13);
        DataOutputStream ih = new DataOutputStream(ihdr);
        ih.writeInt(width);
        ih.writeInt(height);
        ih.writeByte(8);  // bit depth
        ih.writeByte(2);  // colour type: RGB
        ih.writeByte(0);  // deflate
        ih.writeByte(0);  // adaptive filtering (per-row filter byte)
        ih.writeByte(0);  // no interlace
        writeChunk(dos, "IHDR", ihdr.toByteArray(), ihdr.size());

        Deflater deflater = new Deflater(deflateLevel);
        try {
            ChunkStream idat = new ChunkStream(dos);
            DeflaterOutputStream z = new DeflaterOutputStream(idat, deflater, IDAT_CHUNK_SIZE);
            int[] argb = new int[width];
            byte[] line = new byte[1 + width * 3];
            line[0] = 1; // "Sub": each byte minus the same channel of the pixel to the left
            for (int y = 0; y < height; y++) {
                rows.getRow(y, argb);
                int pr = 0, pg = 0, pb = 0;
                for (int x = 0, o = 1; x < width; x++) {
                    int p = argb[x];
                    int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                    line[o++] = (byte) (r - pr);
                    line[o++] = (byte) (g - pg);
                    line[o++] = (byte) (b - pb);
                    pr = r;
                    pg = g;
                    pb = b;
                }
                z.write(line);
            }
            z.finish();
            idat.flushChunk();
        } finally {
            deflater.end();
        }

        writeChunk(dos, "IEND", new byte[0], 0);
        dos.flush();
    }

    private static void writeChunk(DataOutputStream dos, String type, byte[] data, int length) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data, 0, length);
        dos.writeInt(length);
        dos.write(typeBytes);
        dos.write(data, 0, length);
        dos.writeInt((int) crc.getValue());
    }

    /** Collects compressed data and writes it as IDAT chunks. */
    private static final class ChunkStream extends OutputStream {
        private final DataOutputStream dos;
        private final byte[] buffer = new byte[IDAT_CHUNK_SIZE];
        private int size;

        ChunkStream(DataOutputStream dos) {
            this.dos = dos;
        }

        @Override
        public void write(int b) throws IOException {
            if (size == buffer.length) {
                flushChunk();
            }
            buffer[size++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (size == buffer.length) {
                    flushChunk();
                }
                int n = Math.min(len, buffer.length - size);
                System.arraycopy(b, off, buffer, size, n);
                size += n;
                off += n;
                len -= n;
            }
        }

        void flushChunk() throws IOException {
            if (size > 0) {
                writeChunk(dos, "IDAT", buffer, size);
                size = 0;
            }
        }
    }
}
