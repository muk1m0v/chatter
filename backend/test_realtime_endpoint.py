"""Authenticated WebSocket endpoint integration using a disposable SQLite database."""
import hashlib
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

from starlette.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

import app


class RealtimeEndpointTests(unittest.TestCase):
    def test_bearer_auth_subscription_and_typing_delivery(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(app, "DB", Path(directory) / "realtime.sqlite3"):
                app.initialize()
                accounts = [(str(uuid.uuid4()), "A" * 43), (str(uuid.uuid4()), "B" * 43)]
                with app.connect() as db:
                    for index, (profile_id, token) in enumerate(accounts):
                        now = time.time()
                        db.execute("INSERT INTO profiles(id,token_hash,name,username,username_key,is_active,created,updated) VALUES(?,?,?,?,?,1,?,?)",
                                   (profile_id, hashlib.sha256(token.encode()).hexdigest(), "WS QA", f"wsqa{index}", f"wsqa{index}", now, now))
                alice, bob = accounts
                with TestClient(app.app) as client:
                    with self.assertRaises(WebSocketDisconnect) as denied:
                        client.websocket_connect("/ws").__enter__()
                    self.assertEqual(denied.exception.code, 4401)
                    with client.websocket_connect("/ws", headers={"Authorization": "Bearer " + bob[1]}) as bob_socket:
                        self.assertEqual(bob_socket.receive_json(), {"type": "ready"})
                        bob_socket.send_json({"type": "subscribe", "peer_id": alice[0]})
                        self.assertEqual(bob_socket.receive_json(), {"type": "subscribed", "peer_id": alice[0]})
                        self.assertEqual(bob_socket.receive_json(), {"type": "presence", "peer_id": alice[0], "online": False})
                        with client.websocket_connect("/ws", headers={"Authorization": "Bearer " + alice[1]}) as alice_socket:
                            self.assertEqual(alice_socket.receive_json(), {"type": "ready"})
                            alice_socket.send_json({"type": "subscribe", "peer_id": bob[0]})
                            self.assertEqual(alice_socket.receive_json(), {"type": "subscribed", "peer_id": bob[0]})
                            self.assertEqual(alice_socket.receive_json(), {"type": "presence", "peer_id": bob[0], "online": True})
                            self.assertEqual(bob_socket.receive_json(), {"type": "presence", "peer_id": alice[0], "online": True})
                            alice_socket.send_json({"type": "typing", "peer_id": bob[0], "typing": True})
                            self.assertEqual(bob_socket.receive_json(), {"type": "typing", "peer_id": alice[0], "typing": True})


if __name__ == "__main__":
    unittest.main()
