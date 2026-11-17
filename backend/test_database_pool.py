"""Memory-conscious PostgreSQL connection pool configuration tests."""
import unittest
from unittest.mock import patch

from database import create_pool
import app


class DatabasePoolTests(unittest.TestCase):
    def test_defaults_to_four_connections_and_bounded_waiters(self):
        with patch("database.ConnectionPool") as constructor:
            create_pool("postgresql://local/test")

        options = constructor.call_args.kwargs
        self.assertEqual(options["min_size"], 1)
        self.assertEqual(options["max_size"], 4)
        self.assertEqual(options["max_waiting"], 16)
        constructor.return_value.open.assert_called_once_with(wait=True, timeout=10)

    def test_pool_size_can_be_overridden_within_safe_bounds(self):
        with patch.dict("os.environ", {"SALOM_DB_POOL_SIZE": "2"}), patch("database.ConnectionPool") as constructor:
            create_pool("postgresql://local/test")
        self.assertEqual(constructor.call_args.kwargs["max_size"], 2)

    def test_invalid_pool_size_fails_closed(self):
        for value in ("0", "9", "many"):
            with self.subTest(value=value), patch.dict("os.environ", {"SALOM_DB_POOL_SIZE": value}):
                with self.assertRaises(ValueError):
                    create_pool("postgresql://local/test")

    def test_server_can_require_postgres_instead_of_silent_sqlite_fallback(self):
        with patch.object(app, "PG_DSN", ""), patch.dict("os.environ", {"SALOM_REQUIRE_POSTGRES": "1"}):
            with self.assertRaisesRegex(RuntimeError, "SALOM_DATABASE_URL is required"):
                app.initialize()


if __name__ == "__main__":
    unittest.main()
