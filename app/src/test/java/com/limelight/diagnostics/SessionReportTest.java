package com.limelight.diagnostics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SessionReportTest {
    @Test
    public void versionFolderNamesAreSinglePathComponents() {
        assertEquals("12.2-ULL.5", SessionReport.versionFolderName("12.2-ULL.5"));
        assertEquals("1.0_beta_2", SessionReport.versionFolderName("1.0 beta/2"));
        assertEquals("unversioned", SessionReport.versionFolderName(""));
        assertEquals("unversioned", SessionReport.versionFolderName(null));
    }

    @Test
    public void onlyCapturesAreMovedToTheCaptureFolder() {
        assertEquals(true, SessionReport.isCaptureFileName("capture-20261031-125026-h264-80mbps.png"));
        assertEquals(false, SessionReport.isCaptureFileName("session-20261031-125211-custom.json"));
        assertEquals(false, SessionReport.isCaptureFileName("decoder-bench-20261031-124426-h264.txt"));
        assertEquals(false, SessionReport.isCaptureFileName("capture-notes.txt"));
    }
}
