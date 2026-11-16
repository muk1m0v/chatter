"""Fail-closed 0.15.0 deployment preflight. This script never changes production."""

import argparse
import ast
from pathlib import Path
import sys


REQUIRED_FILES = (
    "app.py",
    "auth.py",
    "database.py",
    "media.py",
    "push.py",
    "realtime.py",
    "requirements.txt",
    "salom-api.service",
    "migrate_sqlite_to_postgres.py",
)


def preflight(source: Path) -> list[str]:
    problems = []
    for name in REQUIRED_FILES:
        path = source / name
        if not path.is_file():
            problems.append(f"missing required release file: {name}")
        elif path.suffix == ".py":
            try:
                ast.parse(path.read_text(encoding="utf-8"), filename=name)
            except (OSError, SyntaxError) as error:
                problems.append(f"invalid Python source {name}: {type(error).__name__}")

    requirements = source / "requirements.txt"
    if requirements.is_file():
        declared = requirements.read_text(encoding="utf-8").lower()
        for package in ("psycopg", "psycopg-pool"):
            if package not in declared:
                problems.append(f"requirements.txt does not declare {package}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("/tmp/salom-api-deploy"))
    arguments = parser.parse_args()

    problems = preflight(arguments.source)
    if problems:
        for problem in problems:
            print(f"BLOCKED: {problem}", file=sys.stderr)
        return 1

    print("Preflight passed for the v0.15.0 backend source bundle.")
    print("No files, services, databases, or Nginx configuration were changed.")
    print("Production deployment remains a separate approved stage after backup, restore, load, and WebSocket-through-Nginx gates pass.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
