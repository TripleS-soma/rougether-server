"""Check the fully merged production topology before the target host may stage it."""
import json
import sys


def check(plan):
    services = plan["services"]
    if set(services) != {"mysql","db-proxy","user-api","admin-api","batch","gateway"}:
        raise ValueError("Unexpected service topology")
    if {s for s, spec in services.items() if spec.get("ports")} != {"gateway"}:
        raise ValueError("Unexpected published service")
    if not plan["networks"]["default"]["internal"] or set(services["mysql"]["networks"]) != {"default"}:
        raise ValueError("Database network is not isolated")
    if services["db-proxy"].get("network_mode") != "host" or services["batch"].get("profiles") != ["writer"]:
        raise ValueError("Unexpected private listener or batch profile")
    for service, final_octet in [("user-api",2), ("admin-api",3), ("batch",4)]:
        if services[service]["networks"] != {"clients":{"ipv4_address":f"172.29.242.{final_octet}"}}:
            raise ValueError("Unexpected application address")
    ports = {(p["host_ip"], str(p["published"]), p["target"], p["protocol"]) for p in services["gateway"]["ports"]}
    if ports != {("0.0.0.0","443",443,"tcp"),("0.0.0.0","80",80,"tcp"),("127.0.0.1","8443",8443,"tcp")}:
        raise ValueError("Unexpected gateway exposure")
    if services["gateway"]["environment"]["CUTOVER_MODE"] != "sealed_api":
        raise ValueError("Readiness gateway expected")


if __name__ == "__main__":
    check(json.load(sys.stdin))
