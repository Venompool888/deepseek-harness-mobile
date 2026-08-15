#!/usr/bin/env python3
"""Evaluate JavaScript in the debug Android WebView through an ADB-forwarded CDP port."""

import base64
import json
import os
import socket
import struct
import sys
import urllib.request


def receive_exact(connection, length):
    data = b""
    while len(data) < length:
        data += connection.recv(length - len(data))
    return data


def receive_json(connection):
    _, second = receive_exact(connection, 2)
    length = second & 0x7F
    if length == 126:
        length = struct.unpack("!H", receive_exact(connection, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", receive_exact(connection, 8))[0]
    if second & 0x80:
        mask = receive_exact(connection, 4)
    payload = receive_exact(connection, length)
    if second & 0x80:
        payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
    return json.loads(payload)


def send_json(connection, value):
    payload = json.dumps(value).encode()
    header = bytearray([0x81])
    if len(payload) < 126:
        header.append(0x80 | len(payload))
    elif len(payload) < 65536:
        header.extend([0xFE])
        header.extend(struct.pack("!H", len(payload)))
    else:
        header.extend([0xFF])
        header.extend(struct.pack("!Q", len(payload)))
    mask = os.urandom(4)
    masked = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
    connection.sendall(header + mask + masked)


def main():
    port = int(os.environ.get("CDP_PORT", "9223"))
    pages = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json/list"))
    endpoint = pages[0]["webSocketDebuggerUrl"].removeprefix("ws://")
    host_port, path = endpoint.split("/", 1)
    host, socket_port = host_port.split(":")
    connection = socket.create_connection((host, int(socket_port)))
    key = base64.b64encode(os.urandom(16)).decode()
    request = (
        f"GET /{path} HTTP/1.1\r\nHost: {host_port}\r\nUpgrade: websocket\r\n"
        f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
    )
    connection.sendall(request.encode())
    if b"101 WebSocket Protocol Handshake" not in connection.recv(4096):
        raise RuntimeError("CDP WebSocket handshake failed")
    send_json(
        connection,
        {
            "id": 1,
            "method": "Runtime.evaluate",
            "params": {
                "expression": sys.argv[1],
                "awaitPromise": True,
                "returnByValue": True,
            },
        },
    )
    while True:
        message = receive_json(connection)
        if message.get("id") == 1:
            result = message["result"]["result"]
            print(json.dumps(result.get("value"), ensure_ascii=False))
            return


if __name__ == "__main__":
    main()
