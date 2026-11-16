"""Authenticated, process-local WebSocket hub for foreground chat events."""
import asyncio
from uuid import UUID

from fastapi import WebSocket, WebSocketDisconnect


class RealtimeHub:
    def __init__(self, per_user_limit: int = 5, total_limit: int = 24):
        self.per_user_limit = per_user_limit
        self.total_limit = total_limit
        self.connections: dict[str, set[WebSocket]] = {}
        self._connection_count = 0
        self.subscriptions: dict[WebSocket, str] = {}
        self.typing_last_sent: dict[WebSocket, float] = {}
        self.lock = asyncio.Lock()

    @property
    def connection_count(self) -> int:
        return self._connection_count

    async def serve(self, socket: WebSocket, profile_id: str) -> None:
        async with self.lock:
            user_connections = self.connections.get(profile_id, set())
            if len(user_connections) >= self.per_user_limit or self._connection_count >= self.total_limit:
                await socket.close(code=1013, reason="Connection limit reached")
                return
            await socket.accept()
            user_connections = self.connections.setdefault(profile_id, user_connections)
            user_connections.add(socket)
            self._connection_count += 1
        try:
            await socket.send_json({"type": "ready"})
            while True:
                message = await socket.receive_json()
                kind = message.get("type") if isinstance(message, dict) else None
                if kind == "ping":
                    await socket.send_json({"type": "pong"})
                elif kind == "subscribe":
                    try:
                        peer_id = str(UUID(message.get("peer_id", "")))
                    except (ValueError, TypeError, AttributeError):
                        await socket.close(code=1008, reason="Invalid peer")
                        return
                    async with self.lock:
                        self.subscriptions[socket] = peer_id
                        peer_online = bool(self.connections.get(peer_id))
                    await socket.send_json({"type": "subscribed", "peer_id": peer_id})
                    await socket.send_json({"type": "presence", "peer_id": peer_id, "online": peer_online})
                    await self.publish(peer_id, {"type": "presence", "peer_id": profile_id, "online": True})
                elif kind == "typing":
                    peer_id = self.subscriptions.get(socket)
                    now = asyncio.get_running_loop().time()
                    previous = self.typing_last_sent.get(socket, 0.0)
                    if peer_id and message.get("peer_id") == peer_id and isinstance(message.get("typing"), bool) and now - previous >= 0.35:
                        self.typing_last_sent[socket] = now
                        await self.publish(peer_id, {"type": "typing", "peer_id": profile_id, "typing": message["typing"]})
        except WebSocketDisconnect:
            pass
        except Exception:
            # Disconnects are routine; never log credentials or message content.
            pass
        finally:
            async with self.lock:
                was_connected = socket in self.connections.get(profile_id, set())
                self.connections.get(profile_id, set()).discard(socket)
                if was_connected:
                    self._connection_count = max(0, self._connection_count - 1)
                peer_id = self.subscriptions.pop(socket, None)
                self.typing_last_sent.pop(socket, None)
                if not self.connections.get(profile_id):
                    self.connections.pop(profile_id, None)
                    became_offline = True
                else:
                    became_offline = False
            if became_offline and peer_id:
                await self.publish(peer_id, {"type": "presence", "peer_id": profile_id, "online": False})

    async def publish(self, profile_id: str, event: dict) -> None:
        async with self.lock:
            targets = [socket for socket in self.connections.get(profile_id, set())
                       if not event.get("peer_id") or self.subscriptions.get(socket) == event.get("peer_id")]
        failed = []
        for socket in targets:
            try:
                await socket.send_json(event)
            except Exception:
                failed.append(socket)
        if failed:
            async with self.lock:
                for socket in failed:
                    user_connections = self.connections.get(profile_id, set())
                    if socket in user_connections:
                        user_connections.discard(socket)
                        self._connection_count = max(0, self._connection_count - 1)
                    self.subscriptions.pop(socket, None)
