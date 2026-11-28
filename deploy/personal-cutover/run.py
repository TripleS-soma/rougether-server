"""Invoke only a content-addressed deployment document on the personal managed node."""
import hashlib
import json
import os
from pathlib import Path
import re
import time
import boto3
from botocore.config import Config
from documents import ACCOUNT, REGION, BUCKET, NODE, INSTANCE, document

def main():
    aws = boto3.Session(region_name=REGION)
    assert aws.client('sts').get_caller_identity()['Account']==ACCOUNT
    instance=aws.client('lightsail').get_instance(instanceName=INSTANCE)['instance']
    assert instance['state']['name']=='running' and instance['publicIpAddress']=='43.203.93.12'
    assert {v['key']:v['value'] for v in instance['tags']}.get('Project')=='Rougether'
    ssm=aws.client('ssm')
    node,=ssm.describe_instance_information(Filters=[{'Key':'InstanceIds','Values':[NODE]}])['InstanceInformationList']
    assert node['PingStatus']=='Online' and node['IPAddress']==instance['privateIpAddress']
    mode=os.environ['CANDIDATE_MODE']
    name,raw=document(mode,instance['supportCode'].split('/')[-1])
    stored=ssm.get_document(Name=name,DocumentVersion='1',DocumentFormat='JSON')
    assert json.loads(stored['Content'])==json.loads(raw)
    params={}
    if mode in ('restore','fixture','release-config'):
        snap=os.environ['SNAPSHOT'];digest=os.environ['DIGEST']
        assert re.fullmatch(r'[a-f0-9]{64}',digest)
        if mode=='release-config':
            assert not snap
            snap='config'
        else:
            assert re.fullmatch(r'[0-9]{8}-[0-9]{4}',snap)
        client=aws.client('s3',config=Config(signature_version='s3v4',s3={'addressing_style':'virtual'}))
        key=f'restore/{snap}/{digest}.cms'
        head=client.head_object(Bucket=BUCKET,Key=key,ExpectedBucketOwner=ACCOUNT)
        assert head['ContentLength']>0 and head['Metadata'].get('sha256')==digest
        url=client.generate_presigned_url('get_object',Params={'Bucket':BUCKET,'Key':key},ExpiresIn=900)
        print('::add-mask::'+url,flush=True)
        params={'Envelope':[url],'Digest':[digest]}
        if mode!='release-config':params['Snapshot']=[snap]
    if mode in ('clone-sealed-db','prepare-db-users'):
        snap=os.environ['SNAPSHOT'];digest=os.environ['DIGEST']
        assert re.fullmatch(r'[0-9]{8}-[0-9]{4}',snap) and re.fullmatch(r'[a-f0-9]{64}',digest)
        params={'Snapshot':[snap],'Digest':[digest]}
        if mode=='prepare-db-users':
            config=os.environ['CONFIG_DIGEST']
            assert re.fullmatch(r'[a-f0-9]{64}',config)
            params['ConfigDigest']=[config]
    if mode=='prepare-release':
        config=os.environ['CONFIG_DIGEST']
        assert re.fullmatch(r'[a-f0-9]{64}',config)
        params={'ConfigDigest':[config]}
    if mode=='runtime-check':
        stack=aws.client('cloudformation').describe_stacks(StackName='rougether-personal-runtime-identity')['Stacks'][0]
        assert stack['StackStatus'] in ('CREATE_COMPLETE','UPDATE_COMPLETE')
        outputs={row['OutputKey']:row['OutputValue'] for row in stack['Outputs']}
        for key,kind in [('Anchor','trust-anchor'),('UserProfile','profile'),('AdminProfile','profile')]:
            arn=outputs[key+'Arn']
            assert arn.startswith(f'arn:aws:rolesanywhere:{REGION}:{ACCOUNT}:{kind}/')
            params[key+'Id']=[arn.split('/')[-1]]
    command=ssm.send_command(InstanceIds=[NODE],DocumentName=name,DocumentVersion='1',Parameters=params,TimeoutSeconds=120,
            Comment='Verified personal AWS candidate '+mode)['Command']['CommandId']
    print('Deployment command',command,flush=True)
    for _ in range(430):
        try: result=ssm.get_command_invocation(CommandId=command,InstanceId=NODE)
        except ssm.exceptions.InvocationDoesNotExist:
            time.sleep(4);continue
        status=result['Status']
        if status=='Success':break
        if status not in ('Pending','InProgress','Delayed'):
            raise RuntimeError('Candidate command '+status+'; inspect restricted execution record')
        time.sleep(4)
    else:raise RuntimeError('Candidate timed out')
    out=result['StandardOutputContent'].strip()
    if mode in ('enroll','runtime-enroll'):
        assert re.fullmatch(r'-----BEGIN CERTIFICATE-----\n[A-Za-z0-9+/=\n]+\n-----END CERTIFICATE-----',out)
        certificate = 'transport-cert.pem' if mode=='enroll' else 'runtime-ca.crt'
        Path(certificate).write_text(out+'\n')
    elif mode=='release-config':
        receipt=json.loads(out)
        assert receipt=={'sealedConfig':True,'connected':False,'services':3,'privateTls':True,'digest':digest}
        Path('release-config-receipt.json').write_text(json.dumps(receipt,indent=2))
    elif mode=='prepare-release':
        receipt=json.loads(out)
        assert re.fullmatch(r'[a-f0-9]{64}',receipt['bundle'])
        assert receipt==dict(prepared=True,bundle=receipt['bundle'],config=config,services=6,productionWritersStarted=0)
        Path('prepared-release-receipt.json').write_text(json.dumps(receipt,indent=2))
    elif mode=='prepare-db-users':
        receipt=json.loads(out)
        assert receipt==dict(snapshot=snap,digest=digest,config=config,tlsLogins=2,schemaReadOnly=True,globalReadOnly=True,network='none',checkedGrants=True)
        Path('database-users-receipt.json').write_text(json.dumps(receipt,indent=2))
    elif mode=='clone-sealed-db':
        receipt=json.loads(out)
        assert receipt['readOnly'] and receipt['allTableCountsMatched'] and receipt['binlogEnabled'] and receipt['zeroPublicPorts']
        Path('sealed-copy-receipt.json').write_text(json.dumps(receipt,indent=2))
    elif mode=='renewal-check':
        receipt=json.loads(out)
        assert receipt['firstRequestsVerified'] and {receipt['user'],receipt['admin']} <= {'running','verified'}
        Path('renewal-receipt.json').write_text(json.dumps(receipt,indent=2))
    elif mode=='standby-check':
        receipt=json.loads(out)
        assert receipt['synthetic'] and receipt['cleanup'] and receipt['writeRejectedAfterRestart']
        assert receipt['userHealth']==receipt['adminHealth']==200 and receipt['flywayLatest']=='109'
        Path('standby-receipt.json').write_text(json.dumps(receipt,indent=2))
    elif mode=='runtime-check':
        receipt=json.loads(out)
        assert receipt['runtimeIdentity'] and receipt['adminMediaDeleteDenied'] and receipt['crossRoleDenied']
        Path('runtime-receipt.json').write_text(json.dumps(receipt,indent=2))
    elif mode=='restore':
        receipt=json.loads(out)
        assert receipt['network']=='none' and receipt['readOnly'] and receipt['orphanViolations']==0
        Path('restore-receipt.json').write_text(json.dumps(receipt,indent=2))
    else:
        receipt=json.loads(out)
        assert receipt['fixture'] is True
        Path('fixture-receipt.json').write_text(json.dumps(receipt,indent=2))
if __name__=='__main__':main()
