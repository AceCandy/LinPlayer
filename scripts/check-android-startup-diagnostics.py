#!/usr/bin/env python3
"""核查 APK 成品的诊断入口隔离,防止只测直接构造 Activity 的假绿。"""
import argparse
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk")
parser.add_argument("--apkanalyzer", required=True)
parser.add_argument("--normal", action="store_true")
args = parser.parse_args()
xml = subprocess.check_output([args.apkanalyzer, "manifest", "print", args.apk], text=True)
root = ET.fromstring(xml)
app = root.find("application")
android = "{http://schemas.android.com/apk/res/android}"
expected_app = "xyz.linplayer.app.LinPlayerApp"
assert app.get(android + "name") == expected_app, "Application 仍存在共享初始化路径"
launchers = []
for activity in app.findall("activity"):
    for intent in activity.findall("intent-filter"):
        actions = {x.get(android + "name") for x in intent.findall("action")}
        categories = {x.get(android + "name") for x in intent.findall("category")}
        if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in categories:
            launchers.append(activity.get(android + "name"))
expected_activity = "MainActivity" if args.normal else "StartupDiagnosticsActivity"
assert launchers == ["xyz.linplayer.app." + expected_activity], "桌面图标没有直达预期入口"
diagnostic = next(x for x in app.findall("activity") if x.get(android + "name") == "xyz.linplayer.app.StartupDiagnosticsActivity")
assert diagnostic.get(android + "enabled") == ("false" if args.normal else "true"), "诊断入口启用状态不符"
process = diagnostic.get(android + "process")
assert process in (":diagnostics", root.get("package") + ":diagnostics"), "诊断页未隔离到独立进程"
for provider in app.findall("provider"):
    enabled = provider.get(android + "enabled", "true")
    assert enabled == "true", "主进程的真实启动组件被关闭"
    assert provider.get(android + "process", root.get("package")) != process, "provider 进入诊断进程"
print("正常 APK 启动配置通过" if args.normal else "诊断 APK:独立进程入口、主进程真实初始化组件保留")
