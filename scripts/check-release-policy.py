#!/usr/bin/env python3
"""正式发布必须有完整产物及成功的平台冒烟记录;预发布保留实验风险。"""
import json
import hashlib
import pathlib
import re
import sys

REQUIRED_JOBS = ("build-windows", "build-linux", "build-android", "smoke-linux-gui", "smoke-linux-gui-2604")

def check(root, version, commit):
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-build\d+)?", version):
        raise ValueError("目标版本格式不合法")
    status = json.loads((root / "RELEASE-STATUS.json").read_text(encoding="utf-8"))
    if status.get("version") != version:
        raise ValueError("平台检查记录与目标版本不一致")
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or status.get("commit") != commit:
        raise ValueError("平台检查记录与预发布标签提交不一致")
    for job in REQUIRED_JOBS:
        if status.get("jobs", {}).get(job, {}).get("result") != "success":
            raise ValueError("正式发布所需检查未成功: " + job)
    required = (f"LinPlayer-Windows-v{version}.zip", f"LinPlayer-Linux-v{version}.zip", "app-arm64-v8a-release.apk", "app-tv-armeabi-v7a-release.apk", "RELEASE-STATUS.json")
    sums = {}
    for line in (root / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  \*?(?:\./)?([^/\\]+)", line)
        if not match or match[2] in sums:
            raise ValueError("产物校验清单格式不合法或名称重复")
        sums[match[2]] = match[1]
    for name in required:
        if not (root / name).is_file() or (root / name).stat().st_size == 0:
            raise ValueError("缺少正式发布产物: " + name)
        digest = hashlib.sha256()
        with (root / name).open("rb") as asset:
            for chunk in iter(lambda: asset.read(1024 * 1024), b""):
                digest.update(chunk)
        if sums.get(name) != digest.hexdigest():
            raise ValueError("正式发布产物校验失败: " + name)

if __name__ == "__main__":
    try:
        check(pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3])
    except (OSError, ValueError, KeyError) as e:
        sys.exit("发布门禁未通过: " + str(e))
    print("正式发布产物及平台检查记录通过")
