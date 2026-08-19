"""Rougether 어드민 에셋 MCP 서버.

AI 에이전트(Claude Code, Codex CLI)가 이미지를 생성한 뒤 바로 dev 환경에
등록할 수 있게 하는 stdio MCP 서버다. 노출 툴:

- upload_asset:        로컬 이미지 파일을 S3 에 사람이 읽는 커스텀 key 로 업로드
                       (admin-api 화면/API도 커스텀 key를 지원하며, MCP는 자동화와
                        명시적 overwrite가 필요한 운영 작업에 사용한다)
- asset_exists:        S3 에 해당 key 가 이미 있는지 확인
- import_catalog:      테마/캐릭터/아이템 카탈로그 멱등 적재 (POST /admin/catalog/import)
- import_character_accessory_catalog:
                       캐릭터 악세사리 카탈로그·렌더 프로필을 원자적으로 멱등 적재
- import_default_slots: positioned 가구의 기본 슬롯 멱등 적재 (POST /admin/items/slots/import)
- list_item_slots:     positioned 아이템·슬롯 현황 조회 (GET /admin/items/slots)
- import_character_accessory_render_profiles:
                       캐릭터별 악세사리 합성 위치 멱등 적재
- list_character_accessory_render_profiles:
                       캐릭터별 악세사리 합성 위치 조회
- create_asset_pipeline_job / submit_asset_pipeline_qa / advance_asset_pipeline_job:
                       Asset Foundry manifest·QA·승인 단계 연결 (실제 배포는 수행하지 않음)
- list_asset_pipeline_jobs:
                       Asset Foundry 관제 보드 작업 조회

실행 (Python >= 3.10 필요, uv 가 의존성을 처리):

    uv run --python 3.12 --with 'mcp==1.28.*' --with 'requests==2.34.*' --with 'boto3==1.43.*' \
        --with 'pillow==12.3.*' \
        python tools/admin-asset-mcp/server.py

MCP 클라이언트에는 개인 로컬 설정으로만 등록한다. 저장소 설정이 현재 체크아웃의 코드를
AWS·어드민 권한으로 자동 실행하지 않도록 repo 공용 MCP 설정은 두지 않는다. dev 어드민
(원격 http)을 쓰려면 개인 설정에서만 ADMIN_BASE_URL 과 ADMIN_ALLOW_HTTP=1 을 넣는다.

환경변수:

- ADMIN_BASE_URL   (기본 http://localhost:8081. 원격 http URL 은 비밀번호가 평문 전송되므로
                    ADMIN_ALLOW_HTTP=1 을 함께 명시해야만 허용 — dev 어드민이 TLS 없는 동안의 의식적 opt-in)
- ADMIN_ALLOW_HTTP (원격 http 허용 opt-in. localhost/https 에는 불필요)
- ADMIN_USERNAME   (기본 admin)
- ADMIN_PASSWORD   (미설정 시 SSM /rougether-dev/admin/seed-password 에서 조회)
- ASSET_BUCKET     (기본 rougether-assets)
- AWS_REGION       (기본 ap-northeast-2)
- ASSET_PUBLIC_BASE_URL (업로드 결과에 표시할 CloudFront/CDN base URL. 미설정 시 S3 URL)
- ASSET_ALLOWED_ROOTS (업로드를 허용할 로컬 디렉터리 목록. OS path 구분자로 연결하며,
                       기본값은 MCP 프로세스의 현재 작업 디렉터리)

안전장치:

- upload_asset 은 같은 key 가 이미 있으면 overwrite=True 를 명시하지 않는 한 거부한다.
- asset key 는 kind prefix(characters/categories/themes/items/house)와
  확장자(png/jpg/jpeg/webp)를 검증한다.
- 버킷은 환경변수로만 바꿀 수 있고 툴 인자로는 받지 않는다(오타로 다른 버킷에 쓰는 사고 방지).
"""

from __future__ import annotations

import mimetypes
import os
import re
import warnings
from io import BytesIO
from pathlib import Path
from typing import Any

