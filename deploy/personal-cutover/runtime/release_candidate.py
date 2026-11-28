"""Resolve the independent service database only from the verified final restore receipts."""
import datetime
import json
from pathlib import Path
import re


def require(condition, detail):
    if not condition:
        raise ValueError(detail)


def read_receipt(path):
    require(path.is_file() and not path.is_symlink(), "Candidate receipt missing")
    return json.loads(path.read_text())


def verify(base, snapshot, digest, config_digest):
    require(re.fullmatch(r"[0-9]{8}-[0-9]{4}", snapshot), "Invalid snapshot")
    for value in (digest, config_digest):
        require(re.fullmatch(r"[a-f0-9]{64}", value), "Invalid envelope digest")
    slot = snapshot + "-" + digest[:12]
    original = base / slot
    clone = base / ("release-" + slot)
    config = base / "release-config" / config_digest
    data = clone / "copy/data"
    for folder in (original, clone, config, data):
        require(folder.is_dir() and folder.resolve() == base.resolve() / folder.relative_to(base),
                "Unresolved or redirected release directory")
    require((original / "envelope.sha256").read_text().strip() == digest, "Envelope mismatch")
    restored = read_receipt(original / "receipt.json")
    require(read_receipt(clone / "copy/receipt.json") == restored, "Original receipt mismatch")
    checked = read_receipt(clone / "verified.json")
    accounts = read_receipt(clone / "accounts.json")
    for proof, prefix in [(restored, "rougether-candidate-"), (checked, "rougether-sealed-copy-")]:
        require(proof.get("candidate") == prefix + slot, "Candidate identity mismatch")
        require(all(proof.get(k) is True for k in ("allTableCountsMatched", "readOnly", "flywayMatched"))
                and proof.get("network") == "none" and proof.get("orphanViolations") == 0,
                "Missing database restore checks")
    require(all(checked.get(k) is True for k in ("independentServerUUID", "zeroPublicPorts",
                "secureTransportRequired", "fsyncOnCommit", "binlogEnabled", "sourceDetached")),
            "Service clone was not independently verified")
    require(all(restored.get(k) == checked.get(k) and isinstance(checked.get(k), int)
                for k in ("tables", "rows", "foreignKeys", "routines")), "Service clone totals differ")
    source = checked.get("frozenSource")
    require(isinstance(source, dict) and source == restored.get("frozenSource"),
            "Final source freeze proof missing or inconsistent")
    require(source.get("sourceAccount") == "776158585524" and source.get("schema") == "rougether"
            and source.get("snapshot") == snapshot, "Final source identity mismatch")
    for key in ("sourceDumpSHA256", "objectInventorySHA256", "countDigest"):
        require(re.fullmatch(r"[a-f0-9]{64}", source.get(key, "")), "Final source digest invalid")
    require(datetime.datetime.fromisoformat(source["dumpEnded"]).utcoffset() is not None,
            "Missing source dump timestamp")
    expected = dict(snapshot=snapshot, digest=digest, config=config_digest, tlsLogins=2, network="none")
    require(all(accounts.get(k) == value for k, value in expected.items())
            and all(accounts.get(k) is True for k in ("checkedGrants", "globalReadOnly", "schemaReadOnly")),
            "Prepared accounts do not match this service clone and configuration")
    for name in ("ibdata1", "mysqld-auto.cnf"):
        require((data / name).is_file() and not (data / name).is_symlink(), "Incomplete MySQL data")
    return {"slot": slot, "source": source, "data": str(data), "config": str(config),
            "tables": checked["tables"], "rows": checked["rows"]}


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    for key in ("snapshot", "digest", "config_digest"):
        parser.add_argument(key)
    args = parser.parse_args()
    print(json.dumps(verify(Path("/var/lib/rougether-personal-cutover"), **vars(args))))
