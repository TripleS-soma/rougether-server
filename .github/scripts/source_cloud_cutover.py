"""Operate only the inventoried source furniture workers and source object-write fence."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid

ACCOUNT = '776158585524'
REGION = 'ap-northeast-2'
BUCKET = 'rougether-assets-isb-' + ACCOUNT
MAPPING = 'c15b7482-b106-4b24-b436-cff8196afbf1'
WORKERS = {'rougether-dev-furniture-ai': 50, 'rougether-dev-furniture-control': 16}
ORIGINAL = '66e8118792a8dcf2390d515d260b24bcf3de72686eac5b6c94bfbcc9c581cb38'
SEALED = 'c80ea9a664bdaff64a91b62f56d2795ce46f8c964fe44838e446df8f38f07d23'
FENCE = {'Sid':'RougetherCutoverFreezeObjectWrites','Effect':'Deny','Principal':'*',
         'Action':['s3:PutObject','s3:DeleteObject','s3:DeleteObjectVersion','s3:PutObjectTagging',
                   's3:PutObjectVersionTagging','s3:DeleteObjectTagging','s3:DeleteObjectVersionTagging',
                   's3:PutObjectAcl','s3:PutObjectVersionAcl','s3:RestoreObject','s3:ReplicateObject',
                   's3:ReplicateDelete','s3:ReplicateTags','s3:PutObjectRetention','s3:PutObjectLegalHold'],
         'Resource':'arn:aws:s3:::'+BUCKET+'/*'}


def digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()


def policy_for(previous, seal):
    if digest(previous) not in (ORIGINAL, SEALED):
        raise RuntimeError('Source bucket policy changed since review')
    candidate = copy.deepcopy(previous)
    candidate['Statement'] = [v for v in candidate['Statement'] if v.get('Sid') != FENCE['Sid']]
    if seal:
        candidate['Statement'].append(FENCE)
    if digest(candidate) != (SEALED if seal else ORIGINAL):
        raise RuntimeError('Unexpected proposed source bucket policy')
    return candidate


def current_ingress():
    url = 'https://dkfiwkal2ezg9.cloudfront.net/api/v1/health?cutover=' + uuid.uuid4().hex
    try:
        response = urllib.request.urlopen(urllib.request.Request(url, headers={'Cache-Control':'no-cache'}),timeout=9)
    except urllib.error.HTTPError as exc:
        response = exc
    with response:
        if response.status == 503 and response.headers.get('X-Rougether-Cutover') == 'source-held':
            return 'held'
        if response.status == 200 and response.headers.get('X-Rougether-Furniture-Intake') == 'closed':
            return 'closed'
    raise RuntimeError('Source intake is not closed')


class SourceControls:
    def __init__(self, session):
        assert session.region_name == REGION and session.client('sts').get_caller_identity()['Account'] == ACCOUNT
        self.lm = session.client('lambda')
        self.sqs = session.client('sqs')
        self.s3 = session.client('s3')
        self.bucket = dict(Bucket=BUCKET,ExpectedBucketOwner=ACCOUNT)

    def queue_counts(self):
        counts = {}
        attributes = ['ApproximateNumberOfMessages','ApproximateNumberOfMessagesNotVisible','ApproximateNumberOfMessagesDelayed']
        for kind in ('jobs','dlq'):
            url = f'https://sqs.{REGION}.amazonaws.com/{ACCOUNT}/rougether-dev-furniture-{kind}'
            values = self.sqs.get_queue_attributes(QueueUrl=url,AttributeNames=attributes)['Attributes']
            counts[kind]={key:int(values[key]) for key in attributes}
        return counts

    def empty_queues(self):
        if any(number for queue in self.queue_counts().values() for number in queue.values()):
            raise RuntimeError('Pending furniture work remains. Keep the dispatcher running.')

    def mapping(self):
        result = self.lm.get_event_source_mapping(UUID=MAPPING)
        if result['FunctionArn'] != f'arn:aws:lambda:{REGION}:{ACCOUNT}:function:rougether-dev-furniture-ai' or result['EventSourceArn'] != f'arn:aws:sqs:{REGION}:{ACCOUNT}:rougether-dev-furniture-jobs':
            raise RuntimeError('Unexpected source event mapping')
        return result['State']

    def change_mapping(self, enabled):
        wanted = 'Enabled' if enabled else 'Disabled'
        for _ in range(60):
            state = self.mapping()
            if state == wanted:
                return
            if state in ('Enabled','Disabled'):
                self.lm.update_event_source_mapping(UUID=MAPPING,Enabled=enabled)
            elif state not in ('Disabling','Enabling','Updating'):
                raise RuntimeError('Unexpected source event mapping state')
            time.sleep(3)
        raise RuntimeError('Event mapping did not settle')

    def limits(self):
        limits = {fn:self.lm.get_function_concurrency(FunctionName=fn).get('ReservedConcurrentExecutions') for fn in WORKERS}
        if any(limit not in (0, WORKERS[fn]) for fn,limit in limits.items()):
            raise RuntimeError('Unexpected source worker concurrency')
        return limits

    def set_limits(self, sealed):
        current = self.limits()
        for fn, original in WORKERS.items():
            requested = 0 if sealed else original
            if current[fn] != requested:
                self.lm.put_function_concurrency(FunctionName=fn,ReservedConcurrentExecutions=requested)
        if self.limits() != {fn:0 if sealed else previous for fn,previous in WORKERS.items()}:
            raise RuntimeError('Source worker concurrency did not settle')

    def read_policy(self):
        return json.loads(self.s3.get_bucket_policy(**self.bucket)['Policy'])

    def upload_count(self):
        pages=self.s3.get_paginator('list_multipart_uploads').paginate(**self.bucket)
        return sum(len(page.get('Uploads', [])) for page in pages)

    def set_policy(self, seal):
        current = self.read_policy()
        proposed = policy_for(current, seal)
        if current != proposed:
            self.s3.put_bucket_policy(**self.bucket,Policy=json.dumps(proposed))
        if digest(self.read_policy()) != digest(proposed):
            raise RuntimeError('Source policy readback differs')

    def apply(self, mode, *, ingress=current_ingress, settled=False, target_sealed=False):
        policy=self.read_policy()
        policy_for(policy, True)
        limits=self.limits()
        mapping=self.mapping()
        if mode == 'inspect-cloud':
            return {'mapping':mapping,'concurrency':limits,'policyDigest':digest(policy),'multipart':self.upload_count(),'queues':self.queue_counts()}
        route = ingress()
        if mode == 'stop-workers':
            if route not in ('closed','held') or not settled:
                raise RuntimeError('Close intake and verify settlement before stopping the workers')
            self.empty_queues()
            self.change_mapping(False)
            self.empty_queues()
            self.set_limits(True)
            # A running invocation retains its time budget after concurrency is set to zero.
            # Keep general user traffic available during this interval when intake alone is closed.
            maximum = max(self.lm.get_function_configuration(FunctionName=fn)['Timeout'] for fn in WORKERS)
            if not 1 <= maximum <= 900:
                raise RuntimeError('Unexpected function timeout')
            time.sleep(maximum + 5)
            if self.mapping() != 'Disabled' or any(self.limits().values()):
                raise RuntimeError('Source function fence changed during drain')
            self.empty_queues()
        elif mode == 'seal-assets':
            if route != 'held' or mapping != 'Disabled' or any(limits.values()) or self.upload_count():
                raise RuntimeError('Hold the original API, drain functions and finish uploads first')
            self.empty_queues()
            self.set_policy(True)
            if self.upload_count():
                raise RuntimeError('Incomplete upload detected after source policy was sealed')
        elif mode == 'restore-cloud':
            if not target_sealed or (route != 'held' and not (route == 'closed' and digest(policy) == ORIGINAL)):
                raise RuntimeError('A held source and reconciled, sealed target are required')
            self.set_policy(False)
            self.set_limits(False)
            self.change_mapping(True)
        else:
            raise ValueError('Unexpected mode')
        return self.apply('inspect-cloud')


if __name__ == '__main__':
    import boto3
    parser=argparse.ArgumentParser()
    parser.add_argument('mode',choices=['inspect-cloud','stop-workers','seal-assets','restore-cloud'])
    args=parser.parse_args()
    controls=SourceControls(boto3.Session(region_name=REGION))
    if args.mode!='inspect-cloud':
        assert os.environ.get('GITHUB_REF')=='refs/heads/main' and os.environ.get('GITHUB_EVENT_NAME')=='workflow_dispatch'
    receipt=controls.apply(args.mode,settled=os.environ.get('SOURCE_SETTLED')=='true',target_sealed=os.environ.get('TARGET_SEALED')=='true')
    print(json.dumps(receipt,sort_keys=True))