import boto3
import requests
from botocore.exceptions import ClientError
from mcp.server.fastmcp import FastMCP
from PIL import Image, UnidentifiedImageError

ADMIN_BASE_URL = os.environ.get("ADMIN_BASE_URL", "http://localhost:8081").rstrip("/")
ADMIN_USERNAME = os.environ.get("ADMIN_USERNAME", "admin")


def _require_transport_safety() -> None:
    # 원격 http 는 비밀번호 평문 전송 — dev 어드민에 TLS 가 없는 동안 명시적 opt-in 만 허용
    from urllib.parse import urlparse
    parsed = urlparse(ADMIN_BASE_URL)
    is_local = parsed.hostname in ("localhost", "127.0.0.1", "::1")
    if parsed.scheme == "http" and not is_local and os.environ.get("ADMIN_ALLOW_HTTP") != "1":
        raise RuntimeError(
            f"원격 http URL({ADMIN_BASE_URL})은 어드민 비밀번호가 평문 전송됩니다. "
            "의도한 것이면 ADMIN_ALLOW_HTTP=1 을 함께 설정하세요.")
ADMIN_PASSWORD_SSM_PARAM = "/rougether-dev/admin/seed-password"
ASSET_BUCKET = os.environ.get("ASSET_BUCKET", "rougether-assets")
AWS_REGION = os.environ.get("AWS_REGION", "ap-northeast-2")
ASSET_PUBLIC_BASE_URL = os.environ.get(
    "ASSET_PUBLIC_BASE_URL",
    f"https://{ASSET_BUCKET}.s3.{AWS_REGION}.amazonaws.com",
).rstrip("/")

# 서버(AssetKinds.ALLOWED)와 동일한 kind prefix 집합
ALLOWED_KINDS = {"characters", "categories", "themes", "items", "house"}
ALLOWED_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp"}
ALLOWED_IMAGE_FORMATS = {"PNG": "image/png", "JPEG": "image/jpeg", "WEBP": "image/webp"}
ASSET_KEY_PATTERN = re.compile(r"^[a-z0-9][a-z0-9\-_/.]*$")
# user-api RoomSlots 와 동일한 positioned 슬롯 집합
ALLOWED_SLOTS = {
    "topLeft", "topCenter", "topRight",
    "midLeft", "midRight",
    "bottomLeft", "bottomCenter", "bottomRight",
}
CSRF_INPUT_RE = re.compile(r'name="_csrf"\s+value="([^"]+)"')
CSRF_META_RE = re.compile(r'name="_csrf"\s+content="([^"]+)"')
CSRF_HEADER_META_RE = re.compile(r'name="_csrf_header"\s+content="([^"]+)"')

mcp = FastMCP("rougether-admin-asset")

_session: requests.Session | None = None
_s3_client = None
_ssm_client = None


class _AdminSessionExpired(RuntimeError):
    pass


def _s3():
    global _s3_client
    if _s3_client is None:
        _s3_client = boto3.client("s3", region_name=AWS_REGION)
    return _s3_client


def _ssm():
    global _ssm_client
    if _ssm_client is None:
        _ssm_client = boto3.client("ssm", region_name=AWS_REGION)
    return _ssm_client


def _admin_password() -> str:
    password = os.environ.get("ADMIN_PASSWORD")
    if password:
        return password
    response = _ssm().get_parameter(Name=ADMIN_PASSWORD_SSM_PARAM, WithDecryption=True)
    value = response.get("Parameter", {}).get("Value")
    if not value:
        raise RuntimeError(f"SSM 파라미터에 비밀번호 값이 없습니다: {ADMIN_PASSWORD_SSM_PARAM}")
    return value


def _login() -> requests.Session:
    _require_transport_safety()
    session = requests.Session()
    login_page = session.get(f"{ADMIN_BASE_URL}/login", timeout=10)
    login_page.raise_for_status()
    match = CSRF_INPUT_RE.search(login_page.text)
    if not match:
        raise RuntimeError("로그인 페이지에서 CSRF 토큰을 찾지 못했습니다.")
    response = session.post(
        f"{ADMIN_BASE_URL}/login",
        data={"username": ADMIN_USERNAME, "password": _admin_password(), "_csrf": match.group(1)},
        timeout=10, allow_redirects=False)
    redirect = response.headers.get("Location", "")
    if "error" in redirect:
        raise RuntimeError("어드민 로그인 실패 — 자격증명을 확인하세요.")
    return session


