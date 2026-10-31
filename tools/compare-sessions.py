#!/usr/bin/env python3
"""
Offline A/B comparison of Moonlight ULL session reports.

  tools/compare-sessions.py A.json B.json            # summary comparison
  tools/compare-sessions.py A.json B.json --frames   # also compare per-frame CSV logs

Session reports are written by the app (Settings > Ultra Low Latency > Advanced >
"Record session reports"); pull them with tools/collect-diagnostics.sh.

With --frames, the per-frame logs (enable "Per-frame CSV log") are compared with a
Mann-Whitney U test (normal approximation), so you can tell whether a difference in
a stage is real or just noise. Only the Python standard library is used.
"""
import csv
import json
import math
import os
import sys

STAGES = ["receive", "submit", "jni", "input_wait", "decode", "pacing", "display",
          "client", "client_display", "queue_delay", "host"]
COUNTERS = ["received_fps", "rendered_fps", "network_loss_percent", "render_drop_percent",
            "jitter_ms", "resyncs", "bitrate_mbps"]
CSV_COLUMNS = ["receive_ms", "submit_ms", "jni_ms", "input_wait_ms", "decode_ms", "pacing_ms",
               "client_ms", "queue_delay_ms", "host_ms"]


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def describe(r):
    s = r.get("settings", {})
    d = r.get("decoder", {})
    return "%s [%s] %s@%s %.1f Mbps %s" % (r.get("timestamp"), s.get("profile"), s.get("resolution"),
                                         s.get("fps"), s.get("bitrate_kbps", 0) / 1000.0, d.get("decoder"))


def fmt(v):
    return "n/a" if v is None else "%.2f" % v


def stage_value(r, stage, key):
    # Older reports (ULL.1/ULL.2) have no "jni" stage
    st = r.get("stages_ms", {}).get(stage)
    if not st or st.get("count", 0) == 0:
        return None
    return st.get(key)


def mann_whitney(a, b):
    """Two-sided Mann-Whitney U test, normal approximation. Returns p-value."""
    n1, n2 = len(a), len(b)
    if n1 < 20 or n2 < 20:
        return None
    combined = sorted([(v, 0) for v in a] + [(v, 1) for v in b])
    ranks = [0.0] * len(combined)
    i = 0
    tie_term = 0.0
    while i < len(combined):
        j = i
        while j + 1 < len(combined) and combined[j + 1][0] == combined[i][0]:
            j += 1
        rank = (i + j) / 2.0 + 1
        t = j - i + 1
        tie_term += t ** 3 - t
        for k in range(i, j + 1):
            ranks[k] = rank
        i = j + 1
    r1 = sum(rank for rank, (_, g) in zip(ranks, combined) if g == 0)
    u1 = r1 - n1 * (n1 + 1) / 2.0
    mu = n1 * n2 / 2.0
    n = n1 + n2
    sigma = math.sqrt(n1 * n2 / 12.0 * ((n + 1) - tie_term / (n * (n - 1))))
    if sigma == 0:
        return 1.0
    z = (u1 - mu) / sigma
    return math.erfc(abs(z) / math.sqrt(2))


def load_frames(json_path):
    csv_path = json_path[:-len(".json")] + "-frames.csv"
    if not os.path.exists(csv_path):
        return None
    cols = {c: [] for c in CSV_COLUMNS}
    with open(csv_path, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            for c in CSV_COLUMNS:
                if row.get(c):
                    cols[c].append(float(row[c]))
    return cols


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 2:
        print(__doc__)
        sys.exit(1)
    a, b = load(args[0]), load(args[1])
    print("A:", describe(a))
    print("B:", describe(b))
    print()
    print("%-28s %10s %10s %10s %8s" % ("stage (ms)", "A", "B", "B-A", "B-A %"))
    for stage in STAGES:
        for key in ("mean_ms", "p95_ms"):
            va, vb = stage_value(a, stage, key), stage_value(b, stage, key)
            delta = pct = "n/a"
            if va is not None and vb is not None:
                delta = "%+.2f" % (vb - va)
                pct = "%+.1f%%" % (100.0 * (vb - va) / va) if va else "n/a"
            print("%-28s %10s %10s %10s %8s" % (stage + " " + key[:-3], fmt(va), fmt(vb), delta, pct))
    print()
    for c in COUNTERS:
        va = a.get("counters", {}).get(c)
        vb = b.get("counters", {}).get(c)
        delta = "%+.2f" % (vb - va) if va is not None and vb is not None else "n/a"
        print("%-28s %10s %10s %10s" % (c, fmt(va), fmt(vb), delta))

    if "--frames" in sys.argv:
        fa, fb = load_frames(args[0]), load_frames(args[1])
        if fa is None or fb is None:
            print("\nPer-frame CSV logs not found for both sessions (enable 'Per-frame CSV log').")
            return
        print("\nPer-frame comparison (Mann-Whitney U, p < 0.01 = significant difference)")
        print("%-16s %8s %8s %10s %10s %10s" % ("column", "n A", "n B", "median A", "median B", "p-value"))
        for c in CSV_COLUMNS:
            xa, xb = sorted(fa[c]), sorted(fb[c])
            if not xa or not xb:
                continue
            p = mann_whitney(xa, xb)
            print("%-16s %8d %8d %10.3f %10.3f %10s" % (
                c, len(xa), len(xb), xa[len(xa) // 2], xb[len(xb) // 2],
                "n/a" if p is None else ("%.2g" % p) + (" *" if p < 0.01 else "")))


if __name__ == "__main__":
    main()
