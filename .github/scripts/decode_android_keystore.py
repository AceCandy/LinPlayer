#!/usr/bin/env python3
import base64
import os
import re
import sys
from pathlib import Path


def _fail(message: str, *, raw_len: int | None = None, clean_len: int | None = None, pad: int | None = None) -> None:
    if raw_len is not None and clean_len is not None and pad is not None:
        sys.stderr.write(
            f"ANDROID_KEYSTORE_BASE64 normalization stats: raw_len={raw_len}, clean_len={clean_len}, pad={pad}\n"
        )
    sys.stderr.write(message.rstrip() + "\n")
    raise SystemExit(1)


def main() -> None:
    out_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("android/release.keystore")
    raw = os.environ.get("ANDROID_KEYSTORE_BASE64", "")
    raw_len = len(raw)
    if not raw:
        _fail("ANDROID_KEYSTORE_BASE64 is empty.")

    s = raw.strip()

    if len(s) >= 2 and s[0] == s[-1] and s[0] in ("'", '"'):
        s = s[1:-1]

    m = re.match(r"^data:.*?;base64,(.*)$", s, flags=re.DOTALL)
    if m:
        s = m.group(1)

    s = re.sub(r"-{5}BEGIN [^-]+-{5}", "", s)
    s = re.sub(r"-{5}END [^-]+-{5}", "", s)

    s = s.replace("\\n", "").replace("\\r", "")

    zero_width = {"\u200b", "\u200c", "\u200d", "\ufeff", "\u2060"}
    s = "".join(ch for ch in s if (not ch.isspace()) and (ch not in zero_width))

    s = s.replace("-", "+").replace("_", "/")

    clean_len = len(s)
    pad_needed = (-clean_len) % 4
    s += "=" * pad_needed

    if re.search(r"[^A-Za-z0-9+/=]", s):
        _fail(
            "ANDROID_KEYSTORE_BASE64 contains non-base64 characters after normalization. "
            "Re-generate the secret from the raw keystore bytes and paste the plain base64 string.",
            raw_len=raw_len,
            clean_len=clean_len,
            pad=pad_needed,
        )

    try:
        data = base64.b64decode(s, validate=True)
    except Exception as e:
        _fail(
            f"Failed to decode ANDROID_KEYSTORE_BASE64 ({type(e).__name__}: {e}).",
            raw_len=raw_len,
            clean_len=clean_len,
            pad=pad_needed,
        )

    if len(data) < 32:
        _fail("Decoded keystore is unexpectedly small; refusing to continue.")

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(data)
    sys.stdout.write(f"Wrote keystore: {out_path}\n")


if __name__ == "__main__":
    main()

