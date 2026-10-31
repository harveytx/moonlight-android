package com.limelight.diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits an Annex B H.264/HEVC elementary stream into access units, in the same shape that
 * moonlight-common-c hands them to the renderer: parameter sets as separate buffers and the
 * picture data of each frame as one buffer (start codes included).
 *
 * Only single-slice-per-frame streams are supported, which is what the benchmark clips and
 * Sunshine's low latency encoders produce. SEI and AUD NAL units are dropped.
 */
public final class AnnexBClip {
    public static final int NAL_PARAMETER_VPS = 3;   // Matches MoonBridge.BUFFER_TYPE_VPS
    public static final int NAL_PARAMETER_SPS = 1;   // Matches MoonBridge.BUFFER_TYPE_SPS
    public static final int NAL_PARAMETER_PPS = 2;   // Matches MoonBridge.BUFFER_TYPE_PPS

    public static final class ParameterSet {
        public final int bufferType;
        public final byte[] data;

        ParameterSet(int bufferType, byte[] data) {
            this.bufferType = bufferType;
            this.data = data;
        }
    }

    public static final class AccessUnit {
        public final List<ParameterSet> parameterSets = new ArrayList<>();
        public byte[] pictureData;
        public boolean keyFrame;
    }

    public final boolean hevc;
    public final List<AccessUnit> accessUnits = new ArrayList<>();

    private AnnexBClip(boolean hevc) {
        this.hevc = hevc;
    }

    public static AnnexBClip load(InputStream in, boolean hevc) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) {
            bos.write(buf, 0, r);
        }
        return parse(bos.toByteArray(), hevc);
    }

    // Returns the index of the next 3-byte start code at or after 'from', or -1
    private static int findStartCode(byte[] data, int from) {
        for (int i = from; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                return i;
            }
        }
        return -1;
    }

    public static AnnexBClip parse(byte[] data, boolean hevc) {
        AnnexBClip clip = new AnnexBClip(hevc);

        // Collect NAL unit boundaries. A NAL unit starts at its start code; a zero byte right
        // before a 3-byte start code makes it a 4-byte start code belonging to the next NAL.
        List<int[]> nals = new ArrayList<>(); // {start, end, headerOffset}
        int sc = findStartCode(data, 0);
        while (sc >= 0) {
            int start = (sc > 0 && data[sc - 1] == 0) ? sc - 1 : sc;
            int header = sc + 3;
            int next = findStartCode(data, header);
            int end;
            if (next < 0) {
                end = data.length;
            }
            else {
                end = (next > 0 && data[next - 1] == 0) ? next - 1 : next;
            }
            if (header < end) {
                nals.add(new int[]{start, end, header});
            }
            sc = next;
        }

        AccessUnit pending = new AccessUnit();
        for (int[] nal : nals) {
            int headerByte = data[nal[2]] & 0xFF;
            byte[] bytes = Arrays.copyOfRange(data, nal[0], nal[1]);

            if (hevc) {
                int type = (headerByte >> 1) & 0x3F;
                if (type == 32) {
                    pending.parameterSets.add(new ParameterSet(NAL_PARAMETER_VPS, bytes));
                }
                else if (type == 33) {
                    pending.parameterSets.add(new ParameterSet(NAL_PARAMETER_SPS, bytes));
                }
                else if (type == 34) {
                    pending.parameterSets.add(new ParameterSet(NAL_PARAMETER_PPS, bytes));
                }
                else if (type <= 31) {
                    // VCL NAL: one slice per frame, so this completes an access unit
                    pending.pictureData = bytes;
                    pending.keyFrame = type >= 16 && type <= 21;
                    clip.accessUnits.add(pending);
                    pending = new AccessUnit();
                }
                // SEI (39/40), AUD (35) and others are dropped
            }
            else {
                int type = headerByte & 0x1F;
                if (type == 7) {
                    pending.parameterSets.add(new ParameterSet(NAL_PARAMETER_SPS, bytes));
                }
                else if (type == 8) {
                    pending.parameterSets.add(new ParameterSet(NAL_PARAMETER_PPS, bytes));
                }
                else if (type == 1 || type == 5) {
                    pending.pictureData = bytes;
                    pending.keyFrame = type == 5;
                    clip.accessUnits.add(pending);
                    pending = new AccessUnit();
                }
                // SEI (6), AUD (9) and others are dropped
            }
        }

        return clip;
    }
}
