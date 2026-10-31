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
}
