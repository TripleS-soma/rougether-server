#!/usr/bin/env python3
"""검증된 현재 실행의 가구 AI 패키지만 정식 배포하고 코드 해시를 확인한다."""
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit
from urllib.request import urlopen

from rotate_llm_key import aws
from furniture_alarms import configure as configure_alarms

FUNCTION = "rougether-dev-furniture-ai"
BUCKET = "rougether-furniture-deploy-776158585524"
PACKAGE = Path("bundle/furniture-ai")


class ReleaseError(Exception):
    pass


def sha256(path):
    with Path(path).open("rb") as stream:
        h = hashlib.sha256()
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(part)
    return h.hexdigest()


def identity():
    tree = subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], text=True).strip()
    return {"source_tree": tree, "run_id": int(os.environ["GITHUB_RUN_ID"]),
            "run_attempt": int(os.environ["GITHUB_RUN_ATTEMPT"])}


def prepare():
    target = Path("ci-jars/furniture-ai")
    target.mkdir(parents=True, exist_ok=True)
    archive = target / "furniture-lambda-ai.zip"
    shutil.copyfile("furniture-lambda-ai/build/distributions/furniture-lambda-ai.zip", archive)
    manifest = {"schema": 1, **identity(), "zip_sha256": sha256(archive)}
    (target / "manifest.json").write_text(json.dumps(manifest) + "\n")


def validated_package():
    archive = PACKAGE / "furniture-lambda-ai.zip"
    manifest = json.loads((PACKAGE / "manifest.json").read_text())
    if archive.is_symlink() or manifest != {"schema": 1, **identity(), "zip_sha256": sha256(archive)}:
        raise ReleaseError("AI package source/run/hash mismatch")
    return archive, manifest["zip_sha256"]


def upload(path, expected_hash):
    checksum = base64.b64encode(bytes.fromhex(expected_hash)).decode()
    key = "furniture-lambda/ai-" + expected_hash + ".zip"
    result = subprocess.run([
        "aws", "s3api", "put-object", "--bucket", BUCKET, "--key", key, "--body", str(path),
        "--server-side-encryption", "AES256", "--checksum-algorithm", "SHA256",
        "--checksum-sha256", checksum, "--output", "json", "--no-cli-pager",
    ], capture_output=True, text=True, timeout=180)
    if result.returncode:
        raise ReleaseError("AI package upload failed")
    response = json.loads(result.stdout)
    if response.get("ChecksumSHA256") != checksum:
        raise ReleaseError("AI uploaded package checksum mismatch")
    artifact = {"S3Bucket": BUCKET, "S3Key": key}
    if response.get("VersionId") not in (None, "null"):
        artifact["S3ObjectVersion"] = response["VersionId"]
    return artifact


def backup(current, directory):
    location = current["Code"]["Location"]
    url = urlsplit(location)
    if url.scheme != "https" or not url.hostname or not url.hostname.endswith(".amazonaws.com"):
        raise ReleaseError("Unexpected Lambda package source")
    target = Path(directory) / "previous.zip"
    with urlopen(location, timeout=60) as response, target.open("wb") as stream:
        os.chmod(target, 0o600)
        size = 0
        while part := response.read(1024 * 1024):
            size += len(part)
            if size > 250 * 1024 * 1024:
                raise ReleaseError("Previous AI package exceeds size limit")
            stream.write(part)
    checksum = sha256(target)
    if base64.b64encode(bytes.fromhex(checksum)).decode() != current["Configuration"]["CodeSha256"]:
        raise ReleaseError("Previous AI package checksum mismatch")
    return upload(target, checksum)


def configuration():
    return aws("lambda", "get-function-configuration", {"FunctionName": FUNCTION})


def wait_and_verify(expected):
    aws("lambda", "wait function-updated-v2", {"FunctionName": FUNCTION})
    result = configuration()
    if (result.get("State") != "Active" or result.get("LastUpdateStatus") != "Successful"
            or result.get("CodeSha256") != expected):
        raise ReleaseError("AI code verification failed")
    return result


def restore(previous, artifact, proposed_hash):
    current = configuration()
    if current.get("LastUpdateStatus") == "InProgress":
        try:
            aws("lambda", "wait function-updated-v2", {"FunctionName": FUNCTION})
        except Exception:
            pass
        current = configuration()
    if current.get("LastUpdateStatus") == "InProgress":
        raise ReleaseError("AI update still pending; rollback not safe")
    if current.get("CodeSha256") == previous["CodeSha256"]:
        return
    if current.get("CodeSha256") != proposed_hash:
        raise ReleaseError("Concurrent AI code change; rollback refused")
    aws("lambda", "update-function-code", {
        "FunctionName": FUNCTION, **artifact, "RevisionId": current["RevisionId"],
    })
    wait_and_verify(previous["CodeSha256"])


def deploy():
    archive, digest = validated_package()  # AWS 접근 전에 검증한다.
    configure_alarms()
    expected = base64.b64encode(bytes.fromhex(digest)).decode()
    current = aws("lambda", "get-function", {"FunctionName": FUNCTION})
    before = current["Configuration"]
    if before.get("State") != "Active" or before.get("LastUpdateStatus") != "Successful":
        raise ReleaseError("AI Lambda is not ready")
    if before["CodeSha256"] == expected:
        return {"state": "unchanged", "codeSha256": expected}
    with tempfile.TemporaryDirectory(prefix="furniture-ai-release-") as directory:
        previous_artifact = backup(current, directory)
        artifact = upload(archive, digest)
        try:
            aws("lambda", "update-function-code", {
                "FunctionName": FUNCTION, **artifact, "RevisionId": before["RevisionId"],
            })
            after = wait_and_verify(expected)
        except Exception as error:
            restore(before, previous_artifact, expected)
            raise ReleaseError("AI deployment failed; previous code preserved/restored") from error
    return {"state": "updated", "codeSha256": after["CodeSha256"],
            "previousCodeSha256": before["CodeSha256"], "artifact": artifact}


if __name__ == "__main__":
    try:
        if sys.argv[1:] == ["prepare"]:
            prepare()
        elif sys.argv[1:] == ["validate"]:
            validated_package()
        elif sys.argv[1:] == ["deploy"]:
            result = deploy()
            output = Path("build/deploy-health/furniture-ai.json")
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(result) + "\n")
            print(json.dumps(result))
        else:
            raise ReleaseError("Expected prepare, validate or deploy")
    except Exception as error:
        # URL/SDK 원문에는 서명된 다운로드 주소 등이 포함될 수 있다.
        print(str(error) if isinstance(error, ReleaseError) else type(error).__name__, file=sys.stderr)
        sys.exit(1)
