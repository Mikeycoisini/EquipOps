#!/usr/bin/env python3
"""只读检查双仓源码及迁移基线；不连接数据库，不读取进程凭据。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlsplit


def inventory(root, names):
    """按路径与原始字节生成稳定摘要，缺失文件或符号链接即失败。"""
    digest = hashlib.sha256()
    for name in sorted(names):
        path = root / name
        if path.is_symlink() or any(p.is_symlink() for p in path.parents if p != root):
            raise ValueError(f"符号链接不属于可核对的普通源码：{name}")
        if not path.is_file():
            raise ValueError(f"受跟踪文件缺失：{name}")
        content_hash = hashlib.sha256(path.read_bytes()).hexdigest()
        digest.update(f"{name}\0{content_hash}\n".encode())
    return {"files": len(names), "sha256": digest.hexdigest()}


def migrations(directory):
    """V1—V15必须存在，新增整数版本继续保持唯一且连续。"""
    rows = []
    seen = set()
    if not directory.is_dir():
        raise ValueError("迁移目录缺失")
    for path in sorted(directory.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"迁移存在符号链接：{path.name}")
        if path.is_dir():
            continue
        match = re.fullmatch(r"V([0-9]+)__([A-Za-z0-9_]+)\.sql", path.name)
        if not match:
            raise ValueError(f"迁移文件名不符合当前整数版本约定：{path.name}")
        version = int(match[1])
        if version in seen:
            raise ValueError(f"迁移版本重复：V{version}")
        seen.add(version)
        rows.append({"version": version, "file": path.relative_to(directory).as_posix(),
                     "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    if not seen or seen != set(range(1, max(15, max(seen)) + 1)):
        raise ValueError("迁移必须从V1连续且至少覆盖V1—V15")
    return sorted(rows, key=lambda row: row["version"])


def git(root, *args):
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True)
    if result.returncode:
        # 不打印远程错误原文，避免Git配置中的认证信息进入报告。
        raise ValueError(f"Git核对失败：{' '.join(args[:1])}")
    return result.stdout.decode("utf-8")


def repository(root, required):
    root = root.resolve()
    if Path(git(root, "rev-parse", "--show-toplevel").strip()).resolve() != root:
        raise ValueError("目标必须是独立仓库根目录")
    head = git(root, "rev-parse", "--verify", "HEAD^{commit}").strip()
    names = [n for n in git(root, "ls-files", "-z").split("\0") if n]
    baseline = {n for n in git(root, "ls-tree", "-r", "--name-only", "-z", "HEAD").split("\0") if n}
    removed = sorted(baseline - set(names))
    if removed:
        raise ValueError(f"基线文件已从索引移除，需单独审阅删除/重命名：{removed[0]}")
    for name in required:
        if name not in names:
            raise ValueError(f"必要入口未纳入Git基线：{name}")
    # 只返回主机和owner/repo，丢弃userinfo、query、fragment。
    remote = git(root, "remote", "get-url", "origin").strip()
    if "://" in remote:
        parsed = urlsplit(remote)
        identity = f"{parsed.hostname}/{parsed.path.lstrip('/')}"
    else:
        match = re.fullmatch(r"(?:[^@]+@)?([^:]+):(.+)", remote)
        if not match:
            raise ValueError("无法识别来源远程")
        remote_path = match[2].split("?", 1)[0].split("#", 1)[0]
        identity = f"{match[1]}/{remote_path}"
    identity = identity.removesuffix(".git")
    all_files = inventory(root, names)
    product = [n for n in names if n.startswith("src/") or n in required]
    return {"origin": identity, "head": head, "tracked": all_files,
            "product": inventory(root, product),
            "working_tree_entries": len([n for n in git(root, "status", "--porcelain=v1",
                                                       "--untracked-files=all").splitlines() if n])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--frontend", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = {
            "scope": "A0静态源码核对；不代表编译或数据库已执行迁移",
            "backend": repository(args.backend, ["pom.xml", "mvnw", ".mvn/wrapper/maven-wrapper.properties",
                "src/main/java/com/EquipOpsApplication.java", "src/main/resources/application.properties"]),
            "frontend": repository(args.frontend, ["package.json", "package-lock.json", "vite.config.ts",
                "index.html", "src/main.ts", "src/router/index.ts"]),
            "migrations": migrations(args.backend / "src/main/resources/db/migration"),
        }
    except (ValueError, OSError, UnicodeError) as error:
        print(f"A0核对失败：{error}", file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
