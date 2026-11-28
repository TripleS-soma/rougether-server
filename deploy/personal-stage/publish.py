"""Deploy only the synthetic rehearsal gateway, from a protected Actions environment."""
import base64
import hashlib
import io
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import time

REGION = "ap-northeast-2"
INSTANCE = "rougether-fallback-stage"
HERE = Path(__file__).resolve().parent
FILES = ("install.sh", "Caddyfile", "GoogleKeys.Caddyfile", "compose.https.yaml",
         "web/index.html", "web/mobile.js")


def aws(*args):
    result = subprocess.check_output(["aws", *args, "--region", REGION, "--output", "json"])
    return json.loads(result) if result.strip() else {}


def run_on_node(node, command, timeout):
    params = json.dumps({"commands": [command], "executionTimeout": [str(timeout)]})
    command_id = aws("ssm", "send-command", "--instance-ids", node,
                     "--document-name", "AWS-RunShellScript", "--timeout-seconds", "60",
                     "--parameters", params)["Command"]["CommandId"]
    until = time.monotonic() + timeout + 65
    while time.monotonic() < until:
        records = aws("ssm", "list-command-invocations", "--command-id", command_id,
                      "--instance-id", node)["CommandInvocations"]
        if records:
            state = records[0]["Status"]
            if state == "Success":
                return
            if state not in ("Pending", "InProgress", "Delayed", "Cancelling"):
                raise RuntimeError(f"SSM command {command_id}: {state}")
        time.sleep(4)
    raise RuntimeError(f"SSM command {command_id}: timed out")


def rule(port, cidr):
    return dict(fromPort=port, toPort=port, protocol="tcp", cidrs=[cidr], ipv6Cidrs=[])


def missing_rules(states, desired):
    missing = []
    for wanted in desired:
        relevant = [s for s in states if s["state"] == "open" and
                    (s["protocol"] == "all" or (s["protocol"] == "tcp" and
                     s["fromPort"] <= wanted["fromPort"] <= s["toPort"]))]
        if not relevant:
            missing.append(wanted)
        elif len(relevant) != 1 or any(relevant[0].get(k) != v for k, v in wanted.items()) or relevant[0].get("cidrListAliases"):
            raise RuntimeError("Existing inbound access differs from the approved test scope")
    return missing


def main():
    account, node, wifi, code_hash = (os.environ[k] for k in
        ("STAGE_ACCOUNT", "STAGE_NODE", "STAGE_WIFI", "STAGE_CODE_HASH"))
    assert re.fullmatch(r"[0-9]{12}", account) and re.fullmatch(r"mi-[0-9a-f]{17}", node)
    net = ipaddress.IPv4Network(wifi)
    assert net.prefixlen == 32 and net.network_address.is_global
    assert re.fullmatch(r"\$2[aby]\$1[0-4]\$[./A-Za-z0-9]{53}", code_hash)
    release = os.environ["GITHUB_SHA"]
    assert re.fullmatch(r"[0-9a-f]{40}", release)
    assert aws("sts", "get-caller-identity")["Account"] == account
    instance = aws("lightsail", "get-instance", "--instance-name", INSTANCE)["instance"]
    tags = {x["key"]: x["value"] for x in instance["tags"]}
    assert tags["Environment"] == "isolated-rehearsal" and tags["Project"] == "Rougether"
    assert instance["arn"].split(":")[4] == account and instance["state"]["name"] == "running"
    address = str(ipaddress.IPv4Address(instance["publicIpAddress"]))
    machine = instance["supportCode"].split("/")[-1]
    assert re.fullmatch(r"i-[0-9a-f]{17}", machine)
    nodes = aws("ssm", "describe-instance-information", "--filters",
                f"Key=InstanceIds,Values={node}")["InstanceInformationList"]
    assert len(nodes) == 1 and nodes[0]["PingStatus"] == "Online"
    assert nodes[0]["IPAddress"] == instance["privateIpAddress"]
    # IMDSv2 corroborates the node identity independently of SSM inventory.
    preflight = """set -eu
token=$(curl -fsS --max-time 3 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
id=$(curl -fsS --max-time 3 -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/instance-id)
test "$id" = MACHINE
cd /home/ubuntu/rougether-stage
test -f compose.yaml
gateway=$(docker compose -p rougether-rehearsal -f compose.yaml ps -q --all | xargs -r docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' | awk '$1=="stage-https"')
if test "$gateway" != stage-https; then
  test -z "$(ss -H -ltn '( sport = :80 or sport = :443 )')"
  test -z "$(docker ps -q --filter publish=80)"
  test -z "$(docker ps -q --filter publish=443)"
fi
""".replace("MACHINE", machine)
    run_on_node(node, preflight, 45)
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as archive:
        for name in FILES:
            archive.add(HERE / name, arcname=name)
    data = buf.getvalue()
    package = base64.b64encode(data).decode()
    script = "\n".join(["set -eu", "umask 077", 'd=$(mktemp -d)', """trap 'rm -rf "$d"' EXIT""",
        f"printf %s {package} | base64 -d > \"$d/release.tgz\"",
        f'echo "{hashlib.sha256(data).hexdigest()}  $d/release.tgz" | sha256sum -c -',
        'tar -xzf "$d/release.tgz" -C "$d" install.sh',
        f"export RELEASE={release} STAGE_IP={address} TEST_CIDR={wifi} STAGE_CODE_HASH={shlex.quote(code_hash)}",
        'bash "$d/install.sh" "$d/release.tgz"'])
    desired = [rule(80, "0.0.0.0/0"), rule(443, wifi)]
    states = aws("lightsail", "get-instance-port-states", "--instance-name", INSTANCE)["portStates"]
    opened = []
    try:
        for item in missing_rules(states, desired):
            aws("lightsail", "open-instance-public-ports", "--instance-name", INSTANCE,
                "--port-info", json.dumps(item))
            opened.append(item)
        run_on_node(node, script, 720)
    except Exception:
        for item in reversed(opened):
            aws("lightsail", "close-instance-public-ports", "--instance-name", INSTANCE,
                "--port-info", json.dumps(item))
        raise
    print("Stage HTTPS health check passed. Use the test link on the allowed Wi-Fi.")


if __name__ == "__main__":
    main()
