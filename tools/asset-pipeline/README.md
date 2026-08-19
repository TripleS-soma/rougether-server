# Rougether Asset Foundry CLI

`assetctl`은 이미지 생성 자체가 아니라 생성 결과를 서비스 에셋 계약으로 고정하는 로컬 품질 게이트입니다.
중간 파일과 QA report는 `${TMPDIR:-/tmp}` 아래에 두고, manifest와 필요한 보호 마스크만 버전 관리합니다.

## 실행

```bash
uv run --python 3.12 --with 'pillow==12.3.*' \
  python tools/asset-pipeline/assetctl.py validate \
  /path/to/asset.manifest.json \
  --report /tmp/asset-foundry/report.json
```

종료 코드는 통과 `0`, 품질 게이트 실패 `2`, manifest 오류 `1`입니다. 생성된 report의 `checks` 배열은
관리자 `Asset Foundry > QA report JSON 반영`에 그대로 붙여 넣을 수 있습니다.

## 검증 범위

- 종류별 output asset key 명명 규칙
- 출력 파일 디코딩과 캔버스 고정
- 투명 테두리
- animated WebP 프레임 수와 무한 루프
- premultiplied-alpha 기준 86px/94px 움직임 가시성
- 보호 마스크 영역의 프레임 간 RGBA 불변
- 집 프레임의 고정 슬롯 alpha 0

S3 업로드, 기존 아이템 교체, seed 변경은 자동 QA와 사람 승인을 통과한 뒤 별도 단계에서 수행합니다.
