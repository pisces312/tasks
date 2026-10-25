#!/usr/bin/env python3
"""Turn a screenrecord of a drag into measurements.

Subcommands:
    extract  VIDEO OUTDIR [--fps N]
    sheet    OUTDIR OUT [--crop x0,y0,x1,y1] [--start N] [--end N] [--cols N]
    track    OUTDIR [OUTDIR ...] --text-box x0,y0,x1,y1 [options]

`extract` keeps every encoded frame (screenrecord is variable frame rate, so a
static screen yields only a few frames per second and a drag yields ~30).  Do
not resample to a fixed fps: it would duplicate frames and make the y-series
stair-stepped in fake ways.

`track` locates a text label across frames with cv2.matchTemplate: the label is
cropped from one reference frame (default frame 2, i.e. before the drag) and
then searched for inside a window around its original position.  The printed
table and the --csv output are absolute screen y coordinates of the label, so a
row that sinks shows up as a positive dy spike even though the finger keeps
moving up.

Requires ffmpeg on PATH plus opencv-python, numpy, pillow, matplotlib.
"""

import argparse
import csv
import os
import shutil
import subprocess
import sys
from pathlib import Path

FRAME_PREFIX = "f-"
FONT_CANDIDATES = [
    "C:/Windows/Fonts/arialbd.ttf",
    "C:/Windows/Fonts/arial.ttf",
    "DejaVuSans.ttf",
]


def die(message):
    raise SystemExit(message)


def frame_files(directory):
    files = [f for f in os.listdir(directory) if f.startswith(FRAME_PREFIX)]
    return sorted(files)


def parse_box(value, name="box"):
    try:
        x0, y0, x1, y1 = (int(v) for v in value.split(","))
    except Exception:
        die(f"--{name} wants x0,y0,x1,y1 (got {value!r})")
    if x1 <= x0 or y1 <= y0:
        die(f"--{name} has an empty rect: {value!r}")
    return x0, y0, x1, y1


def cmd_extract(args):
    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)
    for stale in frame_files(outdir):
        (outdir / stale).unlink()
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        die("ffmpeg not found on PATH")
    cmd = [ffmpeg, "-y", "-loglevel", "error", "-i", args.video]
    if args.fps:
        cmd += ["-vf", f"fps={args.fps}"]
    else:
        cmd += ["-fps_mode", "passthrough"]
    cmd += [str(outdir / (FRAME_PREFIX + "%04d.png"))]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        die(result.stderr.strip())
    count = len(frame_files(outdir))
    if count == 0:
        die("no frames extracted; was the recording empty?")
    print(f"{outdir}: {count} frames")
    return 0


def load_font(size):
    from PIL import ImageFont
    for candidate in FONT_CANDIDATES:
        try:
            return ImageFont.truetype(candidate, size)
        except Exception:
            continue
    return ImageFont.load_default()


