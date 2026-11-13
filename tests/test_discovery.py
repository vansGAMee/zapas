import json
import socket

from zapas.discovery import Discovery, PROBE


def test_real_udp_discovery_does_not_send_secrets():
    discovery = Discovery(19473, 'a' * 32, udp_port=0)
    discovery.start()
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
            client.settimeout(2)
            client.sendto(PROBE, ('127.0.0.1', discovery.socket.getsockname()[1]))
            data, _ = client.recvfrom(1024)
            assert json.loads(data) == {'service':'zapas', 'version':1, 'port':19473, 'server_id':'a'*32}
    finally:
        discovery.close()
