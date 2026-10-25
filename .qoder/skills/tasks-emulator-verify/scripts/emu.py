#!/usr/bin/env python3
"""Drive the local Tasks.org debug build on the pixel6 emulator.

This wrapper avoids the Git Bash <-> Windows path traps that make raw adb
commands fail (device paths rewritten to D:/dev/git/sdcard/..., pull targets
not understood by adb.exe).  Every path this script passes to adb is either a
device path (POSIX) or a Windows path (what adb.exe expects), never a mix.

Examples:
    python emu.py status
    python emu.py install
    python emu.py launch
    python emu.py ui
    python emu.py tap "Continue without sync"
    python emu.py tap 94,2169
    python emu.py drag 500 1430 500 1180 2500
    python emu.py record-drag D:/Temp/drag.mp4 500 1430 500 1180 2500
"""

import argparse
import math
import os
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
DEFAULT_APK = REPO / "app/build/outputs/apk/generic/debug/app-generic-arm64-v8a-debug.apk"
PACKAGE = "org.tasks.debug"
REMOTE_UI = "/sdcard/qoder-ui.xml"
REMOTE_VIDEO = "/sdcard/qoder-record.mp4"


def find_adb():
    env = os.environ.get("ADB")
    if env:
        return env
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    names = ["adb.exe", "adb"] if os.name == "nt" else ["adb"]
    candidates = []
    if home:
        candidates += [Path(home) / "platform-tools" / n for n in names]
    candidates += [Path("D:/dev/android_sdk/platform-tools/adb.exe")]
    for candidate in candidates:
        if candidate.exists():
            return str(candidate)
    return shutil.which("adb") or "adb"


ADB = find_adb()


def run(args, serial, check=True, timeout=120, text=True):
    cmd = [ADB, "-s", serial] + list(args)
    result = subprocess.run(
        cmd, capture_output=True, text=text, timeout=timeout,
        encoding="utf-8" if text else None, errors="replace" if text else None,
    )
    if check and result.returncode != 0:
        err = result.stderr if text else result.stderr.decode("utf-8", "replace")
        raise SystemExit(f"failed: {' '.join(cmd)}\n{err.strip()}")
    return result


def shell(serial, *args, check=True, timeout=120):
    return run(["shell", *[str(a) for a in args]], serial, check=check, timeout=timeout)


def shell_out(serial, *args, timeout=120):
    result = run(["exec-out", *[str(a) for a in args]], serial, timeout=timeout)
    return result.stdout


def parse_bounds(value):
    # [x1,y1][x2,y2]
    try:
        left, rest = value.split("][")
        x1, y1 = (int(v) for v in left.strip("[]").split(","))
        x2, y2 = (int(v) for v in rest.strip("[]").split(","))
        return x1, y1, x2, y2
    except Exception:
        return None


