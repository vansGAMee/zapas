"""Small LAN-only discovery responder. No credentials or library leave it."""
import ipaddress
import json
import logging
import socket
import threading
import time

DISCOVERY_PORT = 9474
PROBE = b'ZAPAS_DISCOVER_V1'


class Discovery:
    def __init__(self, http_port, server_id, udp_port=DISCOVERY_PORT):
        self.http_port, self.server_id, self.udp_port = http_port, server_id, udp_port
        self.socket = None
        self.thread = None
        self.closed = threading.Event()

    def start(self):
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.bind(('0.0.0.0', self.udp_port))
            sock.settimeout(.5)
            self.socket = sock
            self.thread = threading.Thread(target=self.run, daemon=True, name='zapas-discovery')
            self.thread.start()
        except OSError:
            sock.close()
            logging.getLogger(__name__).exception('LAN discovery unavailable; direct pairing still works')

    def run(self):
        last_reply = {}
        payload = json.dumps({'service': 'zapas', 'version': 1, 'port': self.http_port,
                              'server_id': self.server_id}, separators=(',', ':')).encode()
        while not self.closed.is_set():
            try:
                data, peer = self.socket.recvfrom(128)
                ip = ipaddress.ip_address(peer[0])
                if data != PROBE or not (ip.is_private or ip.is_loopback):
                    continue
                now = time.monotonic()
                if now - last_reply.get(peer[0], 0) < .5:
                    continue
                if len(last_reply) > 256:
                    last_reply.clear()
                last_reply[peer[0]] = now
                self.socket.sendto(payload, peer)
            except socket.timeout:
                continue
            except OSError:
                break

    def close(self):
        self.closed.set()
        if self.socket:
            self.socket.close()
        if self.thread:
            self.thread.join(timeout=2)
