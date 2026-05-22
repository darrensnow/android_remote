#!/usr/bin/env python3
import argparse
import asyncio
import contextlib
import logging
import struct
from dataclasses import dataclass, field
from typing import Dict, Optional, Tuple


RELAY_MAGIC = 0x41525231
RELAY_VERSION = 1

ROLE_HOST = 1
ROLE_CLIENT = 2

CHANNEL_VIDEO = 1
CHANNEL_AUDIO = 2

STATUS_OK = 0
STATUS_ERROR = 1

REGISTER_REJECTED = -1
REGISTER_WAITING = 0
REGISTER_PAIRED = 1

MAX_STRING_BYTES = 1024
PAIR_WAIT_TIMEOUT_SECONDS = 60


async def read_int(reader: asyncio.StreamReader) -> int:
    data = await reader.readexactly(4)
    return struct.unpack(">i", data)[0]


async def write_int(writer: asyncio.StreamWriter, value: int) -> None:
    writer.write(struct.pack(">i", value))


async def read_string(reader: asyncio.StreamReader) -> str:
    size = await read_int(reader)
    if size < 0 or size > MAX_STRING_BYTES:
        raise ValueError(f"invalid string length: {size}")
    data = await reader.readexactly(size)
    return data.decode("utf-8")


async def write_string(writer: asyncio.StreamWriter, value: str) -> None:
    data = value.encode("utf-8")
    if len(data) > MAX_STRING_BYTES:
        raise ValueError("string too long")
    await write_int(writer, len(data))
    writer.write(data)


async def write_status(writer: asyncio.StreamWriter, status: int, message: str) -> None:
    await write_int(writer, status)
    await write_string(writer, message)
    await writer.drain()


@dataclass
class PendingConnection:
    role: int
    channel: int
    room_id: str
    auth_token: str
    reader: asyncio.StreamReader
    writer: asyncio.StreamWriter
    paired: asyncio.Event = field(default_factory=asyncio.Event)
    done: asyncio.Event = field(default_factory=asyncio.Event)


@dataclass
class RelaySlot:
    waiting_host: Optional[PendingConnection] = None
    waiting_client: Optional[PendingConnection] = None
    active: bool = False


