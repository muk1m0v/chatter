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

    async def test_wildcard_subscription_receives_all_account_events(self):
        hub = RealtimeHub()
        user, alice, bob = str(uuid4()), str(uuid4()), str(uuid4())
        socket = Socket()
        task = asyncio.create_task(hub.serve(socket, user))
        await asyncio.wait_for(socket.accepted.wait(), 1)
        self.assertEqual(await asyncio.wait_for(socket.sent.get(), 1), {"type": "ready"})
        await socket.incoming.put({"type": "subscribe", "peer_id": "*"})
        for _ in range(20):
            if hub.subscriptions.get(socket) == "*": break
            await asyncio.sleep(0)
        self.assertEqual(await asyncio.wait_for(socket.sent.get(), 1), {"type": "subscribed", "peer_id": "*"})
        for peer_id, message_id in ((alice, 21), (bob, 22)):
            event = {"type": "message", "peer_id": peer_id, "message": {"id": message_id}}
            await hub.publish(user, event)
            self.assertEqual(await asyncio.wait_for(socket.sent.get(), 1), event)
        await socket.incoming.put(None)
        await asyncio.wait_for(task, 1)
        self.assertEqual(hub.connection_count, 0)

    async def test_wildcard_subscription_can_send_typing_only_to_valid_profile(self):
        hub = RealtimeHub()
        user, peer = str(uuid4()), str(uuid4())
        sender_socket = Socket()
        sender_task = asyncio.create_task(hub.serve(sender_socket, user))
        await asyncio.wait_for(sender_socket.accepted.wait(), 1)
        await sender_socket.sent.get()
        await sender_socket.incoming.put({"type": "subscribe", "peer_id": "*"})
        await sender_socket.sent.get()

        peer_socket, peer_task = await self.connect(hub, peer, user)
        while not sender_socket.sent.empty(): sender_socket.sent.get_nowait()
        await sender_socket.incoming.put({"type": "typing", "peer_id": peer, "typing": True})
        self.assertEqual(await asyncio.wait_for(peer_socket.sent.get(), 1), {"type": "typing", "peer_id": user, "typing": True})
        await sender_socket.incoming.put(None)
        await peer_socket.incoming.put(None)
        await asyncio.wait_for(sender_task, 1)
        await asyncio.wait_for(peer_task, 1)


if __name__ == "__main__":
    unittest.main()
