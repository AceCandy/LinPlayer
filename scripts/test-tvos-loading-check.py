#!/usr/bin/env python3
"""检查加载模块脚本的缓存行为；使用模拟编译器，不代替 Swift 编译。"""
import importlib.util
from pathlib import Path
import sys
import tempfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("loading_check", Path(__file__).with_name("build-tvos-loading-check.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
calls = []
compiler = [b"fixture compiler 1"]


def output(args, **kwargs):
    return "/fixture/" + args[2] if "--show-sdk-path" in args else compiler[0]


def compile_module(args, **kwargs):
    calls.append(args)
    Path(args[args.index("-o") + 1]).write_bytes(b"fixture")
    Path(args[args.index("-emit-module-path") + 1]).write_bytes(b"fixture")


with tempfile.TemporaryDirectory() as temporary:
    root = Path(temporary)
    source = root / ".toolchain/tvos/Dependencies/Lottie/Sources/Fixture.swift"
    source.parent.mkdir(parents=True)
    source.write_text("struct Fixture {}")
    destination = root / "output"
    with patch.object(module, "ROOT", root), patch("subprocess.check_output", output), patch("subprocess.run", compile_module):
        with patch.object(sys, "argv", ["check", "appletvsimulator", str(destination)]):
            module.main(); module.main()
            assert len(calls) == 1
            assert calls[0][calls[0].index("-target") + 1] == "arm64-apple-tvos18.0-simulator"
            compiler[0] = b"fixture compiler 2"
            module.main()
            assert len(calls) == 2
            source.write_text("struct Changed {}")
            module.main()
            assert len(calls) == 3
            (destination / "verified.json").write_text("{")
            module.main()
            assert len(calls) == 4, "损坏标记必须重建"
        with patch.object(sys, "argv", ["check", "appletvos", str(destination)]):
            module.main()
            assert len(calls) == 5
            assert calls[-1][calls[-1].index("-target") + 1] == "arm64-apple-tvos18.0"
        with patch.object(sys, "argv", ["check", "invalid", str(destination)]):
            try: module.main()
            except SystemExit: pass
            else: raise AssertionError("非法SDK未拒绝")
print("加载模块脚本缓存/变更重建/损坏恢复/SDK检查通过（模拟编译器）")
