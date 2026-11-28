"""Generate a private TCP listener. The public gateway supplies only reviewed Caddy files."""
import argparse
import ipaddress
from pathlib import Path

CLIENTS = ['172.29.242.2','172.29.242.3','172.29.242.4']


def render(directory, host, lambda_addresses):
    private=ipaddress.IPv4Address(host)
    if not any(private in ipaddress.IPv4Network(network) for network in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16')):
        raise ValueError('Expected the Lightsail private address')
    allowed=sorted({ipaddress.IPv4Address(ip) for ip in lambda_addresses})
    if not 1 <= len(allowed) <= 16 or any(ip not in ipaddress.IPv4Network('172.31.0.0/16') for ip in allowed):
        raise ValueError('Unexpected Lambda interfaces')
    if private in allowed or private in map(ipaddress.IPv4Address,CLIENTS):
        raise ValueError('Listener and client addresses overlap')
    directory.mkdir(mode=0o700,exist_ok=False)
    text=f"""global
  maxconn 128
defaults
  mode tcp
  timeout connect 4s
  timeout client 15m
  timeout server 15m
frontend private_database
  bind {private}:3306
  tcp-request connection reject if !{{ src {' '.join(CLIENTS+[str(ip) for ip in allowed])} }}
  default_backend release_database
backend release_database
  server mysql 172.29.243.2:3306
"""
    config=directory/'haproxy.cfg'
    config.write_text(text)
    config.chmod(0o444)
    gate=Path(__file__).resolve().parent.parent/'gateway'
    (directory/'Caddyfile').write_text((gate/'target.Caddyfile').read_text()+'\nimport /etc/caddy/admin.Caddyfile\n')
    (directory/'admin.Caddyfile').write_text((gate/'admin.Caddyfile').read_text())


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('directory',type=Path)
    parser.add_argument('private_ip')
    parser.add_argument('lambda_ips',nargs='+')
    args=parser.parse_args()
    render(args.directory,args.private_ip,args.lambda_ips)
