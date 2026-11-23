#!/usr/bin/env python3
"""LAN discovery and ping responder for the MediaMTX streams on this PC.

TV clients broadcast {"type": "discover"} to UDP port 8555; this service answers
with the list of live MediaMTX paths. Clients build the RTSP URL from the IP the
reply came from, so the server does not need to know its own address.
{"type": "ping"} is answered with "pong" and is used for the RTT shown on the TVs.

Python 3.8+, standard library only. Run next to MediaMTX:

    python3 server/discovery.py --name "Зал 1"
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import socket
import time
import urllib.request
import uuid
from pathlib import Path

PROTOCOL_VERSION = 1
DEFAULT_PORT = 8555
MAX_PACKET = 2048

log = logging.getLogger("discovery")


def load_server_id(path: Path) -> str:
    """Stable id so a TV can find "its" server again after the PC gets a new IP."""
    try:
        value = path.read_text(encoding="utf-8").strip()
        if value:
            return value
    except FileNotFoundError:
        pass
    value = uuid.uuid4().hex[:12]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8")
    return value


class PathCache:
    """Polls the MediaMTX API at most once per `ttl` seconds."""

    def __init__(self, api_url: str, fallback_path: str, ttl: float = 1.0) -> None:
        self.api_url = api_url.rstrip("/") + "/v3/paths/list"
        self.fallback_path = fallback_path
        self.ttl = ttl
        self._at = 0.0
        self._value: list[dict] = []
        self._api_ok = False

    def get(self) -> list[dict]:
        now = time.monotonic()
        if now - self._at < self.ttl:
            return self._value
        self._at = now
        try:
            with urllib.request.urlopen(self.api_url, timeout=0.5) as resp:
                items = json.load(resp).get("items", [])
            self._value = [
                {
                    "path": item["name"],
                    "ready": bool(item.get("ready")),
                    "tracks": item.get("tracks", []),
                    "readers": len(item.get("readers") or []),
                    **_video_size(item),
                }
                for item in items
            ]
            if not self._api_ok:
                log.info("MediaMTX API reachable at %s", self.api_url)
            self._api_ok = True
        except Exception as exc:  # noqa: BLE001 - any API problem falls back to the static path
            if self._api_ok or not self._value:
                log.warning("MediaMTX API unavailable (%s), announcing '%s' only", exc, self.fallback_path)
            self._api_ok = False
            self._value = [{"path": self.fallback_path, "ready": None, "tracks": [], "readers": 0}]
        return self._value


def _video_size(item: dict) -> dict:
    for track in item.get("tracks2") or []:
        props = track.get("codecProps") or {}
        if "width" in props and "height" in props:
            return {"width": props["width"], "height": props["height"]}
    return {}


def serve(args: argparse.Namespace) -> None:
    server_id = load_server_id(Path(args.id_file).expanduser())
    paths = PathCache(args.api, args.path)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((args.bind, args.port))
    log.info("Server '%s' (id %s) listening on UDP %s:%d, RTSP port %d",
             args.name, server_id, args.bind or "*", args.port, args.rtsp_port)

    while True:
        try:
            data, addr = sock.recvfrom(MAX_PACKET)
            msg = json.loads(data.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            continue
        except OSError as exc:
            log.warning("recv failed: %s", exc)
            continue
        if not isinstance(msg, dict):
            continue

        kind = msg.get("type")
        echo = {"nonce": msg.get("nonce"), "t": msg.get("t")}
        if kind == "discover":
            reply = {
                "type": "announce",
                "v": PROTOCOL_VERSION,
                "id": server_id,
                "name": args.name,
                "rtsp_port": args.rtsp_port,
                "sources": paths.get(),
                **echo,
            }
            log.info("discover from %s:%d", *addr)
        elif kind == "ping":
            reply = {"type": "pong", "id": server_id, **echo}
        else:
            continue

        try:
            sock.sendto(json.dumps(reply, ensure_ascii=False).encode("utf-8"), addr)
        except OSError as exc:
            log.warning("send to %s failed: %s", addr, exc)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--name", default=socket.gethostname(), help="name shown on the TVs (default: hostname)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="UDP discovery port (default: %(default)s)")
    parser.add_argument("--bind", default="", help="local address to bind (default: all interfaces)")
    parser.add_argument("--rtsp-port", type=int, default=8554, help="MediaMTX RTSP port (default: %(default)s)")
    parser.add_argument("--api", default="http://127.0.0.1:9997", help="MediaMTX API base URL")
    parser.add_argument("--path", default="live", help="path to announce if the API is unreachable")
    parser.add_argument("--id-file", default=os.path.join("~", ".ndi-rtsp-server-id"),
                        help="file that stores this server's stable id")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    try:
        serve(args)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
