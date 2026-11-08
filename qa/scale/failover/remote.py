"""격리된 Fargate 발생기에서만 실행한다."""

import os
import re
import sys

import MySQLdb
import workload

env = os.environ


def db(use_schema=True):
    assert (
        env["DB_HOST"].startswith("rougether-scale-test.")
        and env["DB_INSTANCE_ID"] == "rougether-scale-test"
    )
    assert re.fullmatch(r"lossqa_[0-9]{10}", env["SCHEMA"])
    args = dict(
        host=env["DB_HOST"],
        user=env["DB_USER"],
        passwd=env["DB_PASSWORD"],
        autocommit=True,
        ssl={"ca": "/opt/certs/global-bundle.pem"},
        ssl_mode="VERIFY_IDENTITY",
        connect_timeout=10,
    )
    if use_schema:
        args["db"] = env["SCHEMA"]
    return MySQLdb.connect(**args)


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode in ("create", "drop"):
        with db(False) as conn, conn.cursor() as cur:
            ddl = "CREATE DATABASE" if mode == "create" else "DROP DATABASE"
            cur.execute(f"{ddl} `{env['SCHEMA']}`")
    elif mode == "seed":
        workload.seed(db)
    elif mode == "load":
        workload.run(db)
    else:
        raise ValueError(mode)
