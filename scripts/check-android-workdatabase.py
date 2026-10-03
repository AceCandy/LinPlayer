#!/usr/bin/env python3
"""检查 release APK 中 Room 反射创建 WorkManager 数据库所需的构造器。"""
import argparse
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk")
parser.add_argument("--apkanalyzer", required=True)
args = parser.parse_args()
result = subprocess.run([
    args.apkanalyzer, "dex", "code", "--class", "androidx.work.impl.WorkDatabase_Impl",
    "--method", "<init>()V", args.apk,
], text=True, capture_output=True)
assert result.returncode == 0, "APK 缺少 WorkDatabase_Impl 无参构造器,启动时反射创建会失败"
assert ".method public constructor <init>()V" in result.stdout, "数据库无参构造器不是 public"
print("WorkManager 数据库 public 无参构造器存在")
