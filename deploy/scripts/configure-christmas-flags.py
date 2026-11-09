#!/usr/bin/env python3
"""기존 사용자 API 환경의 이벤트 플래그 두 개만 변경한다. 컨테이너는 재시작하지 않는다."""

import argparse
import fcntl
import inspect
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import time


def configure_flags(path, desired=None):
    if desired is not None and not isinstance(desired, bool):
        raise ValueError("Desired flag value must be boolean")
    path = Path(path)
    keys = (b"CHRISTMAS_EVENT_ENABLED", b"CHRISTMAS_COIN_BONUS_ENABLED")
    nofollow = getattr(os, "O_NOFOLLOW", 0)

    def regular_file(metadata, backup=False):
        mode = stat.S_IMODE(metadata.st_mode)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
            raise ValueError("Expected a single-link regular file")
        if mode & 0o7022:
            raise ValueError("Unsafe file permissions")
        if backup and mode & ~0o600:
            raise ValueError("Backup permissions must be private")

    def set_owner(fd, metadata):
        current = os.fstat(fd)
        if (current.st_uid, current.st_gid) != (metadata.st_uid, metadata.st_gid):
            os.fchown(fd, metadata.st_uid, metadata.st_gid)

    def signature(metadata):
        return tuple(getattr(metadata, field) for field in (
            "st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns", "st_mode", "st_uid", "st_gid"))

    fd = os.open(path, os.O_RDONLY | nofollow)
    with os.fdopen(fd, "rb") as source:
        fcntl.flock(source.fileno(), fcntl.LOCK_EX)
        metadata = os.fstat(source.fileno())
        regular_file(metadata)
        if os.geteuid() not in (0, metadata.st_uid):
            raise PermissionError("Caller must own the environment file")
        original = source.read()
        lines = original.splitlines(keepends=True)
        existing, positions = {}, {}
        newline = next((b"\r\n" if line.endswith(b"\r\n") else line[-1:]
                        for line in lines if line.endswith((b"\r", b"\n"))), b"\n")
        for key in keys:
            matches = [(index, line) for index, line in enumerate(lines)
                       if re.match(rb"^[ \t]*(?:export[ \t]+)?" + key + rb"[ \t]*=", line)]
            if len(matches) > 1:
                raise ValueError("Duplicate Christmas flag")
            if matches and not matches[0][1].startswith(key + b"="):
                raise ValueError("Noncanonical Christmas flag declaration")
            value = matches[0][1].rstrip(b"\r\n").split(b"=", 1)[1] if matches else b"false"
            if value not in (b"true", b"false"):
                raise ValueError("Invalid Christmas flag")
            existing[key] = value == b"true"
            positions[key] = matches[0][0] if matches else None
        # 누락 키는 Spring 기본값 false로 읽음. disable에서 누락 false를 굳이 기록하지 않음.
        changed = desired is not None and any(value != desired for value in existing.values())
        backup_created, backup = False, None
        if changed:
            updated_lines = list(lines)
            missing = []
            for key in keys:
                index = positions[key]
                if index is None:
                    missing.append(key)
                else:
                    old = lines[index]
                    ending = b"\r\n" if old.endswith(b"\r\n") else old[-1:] if old.endswith((b"\r", b"\n")) else b""
                    updated_lines[index] = key + b"=" + str(desired).lower().encode("ascii") + ending
            updated = b"".join(updated_lines)
            if missing:
                if updated and not updated.endswith((b"\r", b"\n")):
                    updated += newline
                updated += b"".join(key + b"=" + str(desired).lower().encode("ascii") + newline for key in missing)
            # text 모드로 읽지 않아 CRLF와 다른 비밀값의 원본 바이트를 보존함.
            backup = path.with_name(path.name + ".before-christmas")
            try:
                backup_fd = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL | nofollow, 0o600)
            except FileExistsError:
                with os.fdopen(os.open(backup, os.O_RDONLY | nofollow), "rb") as previous:
                    old_metadata = os.fstat(previous.fileno())
                    regular_file(old_metadata, backup=True)
                    if (old_metadata.st_uid, old_metadata.st_gid) != (metadata.st_uid, metadata.st_gid):
                        raise ValueError("Backup owner mismatch")
                    identical = previous.read() == original
                if identical:
                    backup_fd = None
                else:
                    # 과거 백업은 덮지 않고 같은 디렉터리에 새0600 파일로 보관함.
                    backup_fd, backup_name = tempfile.mkstemp(prefix=path.name + ".before-christmas-", dir=path.parent)
                    backup = Path(backup_name)
            if backup_fd is not None:
                with os.fdopen(backup_fd, "wb") as handle:
                    set_owner(handle.fileno(), metadata)
                    os.fchmod(handle.fileno(), 0o600)
                    handle.write(original)
                    handle.flush()
                    os.fsync(handle.fileno())
                backup_created = True
            pending_fd, pending = tempfile.mkstemp(prefix=".christmas-env-", dir=path.parent)
            try:
                with os.fdopen(pending_fd, "wb") as handle:
                    set_owner(handle.fileno(), metadata)
                    handle.write(updated)
                    handle.flush()
                    os.fchmod(handle.fileno(), stat.S_IMODE(metadata.st_mode))
                    os.fsync(handle.fileno())
                current = os.stat(path, follow_symlinks=False)
                regular_file(current)
                if signature(current) != signature(metadata):
                    raise ValueError("Environment changed concurrently")
                os.replace(pending, path)
            finally:
                if os.path.exists(pending):
                    os.unlink(pending)
        return {
            "changed": changed,
            "flags": {key.decode("ascii"): desired if desired is not None else value for key, value in existing.items()},
            "flagsExplicitlyPresentBefore": {key.decode("ascii"): positions[key] is not None for key in keys},
            "backupCreated": backup_created,
            "backupFile": str(backup) if backup is not None else None,
            "restartPerformed": False,
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", default="rougether-isb")
    parser.add_argument("--region", default="ap-northeast-2")
    parser.add_argument("--instance-id", required=True)
    parser.add_argument("--mode", choices=("check", "enable", "disable"), default="check")
    args = parser.parse_args()
    desired = {"check": None, "enable": True, "disable": False}[args.mode]
    remote = (
        "import os,json,stat,tempfile,time,re,fcntl\nfrom pathlib import Path\n"
        + inspect.getsource(configure_flags)
        + "\nif os.geteuid() != 0: raise PermissionError('SSM root required')\n"
        + "print(json.dumps(configure_flags('/etc/rougether/user-api.env', "
        + repr(desired)
        + ")))\n"
    )

    def aws(*command):
        return json.loads(subprocess.check_output(
            ["aws", "--profile", args.profile, "--region", args.region, *command, "--output", "json"],
            stderr=subprocess.PIPE, text=True,
        ))

    # 실행 중인 기존 dev API 호스트만 대상으로 제한함.
    instances = aws("ec2", "describe-instances", "--instance-ids", args.instance_id)
    rows = [row for group in instances["Reservations"] for row in group["Instances"]]
    if len(rows) != 1 or rows[0]["State"]["Name"] != "running" or not any(
        tag == {"Key": "Name", "Value": "rougether-dev-app"} for tag in rows[0].get("Tags", [])
    ):
        raise ValueError("Unexpected API host")
    command = aws("ssm", "send-command", "--instance-ids", args.instance_id,
                  "--document-name", "AWS-RunShellScript", "--parameters",
                  json.dumps({"commands": ["python3 - <<'PY'\n" + remote + "PY"], "executionTimeout": ["60"]}))
    command_id = command["Command"]["CommandId"]
    for _ in range(120):
        try:
            result = aws("ssm", "get-command-invocation", "--command-id", command_id,
                         "--instance-id", args.instance_id)
        except subprocess.CalledProcessError:
            time.sleep(1)
            continue
        if result["Status"] == "Success":
            print(json.dumps(json.loads(result["StandardOutputContent"]), ensure_ascii=False))
            return
        if result["Status"] in ("Failed", "TimedOut", "Cancelled"):
            raise RuntimeError("Remote flag configuration failed; inspect the SSM command")
        time.sleep(1)
    raise RuntimeError("SSM command pending; inspect before retrying")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # AWS 응답/환경 파일에는 비밀값이 있을 수 있어 오류 본문을 전달하지 않는다.
        raise SystemExit("Christmas flag operation failed: " + type(error).__name__) from None
