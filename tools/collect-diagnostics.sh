#!/bin/sh
# Collects the device information needed to analyse latency on an Android TV box
# (e.g. ZTE ZXV10 B866V2K). Requires adb with USB or network debugging enabled:
#   adb connect <tv-box-ip>:5555
# Usage: tools/collect-diagnostics.sh [output-dir]
# The app keeps one report folder per version: ull-sessions/<version>/ (all are pulled).
set -u
OUT=${1:-ull-diagnostics-$(date +%Y%m%d-%H%M%S)}
PKG=${PKG:-com.limelight.ull}
mkdir -p "$OUT"

run() {
  name=$1; shift
  echo "== $*" > "$OUT/$name.txt"
  adb shell "$@" >> "$OUT/$name.txt" 2>&1
}

run getprop getprop
run cpuinfo cat /proc/cpuinfo
run meminfo cat /proc/meminfo
run cpufreq 'for c in /sys/devices/system/cpu/cpu[0-9]*; do echo $c $(cat $c/cpufreq/cpuinfo_max_freq $c/cpufreq/scaling_governor 2>/dev/null); done'
run media_codecs 'for f in /vendor/etc/media_codecs*.xml /odm/etc/media_codecs*.xml /system/etc/media_codecs*.xml /apex/com.android.media.swcodec/etc/media_codecs*.xml; do [ -f "$f" ] && echo "### $f" && cat "$f"; done'
run media_profiles 'cat /vendor/etc/media_profiles*.xml 2>/dev/null'
run dumpsys_media_codec dumpsys media.codec
run dumpsys_display dumpsys display
run dumpsys_surfaceflinger dumpsys SurfaceFlinger
run dumpsys_hdmi dumpsys hdmi_control
run net 'ip addr; ip link; for i in /sys/class/net/*; do echo $i speed=$(cat $i/speed 2>/dev/null) duplex=$(cat $i/duplex 2>/dev/null); done'
run vdec_status 'cat /sys/class/vdec/vdec_status 2>/dev/null; cat /sys/class/vdec/core 2>/dev/null; cat /sys/class/codec_mm/codec_mm_dump 2>/dev/null'
run video_layer 'cat /sys/class/video/frame_rate /sys/class/video/vframe_states /sys/class/display/mode 2>/dev/null'

# Moonlight ULL reports (capability report, decoder benchmarks, session reports)
adb pull "/sdcard/Android/data/$PKG/files/ull-sessions" "$OUT/ull-sessions" >/dev/null 2>&1 || \
  echo "No ULL reports found for $PKG (run the capability report in Settings > Diagnostics first)" > "$OUT/ull-sessions-missing.txt"

# Recent logs from Moonlight and the media stack
adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>&1

echo "Diagnostics saved in $OUT"
