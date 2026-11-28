"""Configure the disconnected personal workers or switch only the reviewed queue mapping."""
import hmac
import json
import os
import time
from pathlib import Path
import boto3

ACCOUNT='940482450790'
REGION='ap-northeast-2'
# Verified against this candidate's private Lightsail address and the TLS SAN.
PRIVATE_ADDRESS='172.26.10.189'
PREFIX='rougether-personal-furniture-'
ARTIFACTS='rougether-cutover-940482450790-artifacts'
ASSETS='rougether-fallback-940482450790-assets'
MAPPING='e416e6b1-8023-487a-b3d0-f934e3f2d1d3'
HASHES={'ai':'aRO2Te5+jY7zMx9KhfUQRZ/i7D5TMN31ju3mfl/80go=','control':'jMMHVf4KVVER5s2d3K1Ne3HgC9MQJBxAfqhwyadCBkg='}
PARAMETER='/rougether-personal/cutover/db/furniture-password'

def require(ok,why):
    if not ok:raise RuntimeError(why)

def url(ip,schema):
    return f'jdbc:mysql://{ip}:3306/{schema}?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/opt/rougether-trust.p12&trustCertificateKeyStoreType=PKCS12&trustCertificateKeyStorePassword=public-truststore&serverTimezone=Asia/Seoul'

def desired(kind,old,ip,password):
    env=dict(old)
    if kind=='ai':
        require(env['ASSET_S3_BUCKET'] in (ASSETS,ARTIFACTS) and
                env['CONTROL_FUNCTION_NAME']==PREFIX+'control' and
                env['LLM_API_KEY_PARAMETER']=='/rougether-personal/cutover/llm/api-key','Unexpected AI runtime')
        env['ASSET_S3_BUCKET']=ASSETS
    elif kind=='control':
        require((env['SPRING_DATASOURCE_URL'],env['SPRING_DATASOURCE_USERNAME']) in (
                (url(ip,'rougether_furniture_fixture'),'furniture_fixture'),(url(ip,'rougether'),'rougether_furniture')),
                'Unexpected database connection')
        require(password and len(password)>=24,'Missing furniture-only database password')
        env.update(SPRING_DATASOURCE_URL=url(ip,'rougether'),SPRING_DATASOURCE_USERNAME='rougether_furniture',
                   SPRING_DATASOURCE_PASSWORD=password)
    else:raise ValueError('Unexpected worker')
    return env

def function(lm,kind):
    r=lm.get_function_configuration(FunctionName=PREFIX+kind)
    require(r['FunctionArn']==f'arn:aws:lambda:{REGION}:{ACCOUNT}:function:{PREFIX}{kind}' and
            r['Role']==f'arn:aws:iam::{ACCOUNT}:role/{PREFIX}{kind}' and r['CodeSha256']==HASHES[kind] and
            r['State']=='Active' and r['LastUpdateStatus']=='Successful','Unexpected worker revision')
    return r

def mapping(lm):
    m=lm.get_event_source_mapping(UUID=MAPPING)
    require(m['FunctionArn']==f'arn:aws:lambda:{REGION}:{ACCOUNT}:function:{PREFIX}ai' and
            m['EventSourceArn']==f'arn:aws:sqs:{REGION}:{ACCOUNT}:{PREFIX}jobs' and
            m['BatchSize']==1 and 'ReportBatchItemFailures' in m['FunctionResponseTypes'],'Unexpected queue wiring')
    return m['State']

def empty(sqs):
    attrs=['ApproximateNumberOfMessages','ApproximateNumberOfMessagesDelayed','ApproximateNumberOfMessagesNotVisible']
    for suffix in ('jobs','dlq'):
        q=f'https://sqs.{REGION}.amazonaws.com/{ACCOUNT}/{PREFIX}{suffix}'
        values=sqs.get_queue_attributes(QueueUrl=q,AttributeNames=attrs)['Attributes']
        require(all(values.get(a)=='0' for a in attrs),'Personal furniture queue has pending work')

def public_health(host,expected):
    from urllib.error import HTTPError
    from urllib.request import Request,urlopen
    from uuid import uuid4
    req=Request('https://'+host+'/api/v1/health?cutover='+uuid4().hex,headers={'Cache-Control':'no-cache'})
    try:r=urlopen(req,timeout=8)
    except HTTPError as e:r=e
    with r:
        require(r.status==200 and (r.headers.get('X-Rougether-Origin')=='personal-api')==expected,'Unexpected origin or health')