class RelayServer:
    def __init__(self) -> None:
        self._slots: Dict[Tuple[str, int], RelaySlot] = {}
        self._lock = asyncio.Lock()

    async def handle_connection(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        peer_name = writer.get_extra_info("peername")
        pending: Optional[PendingConnection] = None
        try:
            magic = await read_int(reader)
            version = await read_int(reader)
            role = await read_int(reader)
            channel = await read_int(reader)
            room_id = await read_string(reader)
            auth_token = await read_string(reader)
            if magic != RELAY_MAGIC or version != RELAY_VERSION:
                await write_status(writer, STATUS_ERROR, "中继协议版本不匹配")
                return
            if role not in (ROLE_HOST, ROLE_CLIENT):
                await write_status(writer, STATUS_ERROR, "无效的连接角色")
                return
            if channel not in (CHANNEL_VIDEO, CHANNEL_AUDIO):
                await write_status(writer, STATUS_ERROR, "无效的通道类型")
                return
            if not room_id:
                await write_status(writer, STATUS_ERROR, "房间号不能为空")
                return

            pending = PendingConnection(role, channel, room_id, auth_token, reader, writer)
            register_result = await self._register_connection(pending)
            if register_result == REGISTER_REJECTED:
                return
            if register_result == REGISTER_WAITING:
                await asyncio.wait_for(pending.paired.wait(), timeout=PAIR_WAIT_TIMEOUT_SECONDS)
            else:
                await pending.paired.wait()
            await pending.done.wait()
        except asyncio.TimeoutError:
            if pending is not None:
                await self._remove_waiting_connection(pending)
                if not pending.paired.is_set():
                    with contextlib.suppress(Exception):
                        await write_status(writer, STATUS_ERROR, "等待另一端连接超时")
            logging.info("wait timeout: peer=%s", peer_name)
        except asyncio.IncompleteReadError:
            if pending is not None:
                await self._remove_waiting_connection(pending)
        except Exception as error:
            if pending is not None:
                await self._remove_waiting_connection(pending)
            logging.warning("connection error from %s: %s", peer_name, error)
            with contextlib.suppress(Exception):
                await write_status(writer, STATUS_ERROR, str(error) or error.__class__.__name__)
        finally:
            await self._close_writer(writer)

    async def _register_connection(self, pending: PendingConnection) -> int:
        key = (pending.room_id, pending.channel)
        async with self._lock:
            slot = self._slots.setdefault(key, RelaySlot())
            if slot.active:
                await write_status(pending.writer, STATUS_ERROR, "该房间通道已被占用")
                return REGISTER_REJECTED

            if pending.role == ROLE_HOST:
                if slot.waiting_host is not None:
                    await write_status(pending.writer, STATUS_ERROR, "服务端已在等待连接")
                    return REGISTER_REJECTED
                peer = slot.waiting_client
                if peer is None:
                    slot.waiting_host = pending
                    logging.info("host waiting: room=%s channel=%s", pending.room_id, pending.channel)
                    return REGISTER_WAITING
                if peer.auth_token != pending.auth_token:
                    await write_status(pending.writer, STATUS_ERROR, "房间口令不匹配")
                    return REGISTER_REJECTED
                slot.waiting_client = None
                slot.active = True
                asyncio.create_task(self._bridge_pair(key, pending, peer))
                return REGISTER_PAIRED

            if slot.waiting_client is not None:
                await write_status(pending.writer, STATUS_ERROR, "客户端已在等待连接")
                return REGISTER_REJECTED
            peer = slot.waiting_host
            if peer is None:
                slot.waiting_client = pending
                logging.info("client waiting: room=%s channel=%s", pending.room_id, pending.channel)
                return REGISTER_WAITING
            if peer.auth_token != pending.auth_token:
                await write_status(pending.writer, STATUS_ERROR, "房间口令不匹配")
                return REGISTER_REJECTED
            slot.waiting_host = None
            slot.active = True
            asyncio.create_task(self._bridge_pair(key, peer, pending))
            return REGISTER_PAIRED

    async def _remove_waiting_connection(self, pending: PendingConnection) -> None:
        key = (pending.room_id, pending.channel)
        async with self._lock:
            slot = self._slots.get(key)
            if slot is None:
                return
            if slot.waiting_host is pending:
                slot.waiting_host = None
            if slot.waiting_client is pending:
                slot.waiting_client = None
            if not slot.active and slot.waiting_host is None and slot.waiting_client is None:
                self._slots.pop(key, None)

    async def _bridge_pair(
        self,
        key: Tuple[str, int],
        host: PendingConnection,
        client: PendingConnection,
    ) -> None:
        channel_label = "video" if key[1] == CHANNEL_VIDEO else "audio"
        logging.info("paired: room=%s channel=%s", key[0], channel_label)
        try:
            await write_status(host.writer, STATUS_OK, "relay-ready")
            await write_status(client.writer, STATUS_OK, "relay-ready")
            host.paired.set()
            client.paired.set()
            await asyncio.gather(
                self._pipe(host.reader, client.writer),
                self._pipe(client.reader, host.writer),
            )
        except Exception as error:
            logging.info("pair closed: room=%s channel=%s error=%s", key[0], channel_label, error)
        finally:
            async with self._lock:
                slot = self._slots.get(key)
                if slot is not None:
                    slot.active = False
                    if slot.waiting_host is None and slot.waiting_client is None:
                        self._slots.pop(key, None)
            host.paired.set()
            client.paired.set()
            host.done.set()
            client.done.set()
            await self._close_writer(host.writer)
            await self._close_writer(client.writer)

    async def _pipe(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        while True:
            data = await reader.read(65536)
            if not data:
                return
            writer.write(data)
            await writer.drain()

    async def _close_writer(self, writer: asyncio.StreamWriter) -> None:
        if writer.is_closing():
            return
        writer.close()
        with contextlib.suppress(Exception):
            await writer.wait_closed()


async def run_server(host: str, port: int) -> None:
    relay = RelayServer()
    server = await asyncio.start_server(relay.handle_connection, host, port)
    addresses = ", ".join(str(sock.getsockname()) for sock in server.sockets or [])
    logging.info("relay listening on %s", addresses)
    async with server:
        await server.serve_forever()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Android Remote NAS relay server")
    parser.add_argument("--host", default="0.0.0.0", help="bind host")
    parser.add_argument("--port", type=int, default=9000, help="bind port")
    parser.add_argument("--log-level", default="INFO", help="logging level")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(message)s",
    )
    asyncio.run(run_server(args.host, args.port))


if __name__ == "__main__":
    main()