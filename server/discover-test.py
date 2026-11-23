#!/usr/bin/env python3
"""Check discovery from a PC the same way a TV does: broadcast and print the replies.

    python3 server/discover-test.py            # broadcast to 255.255.255.255
    python3 server/discover-test.py 127.0.0.1  # ask one host directly
"""

import json
import socket
import sys
import time
import uuid

PORT = 8555

targets = sys.argv[1:] or ["255.255.255.255"]
nonce = uuid.uuid4().hex
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
sock.settimeout(0.3)

start = time.monotonic()
for host in targets:
    sock.sendto(json.dumps({"type": "discover", "v": 1, "nonce": nonce, "t": 0}).encode(), (host, PORT))

found = 0
while time.monotonic() - start < 1.5:
    try:
        data, (ip, _) = sock.recvfrom(4096)
    except socket.timeout:
        continue
    msg = json.loads(data)
    if msg.get("nonce") != nonce:
        continue
    rtt = (time.monotonic() - start) * 1000
    found += 1
    print(f"{msg['name']} ({msg['id']}) at {ip}, {rtt:.1f} ms")
    for src in msg["sources"]:
        state = {True: "live", False: "offline", None: "unknown"}[src["ready"]]
        print(f"  rtsp://{ip}:{msg['rtsp_port']}/{src['path']}  [{state}] {', '.join(src['tracks'])}"
              f"  readers={src['readers']}")

if not found:
    print("No servers answered. Is discovery.py running and UDP 8555 allowed in the firewall?")
    sys.exit(1)
