"""Isolated WebSocket hub tests; no network or production database."""
import asyncio
import unittest
from uuid import uuid4

from fastapi import WebSocketDisconnect

from realtime import RealtimeHub


class Socket:
    def __init__(self):
        self.incoming = asyncio.Queue()
        self.sent = asyncio.Queue()
        self.accepted = asyncio.Event()
        self.closed = None

    async def accept(self): self.accepted.set()
    async def send_json(self, data): await self.sent.put(data)
    async def receive_json(self):
        item = await self.incoming.get()
        if item is None: raise WebSocketDisconnect(1000)
        return item
    async def close(self, code=1000, reason=""): self.closed = (code, reason)


class RealtimeHubTests(unittest.IsolatedAsyncioTestCase):
    async def connect(self, hub, profile_id, peer_id):
        socket = Socket()
        task = asyncio.create_task(hub.serve(socket, profile_id))
        await asyncio.wait_for(socket.accepted.wait(), 1)
        self.assertEqual(await asyncio.wait_for(socket.sent.get(), 1), {"type": "ready"})
        await socket.incoming.put({"type": "subscribe", "peer_id": peer_id})
        for _ in range(20):
            if hub.subscriptions.get(socket) == peer_id: break
            await asyncio.sleep(0)
        self.assertEqual(hub.subscriptions.get(socket), peer_id)
        self.assertEqual(await asyncio.wait_for(socket.sent.get(), 1), {"type": "subscribed", "peer_id": peer_id})
        self.assertEqual((await asyncio.wait_for(socket.sent.get(), 1))["type"], "presence")
        return socket, task

    async def test_events_are_limited_to_subscribed_peer(self):
        hub = RealtimeHub()
        user, peer, other = str(uuid4()), str(uuid4()), str(uuid4())
        socket, task = await self.connect(hub, user, peer)
        self.assertEqual(hub.connection_count, 1)
        await hub.publish(user, {"type": "message", "peer_id": other})
        await asyncio.sleep(0)
        self.assertTrue(socket.sent.empty())
        event = {"type": "message", "peer_id": peer, "message": {"id": 12}}
        await hub.publish(user, event)
        self.assertEqual(await asyncio.wait_for(socket.sent.get(), 1), event)
        await socket.incoming.put(None)
        await asyncio.wait_for(task, 1)
        self.assertEqual(hub.connection_count, 0)
        self.assertNotIn(user, hub.connections)

    async def test_typing_is_forwarded_only_to_active_peer_subscription(self):
        hub = RealtimeHub()
        alice, bob, carol = str(uuid4()), str(uuid4()), str(uuid4())
        bob_socket, bob_task = await self.connect(hub, bob, alice)
        alice_socket, alice_task = await self.connect(hub, alice, bob)
        self.assertEqual(hub.connection_count, 2)
        self.assertEqual(await asyncio.wait_for(bob_socket.sent.get(), 1), {"type": "presence", "peer_id": alice, "online": True})
        await alice_socket.incoming.put({"type": "typing", "peer_id": bob, "typing": True})
        self.assertEqual(await asyncio.wait_for(bob_socket.sent.get(), 1), {"type": "typing", "peer_id": alice, "typing": True})
        await alice_socket.incoming.put({"type": "typing", "peer_id": carol, "typing": True})
        await asyncio.sleep(0)
        self.assertTrue(bob_socket.sent.empty())
        await alice_socket.incoming.put(None)
        await bob_socket.incoming.put(None)
        await asyncio.wait_for(alice_task, 1)
        await asyncio.wait_for(bob_task, 1)
        self.assertEqual(hub.connection_count, 0)


if __name__ == "__main__":
    unittest.main()
