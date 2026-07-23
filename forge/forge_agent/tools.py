"""Tool implementations for the Forge coding agent.

Each tool is a plain Python function plus a JSON-schema definition. File
operations are confined to the workspace root; bash runs inside the workspace
with a timeout. These are the primitives the model composes to read, write,
run, and verify code.
"""

from __future__ import annotations

import os
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable


class ToolError(Exception):
    """Raised when a tool cannot complete; surfaced back to the model."""


@dataclass
class Tool:
    name: str
    description: str
    input_schema: dict[str, Any]
    func: Callable[..., str]

    def definition(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "input_schema": self.input_schema,
        }


class Workspace:
    """A rooted view of the filesystem the agent is allowed to touch."""

    def __init__(self, root: str, bash_timeout: int = 120):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.bash_timeout = bash_timeout

    def _resolve(self, path: str) -> Path:
        """Resolve a user/model-supplied path and confine it to the root."""
        p = Path(path)
        candidate = (p if p.is_absolute() else self.root / p).resolve()
        try:
            candidate.relative_to(self.root)
        except ValueError as exc:
            raise ToolError(
                f"Path {path!r} escapes the workspace root {self.root}."
            ) from exc
        return candidate

    # --- tool bodies ---------------------------------------------------------

    def read_file(self, path: str, view_range: list[int] | None = None) -> str:
        target = self._resolve(path)
        if not target.exists():
            raise ToolError(f"File not found: {path}")
        if target.is_dir():
            raise ToolError(f"{path} is a directory; use list_dir.")
        text = target.read_text(encoding="utf-8", errors="replace")
        lines = text.splitlines()
        start = 1
        if view_range:
            start, end = view_range
            end = len(lines) if end == -1 else end
            lines = lines[start - 1 : end]
        numbered = "\n".join(f"{i + start:>6}\t{line}" for i, line in enumerate(lines))
        return numbered or "(empty file)"

    def write_file(self, path: str, content: str) -> str:
        target = self._resolve(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        existed = target.exists()
        target.write_text(content, encoding="utf-8")
        n = len(content.splitlines())
        verb = "Overwrote" if existed else "Created"
        return f"{verb} {path} ({n} lines)."

    def edit_file(self, path: str, old_str: str, new_str: str) -> str:
        target = self._resolve(path)
        if not target.exists():
            raise ToolError(f"File not found: {path}")
        text = target.read_text(encoding="utf-8")
        count = text.count(old_str)
        if count == 0:
            raise ToolError(
                f"old_str not found in {path}. Read the file and match exactly."
            )
        if count > 1:
            raise ToolError(
                f"old_str appears {count} times in {path}; make it unique."
            )
        target.write_text(text.replace(old_str, new_str), encoding="utf-8")
        return f"Edited {path}."

    def list_dir(self, path: str = ".") -> str:
        target = self._resolve(path)
        if not target.exists():
            raise ToolError(f"Path not found: {path}")
        if target.is_file():
            return f"{path} (file, {target.stat().st_size} bytes)"
        entries = []
        for item in sorted(target.iterdir()):
            if item.name in {".git", "__pycache__", ".venv", "node_modules"}:
                continue
            suffix = "/" if item.is_dir() else ""
            entries.append(f"{item.name}{suffix}")
        return "\n".join(entries) or "(empty directory)"

    def bash(self, command: str) -> str:
        try:
            proc = subprocess.run(
                command,
                shell=True,
                cwd=self.root,
                capture_output=True,
                text=True,
                timeout=self.bash_timeout,
            )
        except subprocess.TimeoutExpired:
            raise ToolError(
                f"Command timed out after {self.bash_timeout}s: {command}"
            )
        out = proc.stdout or ""
        err = proc.stderr or ""
        parts = [f"$ {command}", f"(exit code {proc.returncode})"]
        if out.strip():
            parts.append("--- stdout ---\n" + out.rstrip())
        if err.strip():
            parts.append("--- stderr ---\n" + err.rstrip())
        if not out.strip() and not err.strip():
            parts.append("(no output)")
        return "\n".join(parts)

    def run_tests(self, command: str | None = None) -> str:
        """Run the project's tests. Defaults to pytest, then unittest."""
        if command:
            return self.bash(command)
        if (self.root / "pytest.ini").exists() or _has_test_files(self.root):
            return self.bash("python -m pytest -q 2>&1 || true")
        return self.bash("python -m unittest discover -v 2>&1 || true")

    # --- registry ------------------------------------------------------------

    def tools(self) -> list[Tool]:
        return [
            Tool(
                "read_file",
                "Read a file's contents with line numbers. Optionally pass "
                "view_range=[start, end] (1-indexed; end=-1 for EOF).",
                {
                    "type": "object",
                    "properties": {
                        "path": {"type": "string", "description": "File path."},
                        "view_range": {
                            "type": "array",
                            "items": {"type": "integer"},
                            "description": "Optional [start, end] line range.",
                        },
                    },
                    "required": ["path"],
                },
                self.read_file,
            ),
            Tool(
                "write_file",
                "Create a new file or overwrite an existing one with content.",
                {
                    "type": "object",
                    "properties": {
                        "path": {"type": "string"},
                        "content": {"type": "string"},
                    },
                    "required": ["path", "content"],
                },
                self.write_file,
            ),
            Tool(
                "edit_file",
                "Replace a unique snippet in an existing file. old_str must "
                "match exactly and appear exactly once.",
                {
                    "type": "object",
                    "properties": {
                        "path": {"type": "string"},
                        "old_str": {"type": "string"},
                        "new_str": {"type": "string"},
                    },
                    "required": ["path", "old_str", "new_str"],
                },
                self.edit_file,
            ),
            Tool(
                "list_dir",
                "List the entries of a directory in the workspace.",
                {
                    "type": "object",
                    "properties": {"path": {"type": "string"}},
                    "required": [],
                },
                self.list_dir,
            ),
            Tool(
                "bash",
                "Run a shell command in the workspace and return stdout, "
                "stderr, and the exit code. Use to run code, install "
                "packages, and inspect the environment.",
                {
                    "type": "object",
                    "properties": {"command": {"type": "string"}},
                    "required": ["command"],
                },
                self.bash,
            ),
            Tool(
                "run_tests",
                "Run the project's test suite. Pass an explicit command to "
                "override auto-detection (pytest/unittest).",
                {
                    "type": "object",
                    "properties": {"command": {"type": "string"}},
                    "required": [],
                },
                self.run_tests,
            ),
        ]


def _has_test_files(root: Path) -> bool:
    return any(root.rglob("test_*.py")) or any(root.rglob("*_test.py"))