def dump_nodes(serial, retries=4):
    for attempt in range(retries):
        shell(serial, "rm", "-f", REMOTE_UI, check=False)
        result = shell(serial, "uiautomator", "dump", REMOTE_UI, check=False)
        raw = shell_out(serial, "cat", REMOTE_UI, timeout=30)
        if raw and raw.lstrip().startswith("<?xml"):
            root = ET.fromstring(raw)
            nodes = []
            for node in root.iter("node"):
                bounds = parse_bounds(node.get("bounds", ""))
                if not bounds:
                    continue
                nodes.append({
                    "text": node.get("text", ""),
                    "desc": node.get("content-desc", ""),
                    "class": node.get("class", ""),
                    "package": node.get("package", ""),
                    "bounds": bounds,
                    "center": ((bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2),
                })
            return nodes
        time.sleep(1)
    raise SystemExit("uiautomator dump failed (null root); retry once the window is idle")


def visible_nodes(serial):
    return [n for n in dump_nodes(serial) if n["text"] or n["desc"]]


def find_nodes(serial, needle):
    needle = needle.lower()
    return [
        n for n in visible_nodes(serial)
        if needle == (n["text"] or "").lower()
        or needle == (n["desc"] or "").lower()
        or needle in (n["text"] or "").lower()
        or needle in (n["desc"] or "").lower()
    ]


def cmd_status(serial):
    print(run(["devices"], serial, check=False).stdout.strip())
    boot = shell(serial, "getprop", "sys.boot_completed", check=False).stdout.strip()
    print("boot_completed=" + boot)
    return 0 if boot == "1" else 1


def cmd_install(serial, apk):
    apk = Path(apk) if apk else DEFAULT_APK
    if not apk.exists():
        raise SystemExit(f"APK not found: {apk}\nBuild it first: ./build.sh debug")
    print(f"installing {apk}")
    print(run(["install", "-r", str(apk)], serial, timeout=300).stdout.strip())
    return 0


def cmd_launch(serial):
    shell(serial, "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1",
          check=False)
    print("launched " + PACKAGE)
    return 0


def cmd_stop(serial):
    shell(serial, "am", "force-stop", PACKAGE, check=False)
    print("stopped " + PACKAGE)
    return 0


def cmd_shot(serial, out):
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    data = run(["exec-out", "screencap", "-p"], serial, text=False).stdout
    out.write_bytes(data)
    print(f"{out} ({len(data)} bytes)")
    return 0


def cmd_ui(serial, show_empty=False):
    nodes = dump_nodes(serial) if show_empty else visible_nodes(serial)
    for node in nodes:
        text = node["text"].replace("\n", "\\n")
        desc = node["desc"].replace("\n", "\\n")
        print(f"text={text!r} desc={desc!r} bounds={list(node['bounds'])} "
              f"center={node['center']} class={node['class']}")
    if not nodes:
        print("(no nodes with text/content-desc)")


def resolve_target(serial, target):
    if "," in target:
        try:
            x, y = (int(v) for v in target.split(",", 1))
            return x, y, target
        except ValueError:
            pass
    nodes = find_nodes(serial, target)
    if not nodes:
        raise SystemExit(f"no node matching {target!r}")
    if len(nodes) > 1:
        print(f"{len(nodes)} matches for {target!r}; tapping the first", file=sys.stderr)
    return nodes[0]["center"][0], nodes[0]["center"][1], target


def cmd_tap(serial, target):
    x, y, _ = resolve_target(serial, target)
    shell(serial, "input", "tap", x, y)
    print(f"tap {x},{y}")
    return 0


def cmd_drag(serial, x1, y1, x2, y2, duration):
    shell(serial, "input", "draganddrop", x1, y1, x2, y2, duration)
    print(f"draganddrop ({x1},{y1}) -> ({x2},{y2}) {duration}ms")
    return 0


def cmd_swipe(serial, x1, y1, x2, y2, duration):
    shell(serial, "input", "swipe", x1, y1, x2, y2, duration)
    print(f"swipe ({x1},{y1}) -> ({x2},{y2}) {duration}ms")
    return 0


def cmd_longpress(serial, x, y, duration):
    shell(serial, "input", "swipe", x, y, x, y, duration)
    print(f"longpress ({x},{y}) {duration}ms")
    return 0


def cmd_text(serial, value):
    shell(serial, "input", "text", value.replace(" ", "%s"))
    print(f"text {value!r}")
    return 0


def cmd_key(serial, code):
    shell(serial, "input", "keyevent", code)
    print(f"keyevent {code}")
    return 0


def cmd_record_drag(serial, out, x1, y1, x2, y2, duration, lead, tail):
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    time_limit = max(8, int(math.ceil(lead + duration / 1000.0 + tail + 4)))
    shell(serial, "rm", "-f", REMOTE_VIDEO, check=False)
    recorder = subprocess.Popen(
        [ADB, "-s", serial, "shell", "screenrecord", "--bit-rate", "12000000",
         "--time-limit", str(time_limit), REMOTE_VIDEO],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        time.sleep(lead)
        shell(serial, "input", "draganddrop", x1, y1, x2, y2, duration)
        time.sleep(tail)
        # -INT lets screenrecord finalize the mp4 (root is available on this AVD).
        shell(serial, "pkill", "-INT", "screenrecord", check=False)
        try:
            recorder.wait(timeout=time_limit + 5)
        except subprocess.TimeoutExpired:
            recorder.kill()
    finally:
        if recorder.poll() is None:
            recorder.kill()
    run(["pull", REMOTE_VIDEO, str(out)], serial, timeout=120)
    print(f"{out} ({out.stat().st_size} bytes, time-limit {time_limit}s)")
    ffprobe = shutil.which("ffprobe")
    if ffprobe:
        probe = subprocess.run(
            [ffprobe, "-v", "error", "-select_streams", "v:0", "-count_frames",
             "-show_entries", "stream=nb_read_frames,duration",
             "-of", "default=noprint_wrappers=1", str(out)],
            capture_output=True, text=True,
        )
        print(probe.stdout.strip())
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("-s", "--serial", default="emulator-5554")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("status")
    p = sub.add_parser("install")
    p.add_argument("apk", nargs="?")
    sub.add_parser("launch")
    sub.add_parser("stop")
    p = sub.add_parser("shot")
    p.add_argument("out")
    p = sub.add_parser("ui")
    p.add_argument("--all", action="store_true", help="include empty nodes")
    p = sub.add_parser("find")
    p.add_argument("text")
    p = sub.add_parser("tap")
    p.add_argument("target", help="text/content-desc, or X,Y")
    p = sub.add_parser("drag")
    p.add_argument("x1", type=int); p.add_argument("y1", type=int)
    p.add_argument("x2", type=int); p.add_argument("y2", type=int)
    p.add_argument("duration", type=int, nargs="?", default=2500)
    p = sub.add_parser("swipe")
    p.add_argument("x1", type=int); p.add_argument("y1", type=int)
    p.add_argument("x2", type=int); p.add_argument("y2", type=int)
    p.add_argument("duration", type=int, nargs="?", default=400)
    p = sub.add_parser("longpress")
    p.add_argument("x", type=int); p.add_argument("y", type=int)
    p.add_argument("duration", type=int, nargs="?", default=2000)
    p = sub.add_parser("text")
    p.add_argument("value")
    p = sub.add_parser("key")
    p.add_argument("code")
    p = sub.add_parser("record-drag")
    p.add_argument("out")
    p.add_argument("x1", type=int); p.add_argument("y1", type=int)
    p.add_argument("x2", type=int); p.add_argument("y2", type=int)
    p.add_argument("duration", type=int, nargs="?", default=2500)
    p.add_argument("--lead", type=float, default=2.0)
    p.add_argument("--tail", type=float, default=1.0)

    args = parser.parse_args()
    s = args.serial
    if args.command == "status":
        return cmd_status(s)
    if args.command == "install":
        return cmd_install(s, args.apk)
    if args.command == "launch":
        return cmd_launch(s)
    if args.command == "stop":
        return cmd_stop(s)
    if args.command == "shot":
        return cmd_shot(s, args.out)
    if args.command == "ui":
        return cmd_ui(s, args.all)
    if args.command == "find":
        nodes = find_nodes(s, args.text)
        for node in nodes:
            print(f"text={node['text']!r} desc={node['desc']!r} "
                  f"bounds={list(node['bounds'])} center={node['center']}")
        return 0 if nodes else 2
    if args.command == "tap":
        return cmd_tap(s, args.target)
    if args.command == "drag":
        return cmd_drag(s, args.x1, args.y1, args.x2, args.y2, args.duration)
    if args.command == "swipe":
        return cmd_swipe(s, args.x1, args.y1, args.x2, args.y2, args.duration)
    if args.command == "longpress":
        return cmd_longpress(s, args.x, args.y, args.duration)
    if args.command == "text":
        return cmd_text(s, args.value)
    if args.command == "key":
        return cmd_key(s, args.code)
    if args.command == "record-drag":
        return cmd_record_drag(s, args.out, args.x1, args.y1, args.x2, args.y2,
                               args.duration, args.lead, args.tail)
    return 1


if __name__ == "__main__":
    sys.exit(main())
