package com.limelight.diagnostics;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class FastPngWriterTest {
    /** Minimal decoder for what FastPngWriter writes (8-bit RGB, filters None/Sub), with CRC checks. */
    private static int[] decode(byte[] png, int[] size) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(png));
        byte[] sig = new byte[8];
        in.readFully(sig);
        assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}, sig);
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        int w = 0, h = 0;
        boolean end = false;
        while (!end) {
            int len = in.readInt();
            byte[] type = new byte[4];
            in.readFully(type);
            byte[] data = new byte[len];
            in.readFully(data);
            CRC32 crc = new CRC32();
            crc.update(type);
            crc.update(data);
            assertEquals("CRC of " + new String(type, StandardCharsets.US_ASCII), (int) crc.getValue(), in.readInt());
            String t = new String(type, StandardCharsets.US_ASCII);
            if (t.equals("IHDR")) {
                DataInputStream ih = new DataInputStream(new ByteArrayInputStream(data));
                w = ih.readInt();
                h = ih.readInt();
                assertEquals(8, ih.readUnsignedByte());
                assertEquals(2, ih.readUnsignedByte());
            } else if (t.equals("IDAT")) {
                idat.write(data);
            } else if (t.equals("IEND")) {
                end = true;
            }
        }
        Inflater inflater = new Inflater();
        inflater.setInput(idat.toByteArray());
        byte[] raw = new byte[h * (1 + w * 3)];
        int n = 0;
        while (n < raw.length && !inflater.finished()) {
            n += inflater.inflate(raw, n, raw.length - n);
        }
        inflater.end();
        assertEquals(raw.length, n);
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int base = y * (1 + w * 3);
            int filter = raw[base];
            int pr = 0, pg = 0, pb = 0;
            for (int x = 0; x < w; x++) {
                int o = base + 1 + x * 3;
                int r = raw[o] & 0xff, g = raw[o + 1] & 0xff, b = raw[o + 2] & 0xff;
                if (filter == 1) {
                    r = (r + pr) & 0xff;
                    g = (g + pg) & 0xff;
                    b = (b + pb) & 0xff;
                } else {
                    assertEquals(0, filter);
                }
                pixels[y * w + x] = 0xff000000 | (r << 16) | (g << 8) | b;
                pr = r;
                pg = g;
                pb = b;
            }
        }
        size[0] = w;
        size[1] = h;
        return pixels;
    }

    private static void roundTrip(final int w, final int h, final int[] pixels, int level) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FastPngWriter.write(out, w, h, new FastPngWriter.RowSource() {
            @Override
            public void getRow(int y, int[] argb) {
                System.arraycopy(pixels, y * w, argb, 0, w);
            }
        }, level);

        int[] size = new int[2];
        int[] decoded = decode(out.toByteArray(), size);
        assertEquals(w, size[0]);
        assertEquals(h, size[1]);
        for (int i = 0; i < pixels.length; i++) {
            assertEquals("pixel " + i, pixels[i] | 0xff000000, decoded[i]);
        }
    }

    private static int[] noise(int count, long seed) {
        int[] pixels = new int[count];
        Random r = new Random(seed);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xff000000 | r.nextInt(0x1000000);
        }
        return pixels;
    }

    @Test
    public void randomImageIsLossless() throws Exception {
        roundTrip(37, 23, noise(37 * 23, 1234), Deflater.BEST_SPEED);
    }

    @Test
    public void largeImageSpansSeveralChunks() throws Exception {
        // Noise does not compress, so the data needs several 64 KB IDAT chunks
        roundTrip(300, 200, noise(300 * 200, 99), Deflater.BEST_SPEED);
    }

    @Test
    public void onePixelGratingIsExact() throws Exception {
        int w = 16, h = 4;
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (i % 2 == 0) ? 0xffffffff : 0xff000000;
        }
        roundTrip(w, h, pixels, Deflater.NO_COMPRESSION);
    }
}
