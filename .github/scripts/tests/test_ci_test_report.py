import importlib.util
from pathlib import Path
import tempfile
import unittest

p = Path(__file__).resolve().parents[1] / 'ci_test_report.py'
spec = importlib.util.spec_from_file_location('ci_test_report', p)
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class ReportTest(unittest.TestCase):
    def test_preserves_module_and_nested_parameterized_test_identities(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for module, name in [('infra/llm', 'AdapterTest'), ('user-api', 'FlowTest$Nested')]:
                out = root / module / 'build/test-results/test'; out.mkdir(parents=True)
                (out / f'TEST-{name}.xml').write_text(
                    f'<testsuite name="{name}" tests="2" skipped="1" time="1.5">'
                    f'<testcase classname="{name}" name="case[1]"/>'
                    f'<testcase classname="{name}" name="case[2]"><skipped/></testcase></testsuite>')
            data = report.summarize(root)
            self.assertEqual(data['tests'], 4)
            self.assertEqual(data['skipped'], 2)
            self.assertIn('infra:llm:AdapterTest#case[1]', data['cases'])
            self.assertIn('user-api:FlowTest$Nested#case[2]', data['cases'])
            self.assertEqual(len(set(data['cases'])), 4)

    def test_missing_reports_fail_instead_of_appearing_as_zero_tests(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError): report.summarize(Path(directory))


if __name__ == '__main__': unittest.main()
