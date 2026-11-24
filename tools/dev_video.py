#!/usr/bin/env python3
"""Stitches the frames a recorded dev run captured (run/screenshots/frame_<clip>_*.png) into /tmp/res/<clip>60.mp4 at 60 fps.
Record with: ./gradlew runClient -Pmbdev=animation -Pmbslow=8   (slow-motion run, every rendered frame captured, timed by game time)"""
import os, subprocess, sys

d = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "run", "screenshots")
out = "/tmp/res"
os.makedirs(out, exist_ok=True)
for clip in sys.argv[1:] or ["place", "dismantle"]:
    rows = [l.split() for l in open(os.path.join(d, f"frames_{clip}.txt"))]
    lines = []
    for i, (f, t) in enumerate(rows):
        dur = (float(rows[i + 1][1]) - float(t)) * 0.05 if i + 1 < len(rows) else 0.02
        lines.append(f"file '{os.path.abspath(d)}/{f}.png'\nduration {max(dur, 0.001):.5f}")
    lines.append(f"file '{os.path.abspath(d)}/{rows[-1][0]}.png'")
    lst = f"/tmp/{clip}_frames.txt"
    open(lst, "w").write("\n".join(lines))
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst, "-vf", "fps=60,scale=1280:-2",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20", f"{out}/{clip}60.mp4"], check=True)