def sealed_origin(host):
    from urllib.error import HTTPError
    from urllib.request import Request,urlopen
    from uuid import uuid4
    req=Request('https://'+host+'/api/v1/me?cutover='+uuid4().hex,headers={'Cache-Control':'no-cache'})
    try:r=urlopen(req,timeout=8)
    except HTTPError as e:r=e
    with r:
        require(r.status==503 and r.headers.get('Retry-After')=='60' and 'no-store' in r.headers.get('Cache-Control',''),
                'Both public user routes must remain sealed')

def assert_asset_rights(iam):
    role=f'arn:aws:iam::{ACCOUNT}:role/{PREFIX}ai'
    checks=(('s3:GetObject','private/furniture-generation/check',True),
            ('s3:PutObject','private/furniture-generation/check',True),
            ('s3:GetObject','items/check',True),('s3:PutObject','items/photo-furniture/furniture/check',True),
            ('s3:PutObject','items/check',False),('s3:GetObject','private/chat/check',False),
            ('s3:PutObject','private/bugs/check',False),('s3:DeleteObject','items/photo-furniture/furniture/check',False))
    for action,key,allowed in checks:
        result=iam.simulate_principal_policy(PolicySourceArn=role,ActionNames=[action],ResourceArns=[f'arn:aws:s3:::{ASSETS}/{key}'])
        row,=result['EvaluationResults']
        require((row['EvalDecision']=='allowed')==allowed,'Unexpected worker asset permissions')

def run(s,mode):
    require(s.client('sts').get_caller_identity()['Account']==ACCOUNT and s.region_name==REGION,'Wrong AWS account or region')
    require(mode in ('furniture-prepare','furniture-enable','furniture-pause'),'Unexpected operation')
    lm=s.client('lambda')
    if mode=='furniture-pause':
        status=mapping(lm)
        require(status in ('Enabled','Disabled','Disabling','Enabling'),'Unexpected consumer state')
        if status=='Enabling':wait(lm,'Enabled')
        if mapping(lm)=='Enabled':lm.update_event_source_mapping(UUID=MAPPING,Enabled=False)
        wait(lm,'Disabled')
        # Already running invocations or leases must be accounted for separately during rollback.
        return dict(operation=mode,consumer='Disabled',requiresDrainAndReconciliation=True)
    status=mapping(lm)
    if mode=='furniture-prepare':
        public_health('dkfiwkal2ezg9.cloudfront.net',False)
        require(status=='Disabled','Prepare requires a disconnected personal consumer')
    else:
        for host in ('dkfiwkal2ezg9.cloudfront.net','43.203.93.12'):
            public_health(host,True)
            sealed_origin(host)
        require(status in ('Disabled','Enabled','Enabling'),'Unexpected personal consumer state')
    empty(s.client('sqs'))
    assert_asset_rights(s.client('iam'))
    value=s.client('ssm').get_parameter(Name=PARAMETER,WithDecryption=True)['Parameter']
    require(value['Type']=='SecureString' and value['Name']==PARAMETER,'Unexpected runtime secret')
    for kind in ('control','ai'):
        before=function(lm,kind)
        env=before['Environment']['Variables']
        new=desired(kind,env,PRIVATE_ADDRESS,value['Value'])
        if env!=new:
            require(mode=='furniture-prepare' and mapping(lm)=='Disabled','Prepare disconnected workers first')
            lm.update_function_configuration(FunctionName=PREFIX+kind,RevisionId=before['RevisionId'],Environment={'Variables':new})
            lm.get_waiter('function_updated_v2').wait(FunctionName=PREFIX+kind)
        verified=function(lm,kind)['Environment']['Variables']
        require(verified.keys()==new.keys() and all(hmac.compare_digest(verified[k].encode(),v.encode()) for k,v in new.items()),
                'Runtime readback failed')
    if mode=='furniture-enable':
        empty(s.client('sqs'))
        if mapping(lm)=='Disabled':lm.update_event_source_mapping(UUID=MAPPING,Enabled=True)
        wait(lm,'Enabled')
    else:require(mapping(lm)=='Disabled','Personal consumer changed during preparation')
    return dict(operation=mode,consumer=mapping(lm),codeMatched=True,assetBucketMatched=True,
                privateSqlVerifiedByConfiguration=True,cloudSecretMatched=True)

def wait(lm,expected):
    for _ in range(100):
        if mapping(lm)==expected:return
        time.sleep(3)
    raise TimeoutError('Queue consumer transition pending')

if __name__=='__main__':
    result=run(boto3.Session(region_name=REGION),os.environ['CANDIDATE_MODE'])
    Path('furniture-runtime-receipt.json').write_text(json.dumps(result,indent=2))
