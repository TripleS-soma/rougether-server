"""Exercise final reconciliation without source or target cloud credentials."""

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import final_assets as assets


def live(version):
    return {"version": version, "etag": '"' + version + '"', "deleted": False}


def marker(version):
    return {"version": version, "etag": None, "deleted": True}


class Bucket:
    def __init__(self, entries):
        self.entries = dict(entries)
        self.deletes = []

    def get_paginator(self, method):
        assert method == "list_object_versions"
        return self

    def paginate(self, Prefix="", **kwargs):
        page = {"Versions": [], "DeleteMarkers": []}
        for key, state in self.entries.items():
            if key.startswith(Prefix):
                field = "DeleteMarkers" if state["deleted"] else "Versions"
                page[field].append(
                    dict(
                        Key=key,
                        VersionId=state["version"],
                        ETag=state["etag"],
                        IsLatest=True,
                    )
                )
        return [page]

    def delete_object(self, *, Key, IfMatch):
        assert self.entries[Key]["etag"] == IfMatch
        self.deletes.append(Key)
        version = "marker-" + Key
        self.entries[Key] = marker(version)
        return {"DeleteMarker": True, "VersionId": version}


class FinalAssetsTest(unittest.TestCase):
    def setUp(self):
        self.source = Bucket(
            {"first": marker("s1"), "second": marker("s2"), "shared": live("x")}
        )
        self.target = Bucket(
            {
                "first": live("a"),
                "second": live("b"),
                "shared": live("y"),
                assets.FIXTURE: live("f"),
            }
        )
        self.plan = assets.make_plan(self.source.entries, self.target.entries)
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.ledger = Path(directory.name) / "journal"

    def run_plan(self):
        return assets.apply(
            self.plan, (self.source, {}), (self.target, {}), self.ledger
        )

    def test_idempotence_and_fixture_preservation(self):
        self.assertEqual(self.run_plan(), 2)
        self.assertEqual(self.run_plan(), 2)
        self.assertEqual(self.target.deletes, ["first", "second"])
        self.assertEqual(self.source.deletes, [])
        self.assertEqual(self.target.entries[assets.FIXTURE], live("f"))

    def test_partial_completion_resumes(self):
        real_delete = self.target.delete_object

        def interrupt_second(**params):
            if params["Key"] == "second":
                raise TimeoutError()
            return real_delete(**params)

        with (
            patch.object(self.target, "delete_object", interrupt_second),
            self.assertRaises(TimeoutError),
        ):
            self.run_plan()
        self.assertEqual(self.target.deletes, ["first"])
        self.run_plan()
        self.assertEqual(self.target.deletes, ["first", "second"])

    def test_missing_journal_after_success_fails_closed(self):
        with (
            patch.object(assets, "append_receipt", side_effect=OSError),
            self.assertRaises(OSError),
        ):
            self.run_plan()
        with self.assertRaisesRegex(ValueError, "Target inventory changed"):
            self.run_plan()
        self.assertEqual(self.target.deletes, ["first"])

    def test_unrelated_target_overwrite_before_apply(self):
        self.target.entries["shared"] = live("new")
        with self.assertRaisesRegex(ValueError, "Target inventory changed"):
            self.run_plan()
        self.assertEqual(self.target.deletes, [])

    def test_source_recreation_before_apply(self):
        self.source.entries["first"] = live("restored")
        with self.assertRaisesRegex(ValueError, "Source changed"):
            self.run_plan()
        self.assertEqual(self.target.deletes, [])

    def test_missing_or_unknown_object(self):
        self.source.entries["new"] = live("source")
        self.target.entries["unknown"] = live("other")
        self.plan = assets.make_plan(self.source.entries, self.target.entries)
        self.assertEqual(
            (self.plan["missing"], self.plan["unexplained"], self.plan["synthetic"]),
            (1, 1, 1),
        )
        with self.assertRaisesRegex(ValueError, "Resolve missing"):
            self.run_plan()
        self.assertEqual(self.target.deletes, [])


if __name__ == "__main__":
    unittest.main()
