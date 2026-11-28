"""Plan and resume reversible target S3 delete markers after the final writer freeze."""

import argparse
import contextlib
import fcntl
import hashlib
import json
import os
import subprocess
from pathlib import Path

import boto3

REGION = "ap-northeast-2"
SOURCE = ("rougether-isb", "776158585524", "rougether-assets-isb-776158585524")
TARGET = (
    "rougether-personal",
    "940482450790",
    "rougether-fallback-940482450790-assets",
)
# Created by the synthetic furniture success/refund rehearsal on November 29.
FIXTURE = "private/furniture-generation/161f4e19-5ad9-4ae6-9f9b-707abef1ce13/source.jpg"


def connect(spec):
    profile, account, bucket = spec
    creds = json.loads(
        subprocess.check_output(
            [
                "aws",
                "configure",
                "export-credentials",
                "--profile",
                profile,
                "--format",
                "process",
            ]
        )
    )
    session = boto3.Session(
        aws_access_key_id=creds["AccessKeyId"],
        aws_secret_access_key=creds["SecretAccessKey"],
        aws_session_token=creds.get("SessionToken"),
        region_name=REGION,
    )
    if session.client("sts").get_caller_identity()["Account"] != account:
        raise ValueError("Unexpected AWS account")
    s3 = session.client("s3")
    target = {"Bucket": bucket, "ExpectedBucketOwner": account}
    if s3.get_bucket_versioning(**target).get("Status") != "Enabled":
        raise ValueError("Versioning is required on both buckets")
    return s3, target


def inventory(connection, prefix=None):
    s3, args = connection
    result = {}
    for page in s3.get_paginator("list_object_versions").paginate(
        **args, **({"Prefix": prefix} if prefix else {})
    ):
        for kind in ("Versions", "DeleteMarkers"):
            for row in page.get(kind, []):
                key = row["Key"]
                if not row["IsLatest"] or (prefix is not None and key != prefix):
                    continue
                if key in result:
                    raise ValueError("S3 changed during enumeration")
                result[key] = {
                    "version": row["VersionId"],
                    "etag": row.get("ETag"),
                    "deleted": kind == "DeleteMarkers",
                }
    return result


def digest(value):
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def make_plan(source, target):
    rows = []
    missing = unknown = fixtures = 0
    for key, state in source.items():
        if not state["deleted"] and (key not in target or target[key]["deleted"]):
            missing += 1
    for key, state in sorted(target.items()):
        if state["deleted"]:
            continue
        previous = source.get(key)
        if previous and previous["deleted"]:
            rows.append(
                {
                    "key": key,
                    "sourceMarker": previous["version"],
                    "targetVersion": state["version"],
                    "etag": state["etag"],
                }
            )
        elif previous is None:
            if key == FIXTURE:
                fixtures += 1
            else:
                unknown += 1
    return {
        "sourceBucket": SOURCE[2],
        "targetBucket": TARGET[2],
        "sourceHash": digest(source),
        "targetHash": digest(target),
        "missing": missing,
        "unexplained": unknown,
        "synthetic": fixtures,
        "rows": rows,
    }


def append_receipt(path, data):
    with path.open("a") as ledger:
        ledger.write(json.dumps(data, separators=(",", ":")) + "\n")
        ledger.flush()
        os.fsync(ledger.fileno())


def check_target(plan, connection, completed):
    latest = inventory(connection)
    rows = {row["key"]: row for row in plan["rows"]}
    for key, receipt in completed.items():
        row = rows.get(key)
        if not row or receipt["prior"] != row["targetVersion"]:
            raise ValueError("Journal entry does not match the reviewed plan")
        if latest.get(key) != {
            "version": receipt["marker"],
            "etag": None,
            "deleted": True,
        }:
            raise ValueError("Target changed after recorded deletion")
        latest[key] = {
            "version": row["targetVersion"],
            "etag": row["etag"],
            "deleted": False,
        }
    if digest(latest) != plan["targetHash"]:
        raise ValueError(
            "Target inventory changed. Review versions and prepare a fresh plan"
        )