def cmd_sheet(args):
    from PIL import Image, ImageDraw

    directory = Path(args.outdir)
    files = frame_files(directory)
    if not files:
        die(f"no frames in {directory}; run extract first")
    start = max(1, args.start)
    end = min(len(files), args.end or len(files))
    if end < start:
        die("--end is before --start")
    files = files[start - 1:end]
    crop = parse_box(args.crop, "crop") if args.crop else None
    cols = max(1, args.cols)
    tile_w, tile_h = (int(v) for v in args.tile.lower().split("x"))
    rows = (len(files) + cols - 1) // cols

    sheet = Image.new("RGB", (cols * tile_w, rows * tile_h), (18, 18, 18))
    draw = ImageDraw.Draw(sheet)
    font = load_font(18)
    for i, name in enumerate(files):
        image = Image.open(directory / name)
        if crop:
            image = image.crop(crop)
        image = image.resize((tile_w, tile_h))
        cx, cy = (i % cols) * tile_w, (i // cols) * tile_h
        sheet.paste(image, (cx, cy))
        label = str(start + i)
        draw.rectangle([cx, cy, cx + 8 * len(label) + 10, cy + 24], fill=(0, 0, 0))
        draw.text((cx + 4, cy + 2), label, font=font, fill=(255, 70, 70))
    sheet.save(args.out)
    print(f"{args.out} {sheet.size} frames {start}-{end} of {len(frame_files(directory))}")
    return 0


def track_series(directory, text_box, ref_frame, search_box):
    import cv2

    files = frame_files(directory)
    if not files:
        die(f"no frames in {directory}; run extract first")
    ref_index = min(max(ref_frame, 1), len(files)) - 1
    ref = cv2.imread(str(Path(directory) / files[ref_index]), cv2.IMREAD_GRAYSCALE)
    if ref is None:
        die(f"cannot read {files[ref_index]}")
    x0, y0, x1, y1 = text_box
    template = ref[y0:y1, x0:x1]
    if template.size == 0:
        die("--text-box is outside the frame")
    sx0, sy0, sx1, sy1 = search_box
    sx0 = max(0, sx0); sy0 = max(0, sy0)
    sx1 = min(ref.shape[1], sx1); sy1 = min(ref.shape[0], sy1)

    series = []
    for index, name in enumerate(files, start=1):
        image = cv2.imread(str(Path(directory) / name), cv2.IMREAD_GRAYSCALE)
        if image is None:
            continue
        roi = image[sy0:sy1, sx0:sx1]
        if roi.shape[0] < template.shape[0] or roi.shape[1] < template.shape[1]:
            series.append((index, None, None, 0.0))
            continue
        result = cv2.matchTemplate(roi, template, cv2.TM_CCOEFF_NORMED)
        _, score, _, location = cv2.minMaxLoc(result)
        series.append((index, sy0 + location[1], sx0 + location[0], score))
    return series


def fmt_delta(current, previous):
    if current is None or previous is None:
        return ""
    return f"{current - previous:+d}"


def cmd_track(args):
    text_box = parse_box(args.text_box, "text-box")
    search_box = parse_box(args.search, "search") if args.search else (
        max(0, text_box[0] - 150),
        max(0, text_box[1] - 400),
        text_box[2] + 150,
        text_box[3] + 400,
    )
    directions = args.outdirs
    if len(directions) > 1 and args.plot and len(args.label) not in (0, len(directions)):
        die("--label must be given once per directory (or not at all)")

    results = []
    for position, directory in enumerate(directions):
        series = track_series(directory, text_box, args.ref_frame, search_box)
        label = args.label[position] if args.label else Path(directory).name
        results.append((label, series))
        print(f"# {label} ({directory})")
        print("frame    y     x   score   dy")
        previous = None
        for index, y, x, score in series:
            if y is None:
                print(f"{index:5d}    -     -   {score:.3f}")
                continue
            print(f"{index:5d} {y:5d} {x:5d}   {score:.3f}   {fmt_delta(y, previous)}")
            previous = y

    if args.csv:
        with open(args.csv, "w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle)
            writer.writerow(["series", "frame", "y", "x", "score"])
            for label, series in results:
                for index, y, x, score in series:
                    writer.writerow([label, index, "" if y is None else y,
                                     "" if x is None else x, f"{score:.4f}"])
        print(f"csv {args.csv}")

    if args.plot:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt

        figure, axes = plt.subplots(figsize=(11, 5.2), dpi=150)
        colors = ["#1a73e8", "#d93025", "#188038", "#f9ab00"]
        for position, (label, series) in enumerate(results):
            start = None
            for index, y, _, _ in series:
                if y is not None and y != text_box[1]:
                    start = index
                    break
            points = [(i, y) for i, y, _, _ in series
                      if y is not None and (start is None or i >= start)]
            if not points:
                continue
            xs = [i - (start or 1) for i, _ in points]
            ys = [y for _, y in points]
            axes.plot(xs, ys, "o-", ms=3, color=colors[position % len(colors)], label=label)
            axes.axhline(text_box[1], color="#999", lw=0.8, ls="--")
        if args.invert_y:
            axes.invert_yaxis()
        axes.set_xlabel("frame since the label started moving")
        axes.set_ylabel("screen y of the label (px)")
        axes.set_title(args.title or "Tracked label y-position")
        axes.legend(loc="best", fontsize=9)
        axes.grid(alpha=0.25)
        figure.tight_layout()
        figure.savefig(args.plot)
        print(f"plot {args.plot}")
    return 0


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("extract")
    p.add_argument("video")
    p.add_argument("outdir")
    p.add_argument("--fps", type=float, help="resample instead of keeping encoded frames")
    p.set_defaults(func=cmd_extract)

    p = sub.add_parser("sheet")
    p.add_argument("outdir")
    p.add_argument("out")
    p.add_argument("--crop", help="x0,y0,x1,y1 in screen pixels")
    p.add_argument("--start", type=int, default=1)
    p.add_argument("--end", type=int, default=0)
    p.add_argument("--cols", type=int, default=6)
    p.add_argument("--tile", default="432x184")
    p.set_defaults(func=cmd_sheet)

    p = sub.add_parser("track")
    p.add_argument("outdirs", nargs="+")
    p.add_argument("--text-box", required=True,
                   help="x0,y0,x1,y1 of the label in the reference frame")
    p.add_argument("--search", help="x0,y0,x1,y1 search window; default text-box +/-150x400")
    p.add_argument("--ref-frame", type=int, default=2)
    p.add_argument("--label", action="append", default=[])
    p.add_argument("--csv")
    p.add_argument("--plot")
    p.add_argument("--title")
    p.add_argument("--invert-y", action="store_true",
                   help="screen coordinates grow downward; invert so up is up")
    p.set_defaults(func=cmd_track)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
