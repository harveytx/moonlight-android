package com.limelight.diagnostics;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

public class AnnexBClipTest {
    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    @Test
    public void splitsH264IntoAccessUnits() {
        byte[] sps = {0, 0, 0, 1, 0x67, 0x64, 0x00, 0x2a};
        byte[] pps = {0, 0, 0, 1, 0x68, (byte) 0xee, 0x3c};
        byte[] sei = {0, 0, 1, 0x06, 0x05, 0x01};
        byte[] idr = {0, 0, 1, 0x65, (byte) 0x88, 0x10};
        byte[] p1 = {0, 0, 0, 1, 0x41, (byte) 0x9a, 0x02};
        byte[] p2 = {0, 0, 1, 0x41, (byte) 0x9a, 0x03, 0x00, 0x05};

        AnnexBClip clip = AnnexBClip.parse(concat(sps, pps, sei, idr, p1, p2), false);
        assertEquals(3, clip.accessUnits.size());

        AnnexBClip.AccessUnit first = clip.accessUnits.get(0);
        assertTrue(first.keyFrame);
        assertEquals(2, first.parameterSets.size());
        assertEquals(AnnexBClip.NAL_PARAMETER_SPS, first.parameterSets.get(0).bufferType);
        assertArrayEquals(sps, first.parameterSets.get(0).data);
        assertArrayEquals(pps, first.parameterSets.get(1).data);
        assertArrayEquals(idr, first.pictureData);

        assertFalse(clip.accessUnits.get(1).keyFrame);
        assertArrayEquals(p1, clip.accessUnits.get(1).pictureData);
        // A zero byte inside the last NAL must not be mistaken for a 4-byte start code
        assertArrayEquals(p2, clip.accessUnits.get(2).pictureData);
    }

    @Test
    public void splitsHevcParameterSets() {
        byte[] vps = {0, 0, 0, 1, 0x40, 0x01, 0x0c};
        byte[] sps = {0, 0, 0, 1, 0x42, 0x01, 0x01};
        byte[] pps = {0, 0, 0, 1, 0x44, 0x01, (byte) 0xc1};
        byte[] idr = {0, 0, 0, 1, 0x28, 0x01, (byte) 0xaf}; // type 20 (IDR_N_LP)
        byte[] trail = {0, 0, 0, 1, 0x02, 0x01, (byte) 0xd0}; // type 1 (TRAIL_R)

        AnnexBClip clip = AnnexBClip.parse(concat(vps, sps, pps, idr, trail), true);
        assertEquals(2, clip.accessUnits.size());
        assertEquals(3, clip.accessUnits.get(0).parameterSets.size());
        assertEquals(AnnexBClip.NAL_PARAMETER_VPS, clip.accessUnits.get(0).parameterSets.get(0).bufferType);
        assertTrue(clip.accessUnits.get(0).keyFrame);
        assertFalse(clip.accessUnits.get(1).keyFrame);
    }

    private static AnnexBClip loadAsset(String name, boolean hevc) throws Exception {
        File f = new File("src/main/assets/bench/" + name);
        if (!f.exists()) {
            f = new File("app/src/main/assets/bench/" + name);
        }
        try (InputStream in = new FileInputStream(f)) {
            return AnnexBClip.load(in, hevc);
        }
    }

    @Test
    public void benchmarkClipsHaveTheExpectedStructure() throws Exception {
        for (boolean hevc : new boolean[]{false, true}) {
            AnnexBClip clip = loadAsset(hevc ? "hevc_1080p60.h265" : "h264_1080p60.h264", hevc);
            assertEquals(120, clip.accessUnits.size());
            AnnexBClip.AccessUnit first = clip.accessUnits.get(0);
            assertTrue(first.keyFrame);
            assertEquals(hevc ? 3 : 2, first.parameterSets.size());
            for (int i = 1; i < clip.accessUnits.size(); i++) {
                AnnexBClip.AccessUnit au = clip.accessUnits.get(i);
                assertFalse(au.keyFrame);
                assertTrue(au.parameterSets.isEmpty());
                assertNotNull(au.pictureData);
            }
        }
    }
}
