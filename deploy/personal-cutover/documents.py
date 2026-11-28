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
    assert mode in ('enroll','restore','fixture','furniture-check','runtime-enroll','runtime-check','standby-check','renewal-check','clone-sealed-db','release-config','prepare-db-users','prepare-release','warm-release','activate-release','seal-release') and re.fullmatch(r'i-[0-9a-f]{17}',machine)
    source = (HERE / 'host' / (mode+'.sh')).read_text().replace('EXPECTED_MACHINE',machine)
    params = {}
    if mode == 'prepare-release':
        files = [
            'runtime/compose.apps.yaml', 'runtime/compose.identity.yaml', 'runtime/compose.database.yaml',
            'runtime/compose.routing.yaml', 'runtime/compose.standby.yaml', 'runtime/compose.warm.yaml',
            'runtime/render_routing.py', 'runtime/warm_network.sh', 'runtime/compose.live.yaml', 'runtime/source_settings.py',
            'runtime/release_plan.py', 'runtime/release_candidate.py', 'gateway/target.Caddyfile', 'gateway/admin.Caddyfile',
        ]
        parts = []
        for path in files:
            content = (HERE / path).read_text()
            assert content.endswith('\n') and 'RELEASE_FILE_END' not in content
            parts.append(f"cat > {path} <<'RELEASE_FILE_END'\n{content}RELEASE_FILE_END")
        assert source.count('# RELEASE_TOOLS') == 1
        source = source.replace('# RELEASE_TOOLS', '\n'.join(parts))
        params['ConfigDigest'] = {'type':'String','allowedPattern':'^[a-f0-9]{64}$','interpolationType':'ENV_VAR'}
    if mode == 'renewal-check':
        java = (HERE/'runtime/RenewalProbe.java').read_text()
        assert source.count('# RENEWAL_JAVA_SOURCE') == 1
        source = source.replace('# RENEWAL_JAVA_SOURCE', "cat > RenewalProbe.java <<'RENEWAL_END'\n"+java+"RENEWAL_END")
    if mode == 'standby-check':
        files = []
        for name in ('compose.apps.yaml','compose.standby.yaml','standby_check.py'):
            content = (HERE/'runtime'/name).read_text()
            files.append(f"cat > {name} <<'STANDBY_END'\n{content}STANDBY_END")
        assert source.count('# STANDBY_TOOLS') == 1
        source = source.replace('# STANDBY_TOOLS', '\n'.join(files))
    if mode == 'runtime-check':
        marker = '# JAVA_PROBE_SOURCE'
        assert source.count(marker) == 1
        java = (HERE/'runtime/RuntimeProbe.java').read_text()
        source = source.replace(marker, 'cat > "$tmp/RuntimeProbe.java" <<\'JAVA_END\'\n'+java+'JAVA_END')
        for key in ('AnchorId','UserProfileId','AdminProfileId'):
            params[key] = {'type':'String','allowedPattern':'^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$','interpolationType':'ENV_VAR'}
    if mode == 'clone-sealed-db':
        verifier = (HERE/'host/verify.py').read_text()
        assert source.count('# SEALED_DB_VERIFIER') == 1
        source = source.replace('# SEALED_DB_VERIFIER', 'cat > "$tmp/verify.py" <<\'VERIFY_END\'\n'+verifier+'VERIFY_END')
        for key,regex in {'Snapshot':'^[0-9]{8}-[0-9]{4}$','Digest':'^[a-f0-9]{64}$'}.items():
            params[key]={'type':'String','allowedPattern':regex,'interpolationType':'ENV_VAR'}
    if mode == 'activate-release':
        params['SourceSettings'] = {'type':'String','allowedPattern':'^[A-Za-z0-9+/=]{16,22000}$','interpolationType':'ENV_VAR'}
    if mode in ('warm-release','activate-release','seal-release'):
        params['Bundle'] = {'type':'String','allowedPattern':'^[a-f0-9]{64}$','interpolationType':'ENV_VAR'}
    if mode in ('prepare-db-users','warm-release','activate-release','seal-release'):
        for key,regex in {'Snapshot':'^[0-9]{8}-[0-9]{4}$','Digest':'^[a-f0-9]{64}$','ConfigDigest':'^[a-f0-9]{64}$'}.items():
            params[key]={'type':'String','allowedPattern':regex,'interpolationType':'ENV_VAR'}
    if mode == 'release-config':
        renderer = (HERE/'runtime/release_config.py').read_text()
        assert source.count('# CONFIG_RENDERER') == 1
        source = source.replace('# CONFIG_RENDERER', "cat > \"$work/render.py\" <<'CONFIG_END'\n"+renderer+"CONFIG_END")
        for key,regex in {'Digest':'^[a-f0-9]{64}$','Envelope':r'^https://rougether-cutover-940482450790-artifacts\.s3\.ap-northeast-2\.amazonaws\.com/restore/config/[a-f0-9]{64}\.cms\?[A-Za-z0-9_.%&=+~/-]+$'}.items():
            params[key]={'type':'String','allowedPattern':regex,'interpolationType':'ENV_VAR'}
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
               'parameters':params,'mainSteps':[{'action':'aws:runShellScript','name':mode.replace('-',''),
               'inputs':{'timeoutSeconds':'1800','runCommand':[command]}}]}
    raw=json.dumps(content,sort_keys=True,separators=(',',':'))
    return 'RougetherPersonal-'+mode+'-'+hashlib.sha256(raw.encode()).hexdigest()[:20],raw
