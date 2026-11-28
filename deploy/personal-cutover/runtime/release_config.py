"""Render only a verified host-bound bundle. The callers never print field contents."""
import base64
import ipaddress
import json
from pathlib import Path
import re

PREFIX = '/rougether-personal/cutover/'
AUTH = {'jwt/secret':'JWT_SECRET', 'kakao/admin-key':'KAKAO_ADMIN_KEY',
        'apple/team-id':'APPLE_TEAM_ID', 'apple/key-id':'APPLE_KEY_ID',
        'apple/private-key':'APPLE_PRIVATE_KEY', 'apple/refresh-token-enc-key':'APPLE_REFRESH_TOKEN_ENC_KEY'}
ALERTS = {'alerts/webex-bot-token':'OPERATIONS_WEBEX_BOT_TOKEN','alerts/webex-room-id':'OPERATIONS_WEBEX_ROOM_ID'}
REQUIRED = set(AUTH) | set(ALERTS) | {'firebase/credentials-json','admin/origin-secret','llm/api-key','db/api-password','db/furniture-password','db/server-tls'}
BUCKET = 'rougether-fallback-940482450790-assets'
CDN = 'https://d25hfubpdm45t2.cloudfront.net'
QUEUE = 'https://sqs.ap-northeast-2.amazonaws.com/940482450790/rougether-personal-furniture-jobs'


def materialize(bundle, destination):
    if set(bundle) != {'purpose','privateIPv4','parameters','trust','queue'} or bundle['purpose'] != 'personal-sealed-runtime-v1' or bundle['queue'] != QUEUE:
        raise ValueError('configuration bundle identity mismatch')
    ip = ipaddress.IPv4Address(bundle['privateIPv4'])
    if ip not in ipaddress.ip_network('172.16.0.0/12'):
        raise ValueError('unexpected database endpoint')
    values = bundle['parameters']
    if set(values) != REQUIRED or not all(isinstance(x,str) and x.strip() for x in values.values()):
        raise ValueError('invalid configuration fields')
    for field in ('db/api-password','db/furniture-password'):
        if not re.fullmatch(r'[A-Za-z0-9_-]{40,64}',values[field]):
            raise ValueError('invalid database credential')
    firebase = json.loads(values['firebase/credentials-json'])
    if firebase.get('type') != 'service_account' or not all(firebase.get(k) for k in ('client_email','project_id','private_key')):
        raise ValueError('invalid Firebase credential')
    tls = json.loads(values['db/server-tls'])
    if set(tls) != {'ca','certificate','key'}:
        raise ValueError('invalid TLS bundle')
    trust = base64.b64decode(bundle['trust'],validate=True)
    if not trust:
        raise ValueError('empty trust store')
    folder = Path(destination)
    folder.mkdir(mode=0o700,exist_ok=False)
    url = f'jdbc:mysql://{ip}:3306/rougether?serverTimezone=UTC&characterEncoding=UTF-8&sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/run/rougether/config/trust.p12&trustCertificateKeyStoreType=PKCS12&trustCertificateKeyStorePassword=public-truststore&fallbackToSystemTrustStore=false'
    common = {'DB_URL':url, 'DB_USERNAME':'rougether_api','DB_PASSWORD':values['db/api-password'],
              'firebase.credentials-path':'/run/rougether/config/firebase.json',
              'firebase.json':values['firebase/credentials-json'],'trust.p12':trust}
    apps={svc:dict(common) for svc in ['user-api','admin-api','batch']}
    for field,key in AUTH.items():
        apps['user-api'][key]=values[field]
    apps['admin-api']['ADMIN_ORIGIN_SECRET']=values['admin/origin-secret']
    for svc in ['user-api','admin-api']:
        apps[svc].update({'asset.s3.bucket':BUCKET,'asset.s3.region':'ap-northeast-2','asset.public-base-url':CDN})
    for svc in ['user-api','batch']:
        apps[svc]['llm.api-key']=values['llm/api-key']
        for field, key in ALERTS.items():
            apps[svc][key] = values[field]
    apps['user-api'].update({'furniture.lambda.queue-url':QUEUE,
                            'asset.s3.purge-versions-on-delete':'true',
                            'chat.websocket.allowed-origins':'https://43.203.93.12,https://dkfiwkal2ezg9.cloudfront.net',
                            'cors.allowed-origins':'https://app.rougether.com'})
    files = {**apps,'database':{'ca.pem':tls['ca'],'server.pem':tls['certificate'],'server-key.pem':tls['key']},
             'gateway':{'admin-origin':values['admin/origin-secret']},
             'db-accounts':{'api-password':values['db/api-password'],'furniture-password':values['db/furniture-password']}}
    for name,settings in files.items():
        sub=folder/name
        sub.mkdir(mode=0o700)
        for key,value in settings.items():
            data=value if isinstance(value,bytes) else value.encode()
            path=sub/key
            with path.open('xb') as f:
                path.chmod(0o400)
                f.write(data)
    return {'services':sorted(apps),'mysqlTls':'VERIFY_IDENTITY','queueAccountSuffix':'0790','connected':False}


if __name__=='__main__':
    import sys
    import os
    os.umask(0o077)
    result=materialize(json.load(sys.stdin),sys.argv[1])
    print(json.dumps(result))
