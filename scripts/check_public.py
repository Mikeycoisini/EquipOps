#!/usr/bin/env python3
"""发布候选文件的有限凭据检查；仅输出路径、行号及规则名。"""
from pathlib import Path
import re
import subprocess
import sys


PATTERNS = {
    "GitHub token": r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b",
    "AWS access key": r"\bAKIA[A-Z0-9]{16}\b",
    "private key": r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
    "embedded password default": r"\$\{(?:DB_PASSWORD|REDIS_PASSWORD):[^}\s]+\}",
}


def findings(name, content):
    result = []
    path = Path(name)
    if ((path.name == ".env" or path.name.startswith(".env.")) and path.name != ".env.example"
            or path.name in {"id_rsa", "id_ed25519"} or path.suffix in {".p12", ".pfx", ".dump"}):
        result.append({"path": name, "line": 0, "rule": "private runtime file"})
    for line, text in enumerate(content.splitlines(), 1):
        for rule, pattern in PATTERNS.items():
            if re.search(pattern, text):
                result.append({"path": name, "line": line, "rule": rule})
    return result


def main(roots):
    failed = False
    for value in roots:
        root = Path(value).resolve()
        paths = subprocess.check_output(["git", "-C", str(root), "ls-files", "--cached", "--others", "--exclude-standard", "-z"])
        count = 0
        for name in sorted(set(paths.decode().rstrip("\0").split("\0")) - {""}):
            path = root / name
            if path.is_symlink() or not path.is_file():
                raise ValueError("发布候选不是普通文件：" + name)
            content = path.read_bytes().decode("utf-8", errors="replace")
            for finding in findings(name, content):
                print(root.name, finding)
                failed = True
            count += 1
        print(f"{root.name}: checked {count} candidate files")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or [Path(__file__).resolve().parents[1]]))
