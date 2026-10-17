"""일회용 Neo4j와 합성 모델로 Graphiti를 검증함. --container는 호스트 포트 전달에 의존하지 않음."""
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import uuid

from neo4j import GraphDatabase

root = Path(__file__).resolve().parents[2]
name = "rougether-graphiti-test-" + uuid.uuid4().hex[:8]
network = name + "-net"
runner = name + "-runner"
password = secrets.token_urlsafe(24)
containerized = "--container" in sys.argv
env = {**os.environ, "NEO4J_AUTH": "neo4j/" + password,
       "AI_USER_TEST_NEO4J_PASSWORD": password}
try:
    if containerized:
        subprocess.run(["docker","network","create","--internal",network],check=True,capture_output=True,timeout=20)
    address_args = ["--network",network] if containerized else ["-p","127.0.0.1::7687"]
    created = subprocess.run(["docker", "run", "-d", "--rm", "--name", name,
        "--memory=1536m", "--cpus=1", *address_args, "-e", "NEO4J_AUTH",
        "-e", "NEO4J_dbms_usage__report_enabled=false",
        "-e", "NEO4J_server_memory_heap_initial__size=256m", "-e", "NEO4J_server_memory_heap_max__size=512m",
        "-e", "NEO4J_server_memory_pagecache_size=256m", "neo4j:5.26-community"],
        env=env,capture_output=True,text=True,timeout=180)
    if created.returncode:
        raise SystemExit("격리 Neo4j 컨테이너를 시작하지 못함")
    if containerized:
        script = """
import os,time,subprocess,sys
from neo4j import GraphDatabase
with GraphDatabase.driver(os.environ["AI_USER_TEST_NEO4J_URI"],auth=("neo4j",os.environ["AI_USER_TEST_NEO4J_PASSWORD"]),connection_timeout=2) as driver:
    deadline=time.monotonic()+120
    while True:
        try:
            driver.verify_connectivity()
            break
        except Exception:
            if time.monotonic()>deadline: raise SystemExit("Neo4j startup timeout")
            time.sleep(1)
raise SystemExit(subprocess.run([sys.executable,"-m","pytest","-c","/app/pyproject.toml","/tests/test_graph_memory_integration.py","-q","-p","no:cacheprovider"]).returncode)
"""
        result = subprocess.run(["docker","run","--rm","--name",runner,"--network",network,
            "--memory=2g","--cpus=1","--read-only","--tmpfs","/tmp:size=32m",
            "-v",str(root/"ai-service/tests")+":/tests:ro",
            "-v",str(root/"ai-service/rougether_ai")+":/app/rougether_ai:ro",
            "-e","AI_USER_TEST_NEO4J_URI=bolt://"+name+":7687",
            "-e","AI_USER_TEST_NEO4J_PASSWORD","-e","PYTHONPATH=/app",
            "rougether-ai:graphiti-contract-test","python","-c",script],env=env,timeout=480)
    else:
        address = subprocess.check_output(["docker","port",name,"7687"],text=True,timeout=15).strip()
        uri = "bolt://" + address
        with GraphDatabase.driver(uri,auth=("neo4j",password),connection_timeout=2) as driver:
            deadline=time.monotonic()+120
            while True:
                try:
                    driver.verify_connectivity()
                    break
                except Exception:
                    if time.monotonic()>deadline:
                        raise SystemExit("격리 Neo4j 기동 제한 시간 초과")
                    time.sleep(1)
        result = subprocess.run([sys.executable,"-m","pytest","-c","ai-service/pyproject.toml","ai-service/tests/test_graph_memory_integration.py","-q","-p","no:cacheprovider"],
            cwd=root,env={**os.environ,"AI_USER_TEST_NEO4J_URI":uri,"AI_USER_TEST_NEO4J_PASSWORD":password},timeout=480)
    raise SystemExit(result.returncode)
finally:
    for command in (["docker","rm","-f",runner,name],["docker","network","rm",network] if containerized else []):
        if not command: continue
        try:
            subprocess.run(command,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=15)
        except subprocess.TimeoutExpired:
            print("Docker 정리 상태를 확인할 수 없음: "+name,file=sys.stderr)
