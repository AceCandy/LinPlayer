#!/usr/bin/env python3
"""为类型检查/临时渲染编译固定Lottie源码；不生成正式应用。"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent


def main():
    sdk_name, destination = sys.argv[1:]
    if sdk_name not in ("appletvos", "appletvsimulator"):
        raise SystemExit("仅支持tvOS SDK")
    output = Path(destination)
    output.mkdir(parents=True, exist_ok=True)
    source = ROOT / ".toolchain/tvos/Dependencies/Lottie"
    files = sorted((source / "Sources").rglob("*.swift"))
    if not files:
        raise SystemExit("缺少固定Lottie源码，请先准备tvOS依赖")
    sdk = subprocess.check_output(["xcrun", "--sdk", sdk_name, "--show-sdk-path"], text=True).strip()
    compiler = subprocess.check_output(["xcrun", "swiftc", "--version"])
    fingerprint = hashlib.sha256(Path(__file__).read_bytes() + sdk.encode() + compiler +
        b"".join(f.relative_to(source).as_posix().encode() + b"\0" + f.read_bytes() for f in files)).hexdigest()
    stamp = output / "verified.json"
    library, module = output / "libLottie.a", output / "Lottie.swiftmodule"
    try:
        verified = json.loads(stamp.read_text()) == fingerprint
    except (FileNotFoundError, json.JSONDecodeError):
        verified = False
    if not (library.is_file() and module.is_file() and verified):
        target = "arm64-apple-tvos18.0" + ("-simulator" if sdk_name == "appletvsimulator" else "")
        subprocess.run(["xcrun", "swiftc", "-parse-as-library", "-swift-version", "5", "-module-name", "Lottie",
                        "-emit-library", "-static", "-emit-module", "-emit-module-path", str(module), "-o", str(library),
                        "-target", target, "-sdk", sdk, *map(str, files)], check=True)
        stamp.write_text(json.dumps(fingerprint) + "\n")


if __name__ == "__main__": main()
