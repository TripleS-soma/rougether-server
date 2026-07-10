"""Rougether 어드민 에셋 MCP 서버.

AI 에이전트(Claude Code, Codex CLI)가 이미지를 생성한 뒤 바로 dev 환경에
등록할 수 있게 하는 stdio MCP 서버다. 노출 툴:

- upload_asset:        로컬 이미지 파일을 S3 에 사람이 읽는 커스텀 key 로 업로드
                       (admin-api 의 POST /admin/assets 는 uuid key 를 발급하므로,
                        기존 큐레이션 key 관행은 S3 직접 업로드로 유지한다)
- asset_exists:        S3 에 해당 key 가 이미 있는지 확인
- import_catalog:      테마/캐릭터/아이템 카탈로그 멱등 적재 (POST /admin/catalog/import)
- import_default_slots: positioned 가구의 기본 슬롯 멱등 적재 (POST /admin/items/slots/import)
- list_item_slots:     positioned 아이템·슬롯 현황 조회 (GET /admin/items/slots)

실행 (Python >= 3.10 필요, uv 가 의존성을 처리):

    uv run --python 3.12 --with mcp --with requests --with boto3 \
        python tools/admin-asset-mcp/server.py

MCP 클라이언트 등록 예 (Claude Code / Codex CLI 공통 개념):

    command: uv
    args: [run, --python, "3.12", --with, mcp, --with, requests, --with, boto3,
           python, /경로/rougether-server/tools/admin-asset-mcp/server.py]

환경변수:

- ADMIN_BASE_URL  (기본 http://43.203.209.107:8081 — dev 어드민)
- ADMIN_USERNAME  (기본 admin)
- ADMIN_PASSWORD  (미설정 시 SSM /rougether-dev/admin/seed-password 에서 조회)
- ASSET_BUCKET    (기본 rougether-assets)
- AWS_REGION      (기본 ap-northeast-2)

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
import subprocess
from pathlib import Path
from typing import Any

import boto3
import requests
from mcp.server.fastmcp import FastMCP

ADMIN_BASE_URL = os.environ.get("ADMIN_BASE_URL", "http://43.203.209.107:8081").rstrip("/")
ADMIN_USERNAME = os.environ.get("ADMIN_USERNAME", "admin")
ADMIN_PASSWORD_SSM_PARAM = "/rougether-dev/admin/seed-password"
ASSET_BUCKET = os.environ.get("ASSET_BUCKET", "rougether-assets")
AWS_REGION = os.environ.get("AWS_REGION", "ap-northeast-2")

# 서버(AssetKinds.ALLOWED)와 동일한 kind prefix 집합
ALLOWED_KINDS = {"characters", "categories", "themes", "items", "house"}
ALLOWED_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp"}
ASSET_KEY_PATTERN = re.compile(r"^[a-z0-9][a-z0-9\-_/.]*$")
# user-api RoomSlots 와 동일한 positioned 슬롯 집합
ALLOWED_SLOTS = {
    "topLeft", "topCenter", "topRight",
    "midLeft", "midRight",
    "bottomLeft", "bottomCenter", "bottomRight",
}
CSRF_INPUT_RE = re.compile(r'name="_csrf"\s+value="([^"]+)"')

mcp = FastMCP("rougether-admin-asset")

_session: requests.Session | None = None
_s3_client = None


def _s3():
    global _s3_client
    if _s3_client is None:
        _s3_client = boto3.client("s3", region_name=AWS_REGION)
    return _s3_client


def _admin_password() -> str:
    password = os.environ.get("ADMIN_PASSWORD")
    if password:
        return password
    completed = subprocess.run(
        ["aws", "ssm", "get-parameter", "--name", ADMIN_PASSWORD_SSM_PARAM,
         "--with-decryption", "--query", "Parameter.Value", "--output", "text",
         "--region", AWS_REGION],
        check=True, text=True, capture_output=True)
    return completed.stdout.strip()


def _login() -> requests.Session:
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


def _admin_request(method: str, path: str, **kwargs) -> requests.Response:
    """세션 만료(로그인 페이지로 redirect) 시 1회 재로그인 후 재시도."""
    global _session
    if _session is None:
        _session = _login()
    response = _session.request(method, f"{ADMIN_BASE_URL}{path}", timeout=30,
                                allow_redirects=False, **kwargs)
    if response.status_code in (301, 302, 401, 403):
        _session = _login()
        response = _session.request(method, f"{ADMIN_BASE_URL}{path}", timeout=30,
                                    allow_redirects=False, **kwargs)
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


def _object_exists(asset_key: str) -> bool:
    try:
        _s3().head_object(Bucket=ASSET_BUCKET, Key=asset_key)
        return True
    except _s3().exceptions.ClientError as error:
        if error.response["Error"]["Code"] in ("404", "NoSuchKey", "NotFound"):
            return False
        raise


def _public_url(asset_key: str) -> str:
    return f"https://{ASSET_BUCKET}.s3.{AWS_REGION}.amazonaws.com/{asset_key}"


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
    path = Path(file_path).expanduser()
    if not path.is_file():
        return {"ok": False, "error": f"파일이 없습니다: {path}"}
    if path.stat().st_size > 10 * 1024 * 1024:
        return {"ok": False, "error": "이미지 크기는 10MB 이하만 허용합니다 (admin-api 와 동일 정책)"}
    content_type = mimetypes.guess_type(asset_key)[0]
    if content_type not in ("image/png", "image/jpeg", "image/webp"):
        return {"ok": False, "error": f"지원하지 않는 이미지 형식: {content_type}"}
    if not overwrite and _object_exists(asset_key):
        return {"ok": False,
                "error": f"이미 존재하는 key 입니다: {asset_key}. 기존 에셋을 교체하려면 overwrite=True 를 명시하세요.",
                "existingUrl": _public_url(asset_key)}

    _s3().put_object(Bucket=ASSET_BUCKET, Key=asset_key,
                     Body=path.read_bytes(), ContentType=content_type)
    return {"ok": True, "assetKey": asset_key, "url": _public_url(asset_key),
            "sizeBytes": path.stat().st_size, "overwritten": overwrite}


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
                 surfaceSlotType(wallpaper|floor|background|null), characterSlotType(null 가능),
                 name, priceAmount(null=뽑기 전용), assetKey, limited, active}

    assetKey 는 먼저 upload_asset 으로 올린 key 를 그대로 사용한다.
    같은 code/assetKey 재적재는 UPDATE 로 처리되므로 재실행해도 안전하다.
    """
    payload = {"themes": themes or [], "characters": characters or [], "items": items or []}
    missing = [item["assetKey"] for item in payload["items"]
               if item.get("assetKey") and not _object_exists(item["assetKey"])]
    if missing:
        return {"ok": False,
                "error": f"S3 에 없는 assetKey 가 포함돼 있습니다 (먼저 upload_asset 실행): {missing}"}
    response = _admin_request("POST", "/admin/catalog/import", json=payload)
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


if __name__ == "__main__":
    mcp.run()
