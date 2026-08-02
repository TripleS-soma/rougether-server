---
name: routinevillage-asset-add
description: RoutineVillage Figma에 기존 파스텔 손그림 스타일과 맞는 가구·소품 이미지를 생성하고, 투명 PNG로 정리해 올바른 에셋 그룹에 삽입한다. "가구 이미지 추가", "RoutineVillage 소품 만들어줘", "Figma 에셋에 넣어줘" 요청에 사용한다.
metadata:
  version: 0.1.0
---

# RoutineVillage Asset Add

RoutineVillage의 기존 파스텔 손그림 스타일을 유지하면서 가구, 장식, 방 소품을 생성해 Figma 에셋 라이브러리에 추가한다.
이미지 생성만 하고 끝내지 않으며, 색상 추출부터 Figma 레이어 정리와 시각 검증까지 완료해야 한다.

## 기본 대상

- Figma file key: `nVm6ty82NBCMEI9jxPiBXA`
- Page: `prototype`
- Assets frame: `1:2`
- Furniture group: `14:23` (`가구`)

사용자가 다른 파일·페이지·그룹을 지정하면 사용자 지정값을 우선한다. 고정 node id는 Figma 구조 변경으로 달라질 수 있으므로,
쓰기 전에 node의 이름과 부모 관계를 확인한다.

## 필요한 기능

- 이미지 생성 도구
- Figma node 조회·수정 도구
- Figma Desktop에 PNG를 붙여넣을 수 있는 데스크톱 제어
- Python 3와 Pillow

Figma 쓰기나 데스크톱 붙여넣기를 사용할 수 없으면 투명 PNG 생성까지만 진행하고, Figma 삽입까지 완료했다고 보고하지 않는다.
중간 생성물은 저장소에 커밋하지 말고 `${TMPDIR:-/tmp}/routinevillage-asset-add/<asset-slug>/` 같은 임시 작업 폴더에 둔다.

작업을 시작할 때 에셋 이름에 맞는 slug와 레퍼런스 절대 경로를 정하고 임시 폴더를 만든다.

```bash
ASSET_SLUG="<asset-slug>"
REFERENCE_IMAGE="/absolute/path/to/reference.png"
WORK_DIR="${TMPDIR:-/tmp}/routinevillage-asset-add/$ASSET_SLUG"
mkdir -p "$WORK_DIR"
```

`python3 -c 'import PIL'`이 실패하면 저장소 안에 가상환경이나 의존성 파일을 임의로 만들지 말고,
현재 작업 환경의 Python에 Pillow를 준비한 뒤 계속한다.

## 작업 절차

### 1. 가장 가까운 기존 에셋 확보

- 새 에셋과 재질·형태가 가장 비슷한 기존 가구를 우선 선택한다.
- Figma Assets 프레임 또는 사용자가 제공한 이미지에서 해당 에셋을 캡처한다.
- 여러 에셋을 섞기보다 주 레퍼런스 하나와 보조 레퍼런스 한두 개만 사용한다.

### 2. 실제 팔레트 추출

레퍼런스 이미지에서 흰 배경과 투명 픽셀을 제외하고 색상을 추출한다.

```bash
python3 .claude/skills/routinevillage-asset-add/scripts/extract_palette.py \
  "$REFERENCE_IMAGE" \
  --colors 18 \
  --swatch "$WORK_DIR/palette.png"
```

출력된 RGB/hex 값을 생성 프롬프트에 직접 넣는다. 최소한 다음 역할의 색을 구분한다.

- 주 외곽선
- 보조 외곽선·그림자
- 나무·본체 면
- 밝은 하이라이트
- 포인트 색
- 화면·금속 같은 회갈색

예시 색상은 참고값일 뿐 정본이 아니다. 매 작업마다 현재 레퍼런스에서 다시 추출한다.

### 3. 엄격한 팔레트로 이미지 생성

다음 조건을 프롬프트에 명시한다.

- 하나의 깨끗한 2D 스티커형 오브젝트
- 따뜻한 갈색 외곽선, 크림색 면, 은은한 내부 명암
- 둥근 모서리와 약간 부드러운 손그림 선
- 실사 질감, 강한 광택, 복잡한 원근, 사진 같은 그림자 금지
- 사용자가 요청하지 않은 소품이나 장식 추가 금지
- 배경은 그림자·그라데이션·질감 없는 단색 `#00ff00`

팔레트 프롬프트는 다음 형식을 사용한다.

