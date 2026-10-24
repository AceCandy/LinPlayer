#!/usr/bin/env python3
"""检查倍率忙碌状态与正式按钮接线；不替代 Swift 编译或引擎运行测试。"""
from pathlib import Path
import re


def check(root: Path) -> None:
    controller = (root / "apps/tvos/Sources/PlaybackController.swift").read_text()
    view = (root / "apps/tvos/Sources/PlayerView.swift").read_text()
    fixture = (root / "apps/tvos/Tests/VisualCheck.swift").read_text()
    assert re.search(r"@Published\s+private var speedWork: Task<Void, Never>\?", controller), \
        "保存任务变化必须通知界面"
    assert re.search(r"var canSetSpeed: Bool\s*\{\s*!closing && !loading && !changingTrack && speedWork == nil && engine != nil\s*\}", controller), \
        "倍率入口必须覆盖保存中及播放器不可操作状态"
    method = controller.split("func setSpeed(", 1)[1].split("func setPictureMode(", 1)[0]
    assert re.search(r"-> Bool\s*\{\s*guard canSetSpeed, let engine,", method), "命令和按钮必须共用门控"
    assert "else { return false }" in method and re.search(r"return true\s*\}", method), "调用方必须获得接受回执"
    assert "defer { speedWork = nil }" in method, "成功和失败都必须解除忙碌状态"
    success = method.split("} catch is CancellationError", 1)[0]
    assert re.search(r'engine\.setRate\(value\)\s+if message == "倍率保存失败，请重试。" \{ message = nil \}', success), \
        "保存成功后只清除倍率错误，不能清除其他播放错误"
    assert success.count("message = nil") == 1, "不能额外无条件清空播放错误"
    assert re.search(r'onChange\(of: controller\.message\).*?if next != nil \{ showControls\(\) \} else \{ touch\(\) \}', view, re.S), \
        "错误解除后必须重启控制栏收起计时，覆盖保存超过五秒的情况"
    assert re.search(r'Button\("倍速"\).*?\.disabled\(!controller.canSetSpeed\)', view), "入口未禁用"
    assert re.search(r"if controller\.setSpeed\(rate\) \{ closePanel\(\) \}", view), "拒绝的操作不能关闭面板"
    assert re.search(r"if controller\.setSpeed\(rate\).*?\.disabled\(!controller.canSetSpeed\)", view, re.S), "选项未禁用"
    assert "canSetSpeed" in fixture and re.search(r"func setSpeed\(_ rate: Float\) -> Bool", fixture), "渲染夹具接口未同步"


if __name__ == "__main__":
    check(Path(__file__).resolve().parents[1])
    print("tvOS倍率静态接线检查通过（未验证Swift运行时）")
