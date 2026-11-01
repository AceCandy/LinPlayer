#!/usr/bin/env python3
"""从固定输入生成本地链接参数，不写用户签名或凭据。"""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent


def generate(cache):
    specs = json.loads((ROOT / "apps/tvos/dependencies.lock.json").read_text())["mpvkit"]["binaries"]
    # 只链接字幕渲染闭包；不链接 libmpv，视频和音频继续由 Aether 负责。
    names = {"Libass", "Libfreetype", "Libfribidi", "Libharfbuzz", "Libunibreak"}
    paths = [ROOT / "build/tvos/core/lpcore.a"] + [cache / "mpv-input" / s["name"] / s["name"] for s in specs if s["name"] in names]
    if len(paths) != 6 or not all(p.is_file() for p in paths): raise ValueError("核心或字幕静态切片缺失")
    flags = ['"-Wl,-load_hidden,' + str(p) + '"' for p in paths]
    for name in ["AVFoundation", "AudioToolbox", "CoreAudio", "CoreVideo", "CoreFoundation", "CoreMedia", "Metal", "VideoToolbox", "CoreGraphics", "CoreText", "IOSurface", "Foundation", "Security", "OpenGLES", "QuartzCore"]:
        flags.extend(["-framework", name])
    flags.extend("-l" + n for n in ["bz2", "iconv", "expat", "resolv", "xml2", "z", "c++"])
    version = (ROOT / "VERSION").read_text().strip()
    return "OTHER_LDFLAGS = $(inherited) " + " ".join(flags) + "\nMARKETING_VERSION = " + version + "\n"


if __name__ == "__main__":
    output = Path(sys.argv[2]); output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(generate(Path(sys.argv[1]).resolve()))
