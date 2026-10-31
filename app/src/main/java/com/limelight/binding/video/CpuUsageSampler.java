package com.limelight.binding.video;

import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Samples CPU usage of this process and of the native video receive thread from procfs.
 *
 * System-wide CPU usage (/proc/stat) is not readable by apps since Android 8, so only
 * our own process and threads can be measured. Values are percent of ONE core, so a
 * process using two cores fully reads 200 %.
 */
public class CpuUsageSampler {
    private static final String VIDEO_RECV_THREAD_NAME = "VideoRecv";

    private final long clockTicksPerSec;
    private long lastProcessTicks = -1;
    private long lastThreadTicks = -1;
    private long lastSampleNs;
    private int videoRecvTid = -1;
    private boolean available = true;

    private double processPercent = -1;
    private double videoRecvPercent = -1;

    public CpuUsageSampler() {
        // USER_HZ is 100 on every Android kernel we care about. Os.sysconf(_SC_CLK_TCK) would
        // be more correct, but its constant is hidden on older API levels.
        clockTicksPerSec = 100;
    }

    // Returns utime + stime from a /proc/.../stat file, or -1 on failure
    private static long readStatTicks(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            if (line == null) {
                return -1;
            }
            // The comm field may contain spaces, so parse after the closing parenthesis
            int end = line.lastIndexOf(')');
            if (end < 0) {
                return -1;
            }
            String[] fields = line.substring(end + 2).split(" ");
            // fields[0] is state (field 3); utime is field 14 and stime is field 15
            long utime = Long.parseLong(fields[11]);
            long stime = Long.parseLong(fields[12]);
            return utime + stime;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static int findThreadByName(String name) {
        File[] tasks = new File("/proc/self/task").listFiles();
        if (tasks == null) {
            return -1;
        }
        for (File task : tasks) {
            try (BufferedReader br = new BufferedReader(new FileReader(new File(task, "comm")))) {
                String comm = br.readLine();
                if (comm != null && comm.trim().equals(name)) {
                    return Integer.parseInt(task.getName());
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return -1;
    }

    /** Takes a sample. Call roughly once per second. */
    public void sample(long nowNs) {
        if (!available) {
            return;
        }

        long processTicks = readStatTicks("/proc/" + Process.myPid() + "/stat");
        if (processTicks < 0) {
            available = false;
            return;
        }

        if (videoRecvTid < 0) {
            videoRecvTid = findThreadByName(VIDEO_RECV_THREAD_NAME);
        }
        long threadTicks = videoRecvTid >= 0 ?
                readStatTicks("/proc/self/task/" + videoRecvTid + "/stat") : -1;
        if (threadTicks < 0) {
            // The thread may have exited (stream restart); look it up again next time
            videoRecvTid = -1;
        }

        if (lastSampleNs != 0) {
            double elapsedSec = (nowNs - lastSampleNs) / 1_000_000_000.0;
            if (elapsedSec > 0) {
                processPercent = 100.0 * (processTicks - lastProcessTicks) / clockTicksPerSec / elapsedSec;
                if (threadTicks >= 0 && lastThreadTicks >= 0) {
                    videoRecvPercent = 100.0 * (threadTicks - lastThreadTicks) / clockTicksPerSec / elapsedSec;
                }
                else {
                    videoRecvPercent = -1;
                }
            }
        }

        lastProcessTicks = processTicks;
        lastThreadTicks = threadTicks;
        lastSampleNs = nowNs;
    }

    /** @return process CPU usage in percent of one core, or -1 if unknown */
    public double getProcessPercent() {
        return processPercent;
    }

    /** @return VideoRecv thread CPU usage in percent of one core, or -1 if unknown */
    public double getVideoRecvPercent() {
        return videoRecvPercent;
    }
}