def _csrf_headers(session: requests.Session) -> dict[str, str]:
    page = session.get(
        f"{ADMIN_BASE_URL}/asset-foundry", timeout=10, allow_redirects=False)
    if page.status_code in (301, 302, 401, 403):
        raise _AdminSessionExpired("관리자 세션이 만료되었습니다.")
    page.raise_for_status()
    token_match = CSRF_META_RE.search(page.text)
    header_match = CSRF_HEADER_META_RE.search(page.text)
    if not token_match or not header_match:
        raise RuntimeError("Asset Foundry 페이지에서 CSRF 정보를 찾지 못했습니다.")
    return {header_match.group(1): token_match.group(1)}


def _admin_request(method: str, path: str, csrf: bool = False, **kwargs) -> requests.Response:
    """세션 만료(로그인 페이지로 redirect) 시 1회 재로그인 후 재시도."""
    global _session
    if _session is None:
        _session = _login()

    def send(session: requests.Session) -> requests.Response:
        request_kwargs = dict(kwargs)
        headers = dict(request_kwargs.pop("headers", {}))
        if csrf:
            headers.update(_csrf_headers(session))
        return session.request(method, f"{ADMIN_BASE_URL}{path}", timeout=30,
                               allow_redirects=False, headers=headers, **request_kwargs)

    try:
        response = send(_session)
    except _AdminSessionExpired:
        _session = _login()
        response = send(_session)
    if response.status_code in (301, 302, 401, 403):
        _session = _login()
        response = send(_session)
    # 재로그인 후에도 redirect/거부면 raise_for_status(3xx 미포함)에 안 걸리므로 명시적으로 실패 처리
    if response.status_code in (301, 302, 401, 403):
        raise RuntimeError(f"어드민 인증이 유지되지 않습니다 (status {response.status_code}, path {path})")
    response.raise_for_status()
    return response


def _validate_asset_key(asset_key: str) -> str:
    if not ASSET_KEY_PATTERN.match(asset_key) or ".." in asset_key:
        return "asset_key 는 소문자·숫자·하이픈·슬래시만 허용합니다 (예: items/summer-beach/furniture/xxx.png)"
    kind = asset_key.split("/", 1)[0]
    if kind not in ALLOWED_KINDS:
        return f"asset_key 는 {sorted(ALLOWED_KINDS)} 중 하나로 시작해야 합니다"
    if Path(asset_key).suffix.lower() not in ALLOWED_EXTENSIONS:
        return f"확장자는 {sorted(ALLOWED_EXTENSIONS)} 만 허용합니다"
    return ""


def _validate_catalog_asset_references(
        payload: dict[str, list[dict[str, Any]]]) -> tuple[list[str], list[str]]:
    asset_references = [
        (f"characters[{index}].baseAssetKey", "characters", character.get("baseAssetKey"))
        for index, character in enumerate(payload["characters"])
    ] + [
        (f"items[{index}].assetKey", "items", item.get("assetKey"))
        for index, item in enumerate(payload["items"])
    ]
    invalid = []
    asset_keys = []
    for field, expected_kind, asset_key in asset_references:
        if not isinstance(asset_key, str) or not asset_key:
            invalid.append(f"{field}: 비어 있거나 문자열이 아닙니다")
            continue
        error = _validate_asset_key(asset_key)
        if error:
            invalid.append(f"{field}: {error}")
            continue
        if not asset_key.startswith(f"{expected_kind}/"):
            invalid.append(f"{field}: {expected_kind}/ 로 시작해야 합니다")
            continue
        asset_keys.append(asset_key)
    return invalid, asset_keys


