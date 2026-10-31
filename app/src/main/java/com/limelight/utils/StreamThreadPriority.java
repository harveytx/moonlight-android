package com.limelight.utils;

import android.os.Process;

import com.limelight.LimeLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Raises the scheduling priority of the latency-critical streaming threads.
 *
 * moonlight-common-c creates its threads with plain pthread_create(), so they inherit the
 * nice value of the Java thread that started the connection (0). The video receive thread
 * ("VideoRecv") is the one that reads every UDP packet and, with direct-submit decoders
 * (all Codec2 decoders), also queues the frame into MediaCodec. On a quad Cortex-A35 TV box
 * running Android TV services in the background, a nice 0 thread can be preempted for
 * several milliseconds. Apps are allowed to lower the nice value of their own threads
 * (Android's audio and display threads do exactly this), so we do the same here.
 *
 * Real-time scheduling (SCHED_FIFO) is not available to apps and is not used.
 */
public final class StreamThreadPriority {
    public static final int LEVEL_NORMAL = 0;   // Unchanged (original Moonlight behavior)
    public static final int LEVEL_HIGH = 1;     // THREAD_PRIORITY_URGENT_DISPLAY (-8)
    public static final int LEVEL_MAX = 2;      // THREAD_PRIORITY_AUDIO (-16)

    // Native threads that sit on the video critical path
    private static final String[] VIDEO_THREAD_NAMES = {"VideoRecv", "VideoDec"};

    private StreamThreadPriority() {
    }

    public static int levelToNice(int level) {
        switch (level) {
            case LEVEL_HIGH:
                return Process.THREAD_PRIORITY_URGENT_DISPLAY;
            case LEVEL_MAX:
                return Process.THREAD_PRIORITY_AUDIO;
            default:
                return Integer.MIN_VALUE;
        }
    }

    /**
     * Applies the given level to the calling thread.
     *
     * @return true if the priority was changed
     */
    public static boolean applyToCurrentThread(int level) {
        int nice = levelToNice(level);
        if (nice == Integer.MIN_VALUE) {
            return false;
        }
        try {
            Process.setThreadPriority(nice);
            return true;
        } catch (RuntimeException e) {
            LimeLog.warning("Unable to set thread priority: " + e);
            return false;
        }
    }

    /**
     * Applies the given level to the native video threads of moonlight-common-c.
     * Must be called after the video stream has started (e.g. from connectionStarted()).
     *
     * @return a human-readable description of what was changed, for diagnostics
     */
    public static String applyToNativeVideoThreads(int level) {
        int nice = levelToNice(level);
        if (nice == Integer.MIN_VALUE) {
            return "unchanged";
        }

        StringBuilder result = new StringBuilder();
        File[] tasks = new File("/proc/self/task").listFiles();
        if (tasks == null) {
            return "unavailable";
        }

        for (File task : tasks) {
            String comm;
            try (BufferedReader br = new BufferedReader(new FileReader(new File(task, "comm")))) {
                comm = br.readLine();
            } catch (IOException e) {
                continue;
            }
            if (comm == null) {
                continue;
            }
            comm = comm.trim();

            for (String name : VIDEO_THREAD_NAMES) {
                if (name.equals(comm)) {
                    try {
                        int tid = Integer.parseInt(task.getName());
                        Process.setThreadPriority(tid, nice);
                        if (result.length() > 0) {
                            result.append(", ");
                        }
                        result.append(name).append('=').append(Process.getThreadPriority(tid));
                    } catch (RuntimeException e) {
                        LimeLog.warning("Unable to set priority of " + name + ": " + e);
                    }
                }
            }
        }

        String desc = result.length() > 0 ? result.toString() : "no native video threads found";
        LimeLog.info("Native video thread priority: " + desc);
        return desc;
    }
}
