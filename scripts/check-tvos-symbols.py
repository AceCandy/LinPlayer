#!/usr/bin/env python3
"""检查最终可执行文件的静态符号隔离；不执行应用。"""
import re
import subprocess
import sys

symbols = subprocess.check_output(["xcrun", "nm", "-gU", sys.argv[1]], text=True)
if re.search(r"\s_(?:avcodec_|avformat_|avutil_|av_|mpv_)", symbols):
    raise SystemExit("核心静态媒体符号外泄，禁止交付。")
print("静态媒体符号隔离检查通过。")