def _validate_render_profile_asset_references(
        profiles: list[dict[str, Any]]) -> tuple[list[str], list[str]]:
    asset_references = []
    for index, profile in enumerate(profiles):
        asset_references.append(
            (f"profiles[{index}].itemAssetKey", profile.get("itemAssetKey")))
        asset_references.append(
            (f"profiles[{index}].assetKey", profile.get("assetKey")))

    invalid = []
    asset_keys = []
    for index, profile in enumerate(profiles):
        for size_field in ("canvasWidth", "canvasHeight", "assetWidth", "assetHeight"):
            size = profile.get(size_field)
            if isinstance(size, bool) or not isinstance(size, int) or size <= 0:
                invalid.append(
                    f"profiles[{index}].{size_field}: 양의 정수여야 합니다")

    for field, asset_key in asset_references:
        if not isinstance(asset_key, str) or not asset_key:
            invalid.append(f"{field}: 비어 있거나 문자열이 아닙니다")
            continue
        error = _validate_asset_key(asset_key)
        if error:
            invalid.append(f"{field}: {error}")
            continue
        if not asset_key.startswith("items/"):
            invalid.append(f"{field}: items/ 로 시작해야 합니다")
            continue
        asset_keys.append(asset_key)
    return invalid, asset_keys


def _missing_asset_keys(asset_keys: list[str]) -> list[str]:
    return [
        asset_key for asset_key in dict.fromkeys(asset_keys)
        if not _object_exists(asset_key)
    ]


def _object_exists(asset_key: str) -> bool:
    try:
        _s3().head_object(Bucket=ASSET_BUCKET, Key=asset_key)
        return True
    except ClientError as error:
        if error.response["Error"]["Code"] in ("404", "NoSuchKey", "NotFound"):
            return False
        raise


def _public_url(asset_key: str) -> str:
    return f"{ASSET_PUBLIC_BASE_URL}/{asset_key}"


def _allowed_asset_roots() -> list[Path]:
    configured = os.environ.get("ASSET_ALLOWED_ROOTS")
    values = configured.split(os.pathsep) if configured else [str(Path.cwd())]
    return [Path(value.strip()).expanduser().resolve() for value in values if value.strip()]


def _resolve_upload_path(file_path: str) -> tuple[Path | None, str]:
    try:
        path = Path(file_path).expanduser().resolve(strict=True)
    except (FileNotFoundError, OSError) as error:
        return None, f"파일을 확인할 수 없습니다: {error}"
    if not path.is_file():
        return None, f"파일이 아닙니다: {path}"
    if not any(path == root or root in path.parents for root in _allowed_asset_roots()):
        return None, f"허용된 업로드 디렉터리 밖의 파일입니다: {path}"
    return path, ""


def _validate_image_bytes(data: bytes, expected_content_type: str) -> str:
    format_name = ""
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(BytesIO(data)) as image:
                image.verify()
                format_name = image.format or ""
                actual_content_type = ALLOWED_IMAGE_FORMATS.get(format_name)
    except (UnidentifiedImageError, OSError, SyntaxError,
            Image.DecompressionBombWarning, Image.DecompressionBombError) as error:
        return f"유효한 이미지 파일이 아닙니다: {error}"
    if actual_content_type is None:
        return f"지원하지 않는 이미지 형식입니다: {format_name}"
    if actual_content_type != expected_content_type:
        return (f"파일 내용 형식({actual_content_type})과 asset_key 확장자 형식"
                f"({expected_content_type})이 다릅니다")
    return ""