```text
Exact palette extracted from the current reference:
- main outline: RGB(...) / #......
- secondary outline or shadow: RGB(...) / #......
- main surface: RGB(...) / #......
- secondary surface: RGB(...) / #......
- highlight: RGB(...) / #......
- accent: RGB(...) / #......

Line treatment: contour stroke is visibly darker and thicker than the fill.
Use rounded line caps and corners, slight hand-drawn softness, and clean edges.
Background: perfectly flat solid #00ff00 chroma-key.
No shadow, gradient, texture, floor plane, or unrelated props.
```

### 4. 크로마키 제거

```bash
python3 .claude/skills/routinevillage-asset-add/scripts/remove_chroma_key.py \
  --input "$WORK_DIR/generated-chromakey.png" \
  --out "$WORK_DIR/transparent-raw.png" \
  --auto-key border \
  --transparent-threshold 12 \
  --opaque-threshold 220 \
  --despill
```

완전 투명 픽셀이 실제로 생겼는지 출력값을 확인한다. 녹색 테두리가 남으면 원본을 다시 생성하거나 threshold를 작은 폭으로
조정하되, 오브젝트 본체의 포인트 색까지 지우지 않는다.

### 5. Figma용 PNG 정리

```bash
python3 .claude/skills/routinevillage-asset-add/scripts/prepare_asset_png.py \
  "$WORK_DIR/transparent-raw.png" \
  --out "$WORK_DIR/asset-final.png" \
  --figma-out "$WORK_DIR/asset-figma.png" \
  --pad 80 \
  --max-dim 700
```

- 생성물이 레퍼런스보다 어두울 때만 `--brightness 1.03`처럼 작은 폭으로 보정한다.
- 종횡비를 유지한다.
- Figma에서 잘릴 가능성이 있으면 투명 여백을 줄이지 않는다.
- 네 모서리 alpha가 모두 0인지 확인한다.

### 6. Figma Desktop에 붙여넣기

- Figma Desktop을 `Design` mode로 둔다. Dev Mode에서는 편집·붙여넣기가 막힐 수 있다.
- 최종 `asset-figma.png`를 macOS 클립보드에 넣고 `편집 > 붙여넣기`로 삽입한다.
- Figma plugin 이미지 생성 API가 실패하면 같은 호출을 반복하지 말고 Desktop 붙여넣기로 전환한다.

필요하면 다음 AppleScript 형태를 사용한다.

```applescript
set imagePath to "/absolute/path/to/asset-figma.png"
set the clipboard to (read (POSIX file imagePath) as «class PNGf»)
tell application "Figma" to activate
delay 0.15
tell application "System Events"
  tell process "Figma"
    click menu item "붙여넣기" of menu 1 of menu bar item "편집" of menu bar 1
  end tell
end tell
```

### 7. 레이어 정규화

붙여넣은 뒤 Figma node 도구로 다음을 수행한다.

- 새 이미지 rectangle(`image 1`, `이미지` 등)을 찾는다.
- 이해하기 쉬운 한국어 에셋 이름으로 바꾼다.
- 가구이면 `가구`(`14:23`) 그룹으로 이동한다.
- 다른 종류이면 현재 파일 구조를 확인해 맞는 그룹으로 이동한다.
- 종횡비를 유지한 채 `x`, `y`, `width`, `height`를 정리한다.
- image fill의 `scaleMode`를 `FILL`이 아니라 `FIT`으로 설정한다.
- 생성·수정·삭제한 node id를 결과에 남긴다.

거절된 이전 생성물과 명백히 같은 변형만 삭제한다. 기존 사용자 에셋이나 원본 레이어는 삭제하지 않는다.
에셋 라이브러리에 들어갈 이미지를 페이지·프레임 루트에 방치하지 않는다.

### 8. 시각 검증

Assets frame `1:2`를 캡처해 새 에셋을 기존 가구 옆에서 확인한다.

- 외곽선이 기존 스타일보다 너무 검거나 얇지 않은가
- 면 색상이 너무 어둡거나 채도가 높지 않은가
- 가장자리와 그림이 잘리지 않았는가
- 배경이나 녹색 spill이 남지 않았는가
- 요청하지 않은 소품이 추가되지 않았는가
- 레이어가 올바른 그룹 안에 있는가

하나라도 어긋나면 최종 완료로 보고하지 말고 이미지 전처리 또는 생성을 보정한 뒤 다시 확인한다.

## 완료 보고

다음을 짧게 보고한다.

- 추가한 에셋 이름
- 사용한 주 레퍼런스와 핵심 팔레트
- 최종 Figma node id와 부모 그룹
- 시각 검증 결과
- Figma 삽입까지 완료했는지, 투명 PNG까지만 완료했는지

이 스킬의 완료 범위는 Figma 에셋 추가까지다. S3 업로드, asset key 확인, 카탈로그·기본 슬롯 등록은
`docs/claude/domains/assets.md`의 서버 등록 절차를 별도로 따른다.
