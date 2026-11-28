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
    if mode in ('restore','fixture'):
        snap=os.environ['SNAPSHOT'];digest=os.environ['DIGEST']
        assert re.fullmatch(r'[0-9]{8}-[0-9]{4}',snap) and re.fullmatch(r'[a-f0-9]{64}',digest)
        client=aws.client('s3',config=Config(signature_version='s3v4',s3={'addressing_style':'virtual'}))
        key=f'restore/{snap}/{digest}.cms'
        head=client.head_object(Bucket=BUCKET,Key=key,ExpectedBucketOwner=ACCOUNT)
        assert head['ContentLength']>0 and head['Metadata'].get('sha256')==digest
        url=client.generate_presigned_url('get_object',Params={'Bucket':BUCKET,'Key':key},ExpiresIn=900)
        print('::add-mask::'+url,flush=True)
        params={'Envelope':[url],'Snapshot':[snap],'Digest':[digest]}
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
    elif mode=='restore':
        receipt=json.loads(out)
        assert receipt['network']=='none' and receipt['readOnly'] and receipt['orphanViolations']==0
        Path('restore-receipt.json').write_text(json.dumps(receipt,indent=2))
    else:
        receipt=json.loads(out)
        assert receipt['fixture'] is True
        Path('fixture-receipt.json').write_text(json.dumps(receipt,indent=2))
if __name__=='__main__':main()
