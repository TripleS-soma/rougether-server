"""Pin each SSM document to its reviewed content and target machine."""
import hashlib
import json
from pathlib import Path
import re

HERE = Path(__file__).resolve().parent
ACCOUNT = '940482450790'
REGION = 'ap-northeast-2'
BUCKET = 'rougether-cutover-940482450790-artifacts'
NODE = 'mi-017285f379d04931f'
INSTANCE = 'rougether-fallback-stage'
def document(mode, machine):
    assert mode in ('enroll','restore','fixture','furniture-check') and re.fullmatch(r'i-[0-9a-f]{17}',machine)
    source = (HERE / 'host' / (mode+'.sh')).read_text().replace('EXPECTED_MACHINE',machine)
    params = {}
    if mode == 'restore':
        verifier = (HERE/'host/verify.py').read_text()
        marker = 'python3 "$tmp/verify.py"'
        assert source.count(marker) == 1
        source = source.replace(marker, 'cat > "$tmp/verify.py" <<\'VERIFY_END\'\n'+verifier+'VERIFY_END\n'+marker)
    if mode in ('restore','fixture'):
        for key, regex in {'Snapshot':'^[0-9]{8}-[0-9]{4}$', 'Digest':'^[a-f0-9]{64}$',
                           'Envelope':r'^https://rougether-cutover-940482450790-artifacts\.s3\.ap-northeast-2\.amazonaws\.com/restore/[0-9]{8}-[0-9]{4}/[a-f0-9]{64}\.cms\?[A-Za-z0-9_.%&=+~/-]+$'}.items():
            params[key] = {'type':'String','allowedPattern':regex,'interpolationType':'ENV_VAR'}
    # SSM launches a POSIX shell. Explicitly select Bash for the locked host routine.
    command = "exec /bin/bash <<'ROUGETHER_SSM_END'\n"+source+"\nROUGETHER_SSM_END"
    content = {'schemaVersion':'2.2','description':'Rougether Seoul isolated candidate '+mode,
               'parameters':params,'mainSteps':[{'action':'aws:runShellScript','name':mode,
               'inputs':{'timeoutSeconds':'1800','runCommand':[command]}}]}
    raw=json.dumps(content,sort_keys=True,separators=(',',':'))
    return 'RougetherPersonal-'+mode+'-'+hashlib.sha256(raw.encode()).hexdigest()[:20],raw
