import json
from pathlib import Path

import pytest

from rougether_ai import ai_user_policy as policy

REGISTRY = Path(__file__).resolve().parent.parent / "prompt_versions.json"


def registry():
    return json.loads(REGISTRY.read_text(encoding="utf-8"))


def test_current_prompt_sources_match_registered_version():
    # 실패하면 프롬프트 원천이 바뀐 것임. PROMPT_VERSION을 올리고 등록부에 새 지문을 추가할 것.
    assert registry().get(policy.PROMPT_VERSION) == policy.prompt_fingerprint()


def test_registry_keeps_unique_fingerprints_in_version_format():
    entries = registry()
    assert len(set(entries.values())) == len(entries)
    for version, fingerprint in entries.items():
        assert policy._PROMPT_VERSION_FORMAT.fullmatch(version)
        assert len(fingerprint) == 64 and int(fingerprint, 16) >= 0


@pytest.mark.parametrize("name", policy.PROMPT_SOURCES)
def test_changing_any_prompt_source_changes_fingerprint(monkeypatch, name):
    before = policy.prompt_fingerprint()
    value = getattr(policy, name)
    if isinstance(value, str):
        changed = value + " "
    elif isinstance(value, dict):
        changed = {**value, "__probe__": "x"}
    else:
        changed = value + (("PROBE", "x"),)
    monkeypatch.setattr(policy, name, changed)
    assert policy.prompt_fingerprint() != before


def test_every_long_text_constant_is_fingerprinted():
    # 새 프롬프트 상수를 만들고 PROMPT_SOURCES에 넣지 않으면 버전 없이 프롬프트가 바뀔 수 있으므로 막음.
    texts = {name for name, value in vars(policy).items()
             if name.isupper() and not name.startswith("_") and name != "PROMPT_SOURCES"
             and isinstance(value, (str, dict, tuple))
             and len(json.dumps(value, ensure_ascii=False)) >= 30}
    assert texts <= set(policy.PROMPT_SOURCES), texts - set(policy.PROMPT_SOURCES)


def test_rendered_system_prompt_uses_only_fingerprinted_text():
    state = {"source": {"type": "SOCIAL_BROWSE"}, "actions": ["FEED_POST"], "personaKey": "steady-walk",
             "date": "2026-11-04"}
    rendered = policy.decision_system(state)
    assert policy.POST_VOICE_LEAD in rendered and policy.POST_BRIEF_GUIDE in rendered


def test_split_prompt_version_keeps_valid_version_and_drops_malformed():
    plan = {"action": "WAIT", "payload": {"reason": "NO_ACTION_DUE"}, "memory": ""}
    bare, version = policy.split_prompt_version(policy.with_prompt_version(plan, policy.PROMPT_VERSION))
    assert bare == plan and version == policy.PROMPT_VERSION
    for bad in ["", "x" * 41, "버전", None, 3]:
        assert policy.split_prompt_version({**plan, "promptVersion": bad}) == (plan, None)
