"""배포 ZIP의 vips 변환·수명을 실제 Linux ARM64에서 검증함. AWS/AI 호출 없음."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    archive = ROOT / 'furniture-lambda-preprocess/build/distributions/furniture-lambda-preprocess.zip'
    package = out / 'package'
    with zipfile.ZipFile(archive) as bundle:
        bundle.extractall(package)
    classes = out / 'probe'
    classes.mkdir(exist_ok=True)
    source = Path(__file__).with_name('NativeProbe.java')
    subprocess.run([str(args.java_home / 'bin/javac'), '-cp', str(package / 'lib/*'), '-d', str(classes), str(source)], check=True)
    result = subprocess.run(['docker', 'run', '--rm', '--platform', 'linux/arm64', '--network', 'none', '--memory', '512m', '--cpus', '2',
        '-e', 'VIPS_NATIVE_PATH=/app/native/libvips-cpp.so.8.18.6', '-e', 'VIPS_CONCURRENCY=1',
        '-v', f'{package}:/app:ro', '-v', f'{classes}:/probe:ro',
        '-v', f'{out}:/out', 'eclipse-temurin:25-jre', 'java', '-Xmx256m', '--enable-native-access=ALL-UNNAMED',
        '-cp', '/app/lib/*:/probe', 'NativeProbe', '/out'], text=True, capture_output=True)
    (out / 'console.log').write_text(result.stdout + result.stderr)
    (out / 'manifest.json').write_text(json.dumps({'zipSha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
        'probeSha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'exitCode': result.returncode,
        'nativeSha256': hashlib.sha256((package / 'native/libvips-cpp.so.8.18.6').read_bytes()).hexdigest()}, indent=2))
    print(result.stdout, end='')
    if result.returncode:
        print(result.stderr)
    result.check_returncode()


if __name__ == '__main__':
    main()
