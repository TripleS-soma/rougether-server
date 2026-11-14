#!/usr/bin/env python3
"""격리된 localhost MySQL에서 동일 JVM/데이터로 기준판과 변경판을 교차 비교한다."""
import argparse
import hashlib
import json
import os
import platform
from pathlib import Path
import re
import statistics
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--baseline', type=Path, required=True)
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--repetitions', type=int, default=3)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)
env = {k: v for k, v in os.environ.items() if not k.startswith(('AWS_', 'DB_', 'FIREBASE_')) and k not in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')}
init = output / 'classpath.gradle'
init.write_text("gradle.projectsEvaluated { def admin = rootProject.project(':admin-api'); rootProject.tasks.register('memoryClasspath') { doLast { println(admin.sourceSets.main.runtimeClasspath.asPath) } } }\n")
variants = {}
for name, repo in [('before', args.baseline.resolve()), ('after', root)]:
    log = output / (name + '-build.log')
    with log.open('w') as stream:
        subprocess.run(['./gradlew', ':admin-api:bootJar', '--console=plain'], cwd=repo, env=env,
                       stdout=stream, stderr=subprocess.STDOUT, check=True)
    cp = subprocess.check_output(['./gradlew', '-q', '-I', str(init), 'memoryClasspath'],
                                 cwd=repo, env=env, text=True).strip().splitlines()[-1]
    classes = output / (name + '-classes')
    classes.mkdir(exist_ok=True)
    subprocess.run([str(args.java_home / 'bin/javac'), '-cp', cp, '-d', str(classes),
                    str(root / 'qa/admin-memory/AdminMemoryBenchmark.java')], check=True, env=env)
    variants[name] = str(classes) + os.pathsep + cp


def execute(name, destination):
    command = [str(args.java_home / 'bin/java'), '-Xmx384m', '-Duser.timezone=UTC', '-XX:+UseSerialGC',
               '-XX:ReservedCodeCacheSize=128m', '-XX:MaxMetaspaceSize=256m',
               '-XX:MaxDirectMemorySize=64m', '-cp', variants[name],
               'com.triples.rougether.adminapi.activity.service.AdminMemoryBenchmark', str(destination)]
    log = output / (str(destination).replace('/', '_')[-70:] + '.log')
    with log.open('w') as stream:
        result = subprocess.run(['/usr/bin/time', '-l'] + command, env=env,
                                stdout=stream, stderr=subprocess.STDOUT, timeout=240)
    if result.returncode:
        raise RuntimeError('Benchmark failed; inspect ' + str(log))
    rss = re.search(r'(\d+)\s+maximum resident set size', log.read_text())
    return int(rss[1]) if rss else None


execute('before', 'seed')
results = {'before': [], 'after': []}
expected = None
for sample in range(args.repetitions):
    # 먼저 실행하는 버전을 교대해 DB 캐시의 순서 편향을 줄인다.
    for name in (('before', 'after') if sample % 2 == 0 else ('after', 'before')):
        destination = output / (name + '-' + str(sample + 1))
        rss = execute(name, destination)
        payload = json.loads((destination / 'responses.json').read_text())
        canonical = json.dumps(payload, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
        if expected is None:
            expected = payload
        if payload != expected:
            raise AssertionError('Statistics changed: ' + str(destination / 'responses.json'))
        metrics = json.loads((destination / 'metrics.json').read_text())
        metrics['maximumProcessRssBytes'] = rss
        metrics['responseSha256'] = hashlib.sha256(canonical.encode()).hexdigest()
        results[name].append(metrics)
        print(name, sample + 1, json.dumps(metrics), flush=True)

summary = {'baselineRef': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
           'patchSha256': hashlib.sha256(subprocess.check_output(['git', 'diff'], cwd=root)).hexdigest(),
           'platform': platform.platform(), 'responseCases': len(expected),
           'javaVersion': subprocess.run([str(args.java_home / 'bin/java'), '-version'], capture_output=True, text=True).stderr.splitlines(),
           'jvmOptions': ['-Xmx384m', '-Duser.timezone=UTC', '-XX:+UseSerialGC', '-XX:ReservedCodeCacheSize=128m', '-XX:MaxMetaspaceSize=256m', '-XX:MaxDirectMemorySize=64m'],
           'scope': 'Local macOS JVM service/JPA workload against isolated MySQL; not production RSS.',
           'responsesEqual': True, 'runs': results, 'median': {}, 'validSamples': {}}
for name, runs in results.items():
    summary['median'][name] = {}
    summary['validSamples'][name] = {}
    for key in runs[0]:
        if key == 'responseSha256':
            continue
        values = [row[key] for row in runs if isinstance(row[key], (int, float))]
        summary['validSamples'][name][key] = len(values)
        summary['median'][name][key] = statistics.median(values) if values else None
(output / 'summary.json').write_text(json.dumps(summary, indent=2))
print(json.dumps(summary['median'], indent=2), flush=True)
