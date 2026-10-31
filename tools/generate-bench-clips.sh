#!/bin/sh
# Regenerates the synthetic test streams used by the on-device decoder benchmark
# (app/src/main/assets/bench). Requires ffmpeg built with libx264, libx265 and drawtext.
#
# The streams mimic what Sunshine sends for low latency streaming: no B-frames, a single
# reference frame, one slice per frame, a single IDR at the start, CBR with a one-frame VBV
# and BT.709 limited range VUI. Moonlight's SPS patching is applied on the device as usual.
# A large frame counter is drawn so smooth playback can be verified visually.
set -e
OUT="$(dirname "$0")/../app/src/main/assets/bench"
mkdir -p "$OUT"
FONT=${FONT:-/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf}
SRC="testsrc2=size=1920x1080:rate=60,noise=alls=12:allf=t+u,drawtext=fontfile=$FONT:text='%{frame_num}':start_number=0:x=40:y=40:fontsize=160:fontcolor=white:box=1:boxcolor=black@0.7,format=yuv420p"
FRAMES=120

ffmpeg -hide_banner -loglevel error -y -f lavfi -i "$SRC" -frames:v $FRAMES \
  -c:v libx264 -preset veryfast -tune zerolatency -profile:v high -level 4.2 \
  -b:v 15M -maxrate 15M -bufsize 250k \
  -x264-params "keyint=1000:min-keyint=1000:scenecut=0:ref=1:bframes=0:slices=1:sliced-threads=0:aud=0:nal-hrd=none" \
  -colorspace bt709 -color_primaries bt709 -color_trc bt709 -color_range tv \
  -f h264 "$OUT/h264_1080p60.h264"

ffmpeg -hide_banner -loglevel error -y -f lavfi -i "$SRC" -frames:v $FRAMES \
  -c:v libx265 -preset veryfast -tune zerolatency -profile:v main \
  -b:v 11M -maxrate 11M -bufsize 200k \
  -x265-params "keyint=1000:min-keyint=1000:scenecut=0:ref=1:bframes=0:rc-lookahead=0:frame-threads=1:log-level=error:colorprim=bt709:transfer=bt709:colormatrix=bt709:range=limited" \
  -f hevc "$OUT/hevc_1080p60.h265"

ls -l "$OUT"
