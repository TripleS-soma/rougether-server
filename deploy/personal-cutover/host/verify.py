import collections
import hashlib
import json
import re
import subprocess
import sys

name, metadata = sys.argv[1:]
expected = json.load(open(metadata))
def query(sql):
    return subprocess.check_output(['docker', 'exec', '-i', name, 'mysql', '--defaults-extra-file=/run/private/client.cnf', '-N', '-B', 'rougether'], input=sql.encode(), stderr=subprocess.DEVNULL)
def valid(identifier):
    if not re.fullmatch(r'[A-Za-z0-9_]+', identifier):
        raise ValueError('unexpected database identifier')
    return '`' + identifier + '`'
tables = query("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name").decode().splitlines()
counts = dict(zip(tables, map(int, query(' UNION ALL '.join('SELECT COUNT(*) FROM '+valid(t) for t in tables)).splitlines())))
history = query("SELECT installed_rank,version,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank")
groups = collections.defaultdict(list)
for row in query("SELECT constraint_name,table_name,column_name,referenced_table_name,referenced_column_name FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() AND referenced_table_name IS NOT NULL ORDER BY table_name,constraint_name,ordinal_position").decode().splitlines():
    key, table, column, parent, ref = row.split('\t')
    groups[key, table, parent].append((valid(column), valid(ref)))
checks = []
for (_, table, parent), pairs in groups.items():
    present = ' AND '.join('c.'+c+' IS NOT NULL' for c,_ in pairs)
    join = ' AND '.join('c.'+c+'=p.'+r for c,r in pairs)
    checks.append(f'SELECT EXISTS(SELECT 1 FROM {valid(table)} c WHERE {present} AND NOT EXISTS(SELECT 1 FROM {valid(parent)} p WHERE {join}))')
assert sum(map(int,query(' UNION ALL '.join(checks)).splitlines())) == 0
assert len(groups)==expected['fk'] and len(tables)==expected['tables']
assert sum(counts.values())==expected['total'] and all(counts[k]==v for k,v in expected['core'].items())
if 'nonemptyCounts' in expected:
    assert {table:count for table,count in counts.items() if count} == expected['nonemptyCounts']
routines = int(query("SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE()"))
assert routines == expected.get('routines',2)
assert hashlib.sha256(history).hexdigest()==expected['flyway']
receipt=dict(candidate=name,tables=len(tables),rows=sum(counts.values()),foreignKeys=len(groups),orphanViolations=0,flywayMatched=True,allTableCountsMatched='nonemptyCounts' in expected,routines=routines,readOnly=True,network='none')
if 'frozenSource' in expected:
    source=expected['frozenSource']
    assert source['sourceAccount']=='776158585524' and source['schema']=='rougether'
    assert re.fullmatch(r'[0-9]{8}-[0-9]{4}',source['snapshot']) and '-'+source['snapshot']+'-' in name
    assert all(re.fullmatch(r'[a-f0-9]{64}',source[k]) for k in ('countDigest','sourceDumpSHA256','objectInventorySHA256'))
    assert hashlib.sha256(json.dumps(counts,sort_keys=True,separators=(',',':')).encode()).hexdigest()==source['countDigest']
    receipt['frozenSource']=source
print(json.dumps(receipt))
