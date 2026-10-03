#!/usr/bin/env python3
"""校验 Pages 子路径下生成页面的本站链接,避免跳到账号根站点。"""
import sys
from html.parser import HTMLParser
from pathlib import Path


class CheckPaths(HTMLParser):
    def handle_starttag(self, tag, attrs):
        for name, value in attrs:
            if name in ("src", "href") and value and value.startswith("/") and not value.startswith("//"):
                if not value.startswith("/LinPlayer/"):
                    raise ValueError(f"{tag} {name} 未包含项目子路径: {value}")


root = Path(sys.argv[1])
files = list(root.rglob("*.html"))
if not files or (root / "CNAME").exists():
    raise ValueError("没有生成页面或仍有继承的自定义域名")
for file in files:
    CheckPaths().feed(file.read_text(encoding="utf-8"))
print(f"Pages 子路径检查通过: {len(files)} 个页面")
