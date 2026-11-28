"""Read an authenticated GitHub artifact produced by close at the currently deployed source revision."""
import base64
import io
import json
import re
import subprocess
import zipfile
from runtime.source_settings import validated

REPO = "TripleS-soma/rougether-server"

def get(path):
    return subprocess.check_output(["gh","api","repos/"+REPO+"/"+path])

def load(run_id):
    if not re.fullmatch("[0-9]+",run_id):
        raise ValueError("Unexpected source settings run")
    run=json.loads(get("actions/runs/"+run_id))
    main=json.loads(get("git/ref/heads/main"))["object"]["sha"]
    if (run["conclusion"]!="success" or run["head_sha"]!=main or run["head_branch"]!="main" or
        run["event"]!="workflow_dispatch" or run["path"]!=".github/workflows/docker-publish.yml" or
        run["head_repository"]["full_name"]!=REPO):
        raise RuntimeError("Source settings did not come from the reviewed deployment")
    rows=json.loads(get("actions/runs/"+run_id+"/artifacts?name=source-cutover-settings"))["artifacts"]
    artifact,=rows
    if artifact["expired"] or artifact["name"]!="source-cutover-settings" or artifact["size_in_bytes"]>32768:
        raise RuntimeError("Missing valid runtime settings")
    archive=get("actions/artifacts/"+str(artifact["id"])+"/zip")
    import hashlib
    if artifact["digest"]!="sha256:"+hashlib.sha256(archive).hexdigest():
        raise RuntimeError("Source settings artifact checksum differs")
    with zipfile.ZipFile(io.BytesIO(archive)) as z:
        entry,=z.infolist()
        if entry.filename!="source-cutover-settings.json" or entry.file_size>16000:
            raise RuntimeError("Unexpected source settings artifact")
        data=json.loads(z.read(entry))
    if data!={"sourceAccount":"776158585524","region":"ap-northeast-2","release":main,
              "run":run_id,"userApiSettings":validated(data["userApiSettings"])}:
        raise ValueError("Unexpected source settings")
    return base64.b64encode(json.dumps(data,sort_keys=True,separators=(",",":")).encode()).decode()
