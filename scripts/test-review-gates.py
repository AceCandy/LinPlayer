#!/usr/bin/env python3
"""发布与安卓字段门禁的真实负例;临时夹具不改工作树。"""
import contextlib
import hashlib
import importlib.util
import io
import json
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent

def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

policy = load("check-release-policy")
fields = load("check-android-fields")

class ReviewGatesTest(unittest.TestCase):
    def test_release_rejects_failed_smoke_missing_asset_and_wrong_commit(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            version, commit = "2.0.0-build1", "a" * 40
            status = {"version": version, "commit": commit,
                      "jobs": {job: {"result": "success"} for job in policy.REQUIRED_JOBS}}
            files = [f"LinPlayer-{platform}-v{version}.zip" for platform in ("Windows", "Linux")]
            files += ["app-arm64-v8a-release.apk", "app-tv-armeabi-v7a-release.apk"]
            for name in files:
                (root / name).write_bytes(b"package")
            def save():
                (root / "RELEASE-STATUS.json").write_text(json.dumps(status), encoding="utf-8")
                names = files + ["RELEASE-STATUS.json"]
                (root / "SHA256SUMS.txt").write_text("".join(
                    hashlib.sha256((root / name).read_bytes()).hexdigest() + "  ./" + name + "\n"
                    for name in names), encoding="utf-8")
            save()
            policy.check(root, version, commit)
            status["jobs"]["smoke-linux-gui"]["result"] = "failure"
            save()
            with self.assertRaisesRegex(ValueError, "检查未成功"):
                policy.check(root, version, commit)
            status["jobs"]["smoke-linux-gui"]["result"] = "success"
            save()
            with self.assertRaisesRegex(ValueError, "标签提交"):
                policy.check(root, version, "b" * 40)
            (root / files[0]).write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "校验失败"):
                policy.check(root, version, commit)
            (root / files[0]).unlink()
            with self.assertRaisesRegex(ValueError, "缺少"):
                policy.check(root, version, commit)

    def test_emby_does_not_accept_download_fields(self):
        with tempfile.TemporaryDirectory() as folder:
            old_ui = fields.UI
            fields.UI = folder
            try:
                path = pathlib.Path(folder) / "Probe.kt"
                def run(command, field):
                    path.write_text('fun probe() {\n val r = app.call("' + command + '")\n r.obj().str("' + field + '")\n}\n', encoding="utf-8")
                    with contextlib.redirect_stdout(io.StringIO()):
                        return fields.main()
                self.assertEqual(0, run("emby.listCollections", "name"))
                self.assertEqual(1, run("emby.listCollections", "received_bytes"))
                self.assertEqual(0, run("download.list", "received_bytes"))
            finally:
                fields.UI = old_ui

if __name__ == "__main__":
    unittest.main()