@mcp.tool()
def upload_asset(file_path: str, asset_key: str, overwrite: bool = False) -> dict[str, Any]:
    """로컬 이미지 파일을 에셋 S3 버킷에 지정한 key 로 업로드한다.

    asset_key 규칙: {kind}/{테마-코드}/{카테고리}/{파일명}.png 형태의 사람이 읽는 key.
    kind 는 characters/categories/themes/items/house 중 하나.
    예: items/summer-beach-room-v2/furniture/summer-beach-room-v2-rattan-beach-bed.png

    같은 key 가 이미 존재하면 overwrite=True 를 명시해야만 덮어쓴다.
    업로드 후 프론트가 쓸 public URL 을 반환한다. DB 연결(카탈로그 등록)은 import_catalog 로 별도 수행.
    """
    error = _validate_asset_key(asset_key)
    if error:
        return {"ok": False, "error": error}
    path, error = _resolve_upload_path(file_path)
    if error or path is None:
        return {"ok": False, "error": error}
    if path.stat().st_size > 10 * 1024 * 1024:
        return {"ok": False, "error": "이미지 크기는 10MB 이하만 허용합니다 (admin-api 와 동일 정책)"}
    data = path.read_bytes()
    content_type = mimetypes.guess_type(asset_key)[0]
    if content_type not in ("image/png", "image/jpeg", "image/webp"):
        return {"ok": False, "error": f"지원하지 않는 이미지 형식: {content_type}"}
    error = _validate_image_bytes(data, content_type)
    if error:
        return {"ok": False, "error": error}
    if not overwrite and _object_exists(asset_key):
        return {"ok": False,
                "error": f"이미 존재하는 key 입니다: {asset_key}. 기존 에셋을 교체하려면 overwrite=True 를 명시하세요.",
                "existingUrl": _public_url(asset_key)}

    _s3().put_object(Bucket=ASSET_BUCKET, Key=asset_key,
                     Body=data, ContentType=content_type)
    return {"ok": True, "assetKey": asset_key, "url": _public_url(asset_key),
            "sizeBytes": len(data), "overwritten": overwrite}


@mcp.tool()
def asset_exists(asset_key: str) -> dict[str, Any]:
    """S3 에 해당 asset key 가 이미 있는지 확인한다. 업로드 전 충돌 검사나 카탈로그 등록 전 검증에 사용."""
    error = _validate_asset_key(asset_key)
    if error:
        return {"ok": False, "error": error}
    exists = _object_exists(asset_key)
    return {"ok": True, "exists": exists, "url": _public_url(asset_key) if exists else None}


