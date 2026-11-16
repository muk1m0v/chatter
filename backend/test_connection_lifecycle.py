"""Regression test: SQLite request connections must release their descriptors."""
import tempfile
import sqlite3
import unittest
from pathlib import Path
from unittest.mock import patch

import app


class ConnectionLifecycleTests(unittest.TestCase):
    def test_context_exit_closes_sqlite_connection(self):
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / 'salom.sqlite3'
            with patch.object(app, 'DB', database):
                with app.connect() as connection:
                    connection.execute('CREATE TABLE probe (value INTEGER)')
                    connection.execute('INSERT INTO probe VALUES (1)')
                with self.assertRaisesRegex(sqlite3.ProgrammingError, 'closed'):
                    connection.execute('SELECT 1')
                with app.connect() as check:
                    self.assertEqual(check.execute('SELECT value FROM probe').fetchone()[0], 1)

                with self.assertRaisesRegex(RuntimeError, 'rollback'):
                    with app.connect() as transaction:
                        transaction.execute('INSERT INTO probe VALUES (2)')
                        raise RuntimeError('rollback')
                with app.connect() as check:
                    self.assertEqual(check.execute('SELECT count(*) FROM probe').fetchone()[0], 1)


if __name__ == '__main__':
    unittest.main()
