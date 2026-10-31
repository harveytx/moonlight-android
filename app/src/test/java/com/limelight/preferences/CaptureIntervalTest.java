package com.limelight.preferences;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CaptureIntervalTest {
    @Test
    public void parsesSecondsAndRejectsInvalidValues() {
        assertEquals(30, PreferenceConfiguration.parseCaptureInterval("30"));
        assertEquals(0, PreferenceConfiguration.parseCaptureInterval("0"));
        assertEquals(0, PreferenceConfiguration.parseCaptureInterval("-5"));
        assertEquals(0, PreferenceConfiguration.parseCaptureInterval("abc"));
        assertEquals(3600, PreferenceConfiguration.parseCaptureInterval("99999"));
    }
}
