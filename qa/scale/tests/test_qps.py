from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import qps


class QpsTest(unittest.TestCase):
    def test_uses_real_interval(self):
        a = dict(
            monotonic_s=100,
            questions=10000,
            uptime_s=1000,
            since_flush_s=800,
            read_s=0.01,
            server_uuid="a",
        )
        b = dict(
            a, monotonic_s=105.25, questions=10021, uptime_s=1005, since_flush_s=805
        )
        self.assertEqual(qps.rate(a, b)["questions_per_second"], 4)
        for change in (
            dict(server_uuid="b"),
            dict(monotonic_s=float("nan")),
            dict(read_s=-3),
            dict(uptime_s=1),
            dict(since_flush_s=2),
            dict(questions=100),
            dict(monotonic_s=100),
        ):
            with self.subTest(change=change), self.assertRaises(ValueError):
                qps.rate(a, dict(b, **change))