def apply(plan, src, dst, ledger):
    if (
        plan["sourceBucket"] != SOURCE[2]
        or plan["targetBucket"] != TARGET[2]
        or plan["missing"]
        or plan["unexplained"]
    ):
        raise ValueError("Resolve missing and unexplained objects before applying")
    if digest(inventory(src)) != plan["sourceHash"]:
        raise ValueError("Source changed. Repeat final synchronization and plan")
    done = (
        {row["key"]: row for row in map(json.loads, ledger.read_text().splitlines())}
        if ledger.exists()
        else {}
    )
    check_target(plan, dst, done)
    for row in plan["rows"]:
        key = row["key"]
        original = inventory(src, key).get(key)
        current = inventory(dst, key).get(key)
        if (
            not original
            or not original["deleted"]
            or original["version"] != row["sourceMarker"]
        ):
            raise ValueError("Source deletion changed during reconciliation")
        if key in done:
            if (
                not current
                or not current["deleted"]
                or current["version"] != done[key]["marker"]
            ):
                raise ValueError("Target changed after recorded deletion")
            continue
        if (
            not current
            or current["deleted"]
            or (current["version"], current["etag"])
            != (row["targetVersion"], row["etag"])
        ):
            raise ValueError(
                "Target changed. Review its versions and generate a fresh plan"
            )
        marker = dst[0].delete_object(**dst[1], Key=key, IfMatch=row["etag"])
        if not marker.get("DeleteMarker") or not marker.get("VersionId"):
            raise RuntimeError("Target did not return a versioned delete marker")
        receipt = {
            "key": key,
            "prior": row["targetVersion"],
            "marker": marker["VersionId"],
        }
        append_receipt(ledger, receipt)
        done[key] = receipt
        current = inventory(dst, key).get(key)
        if (
            not current
            or not current["deleted"]
            or current["version"] != marker["VersionId"]
        ):
            raise RuntimeError("Target marker readback failed")
    check_target(plan, dst, done)
    after = make_plan(inventory(src), inventory(dst))
    if (
        after["sourceHash"] != plan["sourceHash"]
        or after["rows"]
        or after["missing"]
        or after["unexplained"]
    ):
        raise RuntimeError("Final inventories differ. Keep the target closed")
    return len(plan["rows"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--apply-reviewed-digest", metavar="SHA256")
    args = parser.parse_args()
    os.umask(0o077)
    args.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    src, dst = connect(SOURCE), connect(TARGET)
    with (args.directory / "assets.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.apply_reviewed_digest:
            sha = args.apply_reviewed_digest
            if len(sha) != 64 or not all(c in "0123456789abcdef" for c in sha):
                raise ValueError("Expected a plan SHA-256")
            plan = json.loads((args.directory / (sha + ".json")).read_text())
            if digest(plan) != sha:
                raise ValueError("Plan content mismatch")
            print(
                json.dumps(
                    {
                        "reconciled": apply(
                            plan, src, dst, args.directory / (sha + ".jsonl")
                        )
                    }
                )
            )
        else:
            plan = make_plan(inventory(src), inventory(dst))
            sha = digest(plan)
            path = args.directory / (sha + ".json")
            with contextlib.suppress(FileExistsError):
                with path.open("x") as f:
                    json.dump(plan, f)
            if digest(json.loads(path.read_text())) != sha:
                raise ValueError("Stored plan differs")
            print(
                json.dumps(
                    {
                        "digest": sha,
                        "deleteMarkersPlanned": len(plan["rows"]),
                        "missing": plan["missing"],
                        "unexplained": plan["unexplained"],
                        "synthetic": plan["synthetic"],
                    }
                )
            )


if __name__ == "__main__":
    main()