@mcp.tool()
def import_catalog(themes: list[dict[str, Any]] | None = None,
                   characters: list[dict[str, Any]] | None = None,
                   items: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    """테마/캐릭터/아이템 카탈로그를 dev DB 에 멱등 적재한다 (admin POST /admin/catalog/import).

    themes 항목: {code, name, active}
    characters 항목: {code, name, baseAssetKey, sortOrder, active}
    items 항목: {themeCode, categoryCode, placementType(positioned|surface_slot),
                 surfaceSlotType(wallpaper|floor|background|null), characterSlotType(null),
                 name, priceAmount(null=뽑기 전용), assetKey, limited, active}

    assetKey 는 먼저 upload_asset 으로 올린 key 를 그대로 사용한다.
    이미 존재하는 code/assetKey 는 건너뛴다(skip — 기존 값이 수정되지 않음). 재실행해도
    중복 생성은 없지만, 가격·이름 등 기존 항목의 변경은 이 툴로 할 수 없다.
    캐릭터 악세사리는 필수 렌더 프로필과 함께 import_character_accessory_catalog 로 적재한다.
    """
    payload = {"themes": themes or [], "characters": characters or [], "items": items or []}

    character_items = [
        item for item in payload["items"]
        if item.get("placementType") == "character"
    ]
    if character_items:
        return {
            "ok": False,
            "error": "캐릭터 악세사리는 import_character_accessory_catalog 로 적재해야 합니다.",
        }

    invalid, asset_keys = _validate_catalog_asset_references(payload)
    if invalid:
        return {"ok": False, "error": f"올바르지 않은 카탈로그 에셋 key: {invalid}"}

    missing = _missing_asset_keys(asset_keys)
    if missing:
        return {"ok": False,
                "error": f"S3 에 없는 assetKey 가 포함돼 있습니다 (먼저 upload_asset 실행): {missing}"}
    response = _admin_request("POST", "/admin/catalog/import", json=payload)
    return {"ok": True, "result": response.json()}


@mcp.tool()
def import_character_accessory_catalog(
        catalog: dict[str, list[dict[str, Any]]],
        render_profiles: list[dict[str, Any]]) -> dict[str, Any]:
    """캐릭터 악세사리 카탈로그·뽑기 풀·렌더 프로필을 한 트랜잭션으로 멱등 적재한다.

    catalog: {themes, characters, items}. items 는 모두 placementType=character 여야 한다.
    render_profiles 항목:
    {itemAssetKey, characterCode, renderState, assetKey,
     canvasWidth, canvasHeight, assetWidth, assetHeight,
     positionX, positionY, widthRatio, rotationDeg, zIndex}

    모든 아이템은 renderState=default 프로필을 포함해야 한다. 어느 항목이든 검증 또는
    저장에 실패하면 아이템과 활성 뽑기 풀까지 전부 롤백된다.
    """
    payload_catalog = {
        "themes": catalog.get("themes") or [],
        "characters": catalog.get("characters") or [],
        "items": catalog.get("items") or [],
    }
    invalid_placements = [
        item for item in payload_catalog["items"]
        if item.get("placementType") != "character"
    ]
    if invalid_placements:
        return {
            "ok": False,
            "error": f"캐릭터 악세사리 items 는 placementType=character 여야 합니다: {invalid_placements}",
        }

    catalog_invalid, catalog_asset_keys = _validate_catalog_asset_references(
        payload_catalog)
    profile_invalid, profile_asset_keys = _validate_render_profile_asset_references(
        render_profiles)
    invalid = catalog_invalid + profile_invalid
    if invalid:
        return {
            "ok": False,
            "error": f"올바르지 않은 캐릭터 악세사리 에셋 key 또는 크기: {invalid}",
        }

    missing = _missing_asset_keys(catalog_asset_keys + profile_asset_keys)
    if missing:
        return {
            "ok": False,
            "error": f"S3 에 없는 assetKey 가 포함돼 있습니다: {missing}",
        }

    response = _admin_request(
        "POST",
        "/admin/catalog/character-accessories/import",
        json={"catalog": payload_catalog, "renderProfiles": render_profiles})
    return {"ok": True, "result": response.json()}


@mcp.tool()
def import_default_slots(assignments: list[dict[str, str]]) -> dict[str, Any]:
    """positioned 가구의 기본 배치 슬롯을 멱등 적재한다 (admin POST /admin/items/slots/import).

    assignments 항목: {assetKey, slot}. slot 허용값:
    topLeft/topCenter/topRight/midLeft/midRight/bottomLeft/bottomCenter/bottomRight.
    assetKey 로 매칭하는 UPDATE 라 재실행해도 안전하다. 카탈로그가 먼저 적재돼 있어야 하며,
    응답의 notFound 는 카탈로그에 없는 assetKey 목록이다.
    """
    invalid = [a for a in assignments if a.get("slot") not in ALLOWED_SLOTS]
    if invalid:
        return {"ok": False, "error": f"허용되지 않는 slot 값: {invalid}. 허용값: {sorted(ALLOWED_SLOTS)}"}
    response = _admin_request("POST", "/admin/items/slots/import", json=assignments)
    return {"ok": True, "result": response.json()}


@mcp.tool()
def list_item_slots() -> dict[str, Any]:
    """positioned 아이템 목록과 현재 기본 슬롯 배정을 조회한다 (admin GET /admin/items/slots)."""
    response = _admin_request("GET", "/admin/items/slots")
    return {"ok": True, "result": response.json()}


@mcp.tool()
def import_character_accessory_render_profiles(
        profiles: list[dict[str, Any]]) -> dict[str, Any]:
    """캐릭터별 악세사리 합성 위치를 dev DB 에 멱등 적재한다.

    profiles 항목:
    {itemAssetKey, characterCode, renderState, assetKey,
     canvasWidth, canvasHeight, assetWidth, assetHeight,
     positionX, positionY, widthRatio, rotationDeg, zIndex}

    좌표는 canvasWidth x canvasHeight 캐릭터 원본 캔버스 기준 중심점 0.0~1.0,
    widthRatio 는 악세사리 표시 너비 / 캐릭터 캔버스 너비다.
    assetWidth/assetHeight 는 프론트가 단품 이미지의 표시 높이를 계산할 때 사용한다.
    renderState=default 프로필이 있어야 해당 캐릭터에 장착할 수 있다.
    같은 아이템·캐릭터·상태를 다시 보내면 좌표를 갱신한다.
    """
    invalid, asset_keys = _validate_render_profile_asset_references(profiles)
    if invalid:
        return {"ok": False, "error": f"올바르지 않은 렌더 프로필 에셋 key: {invalid}"}

    missing = _missing_asset_keys(asset_keys)
    if missing:
        return {
            "ok": False,
            "error": f"S3 에 없는 assetKey 가 포함돼 있습니다: {missing}",
        }

    response = _admin_request(
        "POST", "/admin/character-accessory-render-profiles/import", json=profiles)
    return {"ok": True, "result": response.json()}


@mcp.tool()
def list_character_accessory_render_profiles() -> dict[str, Any]:
    """캐릭터별 악세사리 합성 위치를 조회한다."""
    response = _admin_request("GET", "/admin/character-accessory-render-profiles")
    return {"ok": True, "result": response.json()}


@mcp.tool()
def list_asset_pipeline_jobs() -> dict[str, Any]:
    """Asset Foundry의 manifest 작업과 현재 품질/승인 단계를 조회한다."""
    response = _admin_request("GET", "/admin/asset-foundry/jobs")
    return {"ok": True, "result": response.json()}


@mcp.tool()
def create_asset_pipeline_job(
        name: str,
        slug: str,
        kind: str,
        output_asset_key: str,
        theme_code: str | None = None,
        source_asset_key: str | None = None,
        preview_asset_key: str | None = None,
        target_item_id: int | None = None,
        expected_old_asset_key: str | None = None) -> dict[str, Any]:
    """로컬 제작을 시작할 manifest 작업을 Asset Foundry에 등록한다.

    kind: STATIC_FURNITURE, ANIMATED_FURNITURE, CHARACTER_ANIMATION, HOUSE_FRAME 중 하나.
    기존 아이템 교체라면 target_item_id와 expected_old_asset_key를 함께 보낸다.
    이 도구는 S3 업로드나 DB 아이템 교체를 실행하지 않는다.
    """
    payload = {
        "name": name,
        "slug": slug,
        "kind": kind,
        "themeCode": theme_code,
        "sourceAssetKey": source_asset_key,
        "outputAssetKey": output_asset_key,
        "previewAssetKey": preview_asset_key,
        "targetItemId": target_item_id,
        "expectedOldAssetKey": expected_old_asset_key,
    }
    response = _admin_request(
        "POST", "/admin/asset-foundry/jobs", csrf=True, json=payload)
    return {"ok": True, "result": response.json()}


@mcp.tool()
def submit_asset_pipeline_qa(
        job_id: int,
        expected_revision: int,
        checks: list[dict[str, Any]]) -> dict[str, Any]:
    """assetctl report의 checks를 Asset Foundry 작업에 반영한다.

    작업이 BUILT 또는 VALIDATED 단계일 때만 가능하며, required FAIL은 다음 단계 진행을 막는다.
    """
    response = _admin_request(
        "PUT", f"/admin/asset-foundry/jobs/{job_id}/qa", csrf=True,
        json={"expectedRevision": expected_revision, "checks": checks})
    return {"ok": True, "result": response.json()}


@mcp.tool()
def advance_asset_pipeline_job(
        job_id: int,
        expected_revision: int,
        target_status: str,
        note: str) -> dict[str, Any]:
    """Asset Foundry 작업을 허용된 다음 단계로 이동하거나 검수 작업을 DRAFT로 반환한다.

    실제 S3·DB·seed 조작은 하지 않으며 운영자가 확인할 감사 이력만 남긴다.
    """
    response = _admin_request(
        "PUT", f"/admin/asset-foundry/jobs/{job_id}/status", csrf=True,
        json={
            "expectedRevision": expected_revision,
            "targetStatus": target_status,
            "note": note,
        })
    return {"ok": True, "result": response.json()}


if __name__ == "__main__":
    mcp.run()
