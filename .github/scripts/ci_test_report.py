#!/usr/bin/env python3
"""JUnit 결과를 CI 요약과 누락 비교용 목록으로 저장함."""
import argparse
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def summarize(root):
    suites, cases = [], []
    for file in sorted(Path(root).glob('**/build/test-results/test/TEST-*.xml')):
        module = file.relative_to(root).parts[:-4]
        module = ':'.join(module)
        xml = ET.parse(file).getroot()
        suites.append({'module': module, 'class': xml.get('name'), 'seconds': float(xml.get('time', 0)),
                       'tests': int(xml.get('tests', 0)), 'skipped': int(xml.get('skipped', 0))})
        for case in xml.findall('testcase'):
            cases.append(f'{module}:{case.get("classname")}#{case.get("name")}')
    if not suites:
        raise ValueError('JUnit reports missing')
    return {'tests': sum(s['tests'] for s in suites), 'skipped': sum(s['skipped'] for s in suites),
            'suites': sorted(suites, key=lambda s: s['seconds'], reverse=True), 'cases': sorted(cases)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', default='.')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    report = summarize(Path(args.root))
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    lines = [f'### Test report: {report["tests"]} tests, {report["skipped"]} skipped',
             '', '| Module / class | Suite seconds |', '| --- | ---: |']
    lines += [f'| {s["module"]} / {s["class"]} | {s["seconds"]:.3f} |' for s in report['suites'][:15]]
    text = '\n'.join(lines) + '\n'
    print(text)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
            stream.write(text)


if __name__ == '__main__':
    main()
