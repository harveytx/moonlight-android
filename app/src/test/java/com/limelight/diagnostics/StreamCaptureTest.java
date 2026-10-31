package com.limelight.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.Date;

public class StreamCaptureTest {
    private static Date at(int year, int month, int day, int hour, int minute, int second) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day, hour, minute, second);
        return c.getTime();
    }

    @Test
    public void fileNameCarriesTimeAndLabel() {
        Date when = at(2026, 10, 31, 15, 30, 12);
        assertEquals("capture-20261031-153012-h264-50mbps.png", StreamCapture.fileName(when, "h264-50mbps"));
        assertEquals("capture-20261031-153012.png", StreamCapture.fileName(when, null));
        // Anything that is not safe in a file name is replaced
        assertEquals("capture-20261031-153012-a_b_c.png", StreamCapture.fileName(when, "a/b c"));
    }

    @Test
    public void blackDetectionUsesEveryChannel() {
        int black = 0xff000000;
        int limitedBlack = 0xff101010; // limited-range black shown as full range
        assertTrue(StreamCapture.isBlack(new int[]{black, limitedBlack, black}, 24));
        assertFalse(StreamCapture.isBlack(new int[]{black, 0xff000040, black}, 24)); // blue
        assertFalse(StreamCapture.isBlack(new int[]{black, 0xffffffff}, 24));
        assertFalse(StreamCapture.isBlack(new int[0], 24));
    }
}
