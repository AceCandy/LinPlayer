#!/usr/bin/env python3
"""获取固定 tvOS 输入；只准备依赖，不编译、签名或安装应用。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
LOCK = ROOT / "apps/tvos/dependencies.lock.json"


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for b in iter(lambda: f.read(1024 * 1024), b""): h.update(b)
    return h.hexdigest()


def tree_hash(root):
    entries = []
    for p in root.rglob("*"):
        if p.is_symlink(): value = hashlib.sha256(os.readlink(p).encode()).hexdigest()
        elif p.is_file(): value = digest(p)
        else: continue
        entries.append((p.relative_to(root).as_posix(), value))
    return hashlib.sha256("".join(name + "\0" + value + "\n" for name, value in sorted(entries)).encode()).hexdigest()


def extract(tar, target, members=None):
    for m in members if members is not None else tar.getmembers():
        p = (target / m.name).resolve()
        if not p.is_relative_to(target.resolve()) or m.isdev(): raise ValueError("非法归档路径")
        if m.issym() and not (p.parent / m.linkname).resolve().is_relative_to(target.resolve()): raise ValueError("非法符号链接")
        if m.islnk() and not (target / m.linkname).resolve().is_relative_to(target.resolve()): raise ValueError("非法硬链接")
        tar.extract(m, target)


def download(url, target):
    with urllib.request.urlopen(url, timeout=180) as response, target.open("wb") as out: shutil.copyfileobj(response, out)


def local_packages(folder):
    p = folder / "AetherEngine/Package.swift"
    lines = p.read_text().splitlines()
    for i, line in enumerate(lines):
        if line.strip().startswith('.package(url:'):
            name = re.search(r'https://github.com/[^/]+/([^"/]+)', line)[1].removesuffix('.git')
            lines[i] = '        .package(path: "../' + name + '"),'
    p.write_text('\n'.join(lines) + '\n')


def silence_engine(folder):
    # 固定版本没有关闭 OSLog 的公开开关；禁用中央日志出口，避免真实 URL 写入系统日志。
    p = folder / "AetherEngine/Sources/AetherEngine/Diagnostics/EngineLog.swift"
    text = p.read_text()
    signature = "    private static func deliver(_ line: String, category: Category, level: Level) {"
    start = text.index(signature); body = start + len(signature)
    end, depth = body, 1
    while depth:
        if text[end] == "{": depth += 1
        if text[end] == "}": depth -= 1
        end += 1
    p.write_text(text[:body] + "\n        // 宿主不输出包含媒体地址的引擎诊断。\n    }" + text[end:])


def preserve_notices(cache, lock):
    notices = cache / "Notices"
    if notices.exists(): shutil.rmtree(notices)
    notices.mkdir()
    for name in lock["packages"]:
        source = cache / "Dependencies" / name
        dest = notices / name; dest.mkdir()
        for filename in ["LICENSE", "LICENSE-APACHE", "NOTICE", "LICENSES"]:
            p = source / filename
            if p.is_dir(): shutil.copytree(p, dest / filename)
            elif p.is_file(): shutil.copy2(p, dest / filename)
    shutil.copy2(LOCK, notices / "dependencies.lock.json")
    (notices / "MODIFICATIONS.txt").write_text(
        "AetherEngine 7.39.0: EngineLog.deliver 的日志出口已禁用，以免媒体地址进入系统日志。\n"
        "源码重现：scripts/fetch-tvos.py；输入版本和来源见 dependencies.lock.json。\n"
        "这些文件保留固定源码包的声明；MPVKit 静态依赖的完整发行许可仍需在分发前核查。\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=ROOT / ".toolchain/tvos")
    parser.add_argument("--source-archive", type=Path, help="可选：已核查的离线 Dependencies 输入")
    parser.add_argument("--native-archive", type=Path, help="可选：包含 mpv-input 的离线输入")
    parser.add_argument("--verify", action="store_true", help="仅检查现有输入，不下载")
    args = parser.parse_args()
    cache = args.cache.resolve(); cache.mkdir(parents=True, exist_ok=True)
    lock = json.loads(LOCK.read_text())
    fingerprint = digest(LOCK) + ":" + digest(Path(__file__))
    stamp = cache / "verified.json"
    if stamp.exists():
        state = json.loads(stamp.read_text())
        if state.get("fingerprint") == fingerprint and all(tree_hash(cache / name) == value for name, value in state["trees"].items()):
            print("tvOS 固定输入校验通过。"); return
    if args.verify: raise SystemExit("tvOS 输入缺失或改变，请重新执行 fetch-tvos.py。")
    with tempfile.TemporaryDirectory(prefix="fetch-", dir=cache) as temporary:
        work = Path(temporary); deps = work / "Dependencies"; deps.mkdir()
        if args.source_archive:
            with tarfile.open(args.source_archive) as tar: extract(tar, work)
        else:
            for name, spec in lock["packages"].items():
                archive = work / (name + ".tar.gz"); download(spec["url"], archive)
                with tarfile.open(archive) as tar:
                    top = tar.getmembers()[0].name.split("/")[0]; extract(tar, work)
                (work / top).rename(deps / name); archive.unlink()
            local_packages(deps)
        for name, spec in lock["packages"].items():
            if tree_hash(deps / name) != spec["tree_sha256"]: raise ValueError("源码校验失败: " + name)
        # 所有依赖已锁定为本地路径；SwiftPM 会删除失效的远端 pins，准备阶段统一移除。
        (deps / "AetherEngine/Package.resolved").unlink(missing_ok=True)
        silence_engine(deps)
        native = work / "mpv-input"; native.mkdir()
        if args.native_archive:
            with tarfile.open(args.native_archive) as tar:
                extract(tar, work, [m for m in tar.getmembers() if m.name.startswith("mpv-input/")])
        else:
            for spec in lock["mpvkit"]["binaries"]:
                archive = work / (spec["name"] + ".zip"); download(spec["url"], archive)
                if digest(archive) != spec["sha256"]: raise ValueError("上游二进制校验失败: " + spec["name"])
                with zipfile.ZipFile(archive) as z:
                    info = next(p for p in z.namelist() if p.endswith(".xcframework/Info.plist"))
                    libraries = plistlib.loads(z.read(info))["AvailableLibraries"]
                    lib = next(x for x in libraries if x["SupportedPlatform"] == "tvos" and "SupportedPlatformVariant" not in x and "arm64" in x["SupportedArchitectures"])
                    prefix = info.rsplit("/", 1)[0] + "/" + lib["LibraryIdentifier"] + "/" + lib["LibraryPath"]
                    dest = native / spec["name"]; dest.mkdir()
                    if prefix in z.namelist(): (dest / spec["name"]).write_bytes(z.read(prefix))
                    else:
                        for member in z.namelist():
                            if member.startswith(prefix + "/") and not member.endswith("/"):
                                target = dest / member[len(prefix) + 1:]
                                if not target.resolve().is_relative_to(dest.resolve()): raise ValueError("非法静态库路径")
                                target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(z.read(member))
                archive.unlink()
        for spec in lock["mpvkit"]["binaries"]:
            if digest(native / spec["name"] / spec["name"]) != spec["slice_sha256"]: raise ValueError("真机切片校验失败: " + spec["name"])
        if len(list(native.iterdir())) != 28: raise ValueError("静态依赖必须为 28 个")
        for name in ["Dependencies", "mpv-input"]:
            dest = cache / name
            if dest.exists(): shutil.rmtree(dest)
            (work / name).rename(dest)
    preserve_notices(cache, lock)
    stamp.write_text(json.dumps({"fingerprint": fingerprint, "trees": {name: tree_hash(cache / name) for name in ["Dependencies", "mpv-input", "Notices"]}}, indent=2) + "\n")
    print("tvOS 固定源码与 28 个静态切片准备完成；未构建应用。")


if __name__ == "__main__": main()
