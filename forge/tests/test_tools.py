"""Tests for the workspace tools. No API calls — these verify the primitives
the agent relies on, including the security boundary."""

import pytest

from forge_agent.tools import ToolError, Workspace


@pytest.fixture
def ws(tmp_path):
    return Workspace(str(tmp_path))


def test_write_then_read(ws):
    ws.write_file("hello.py", "print('hi')\n")
    out = ws.read_file("hello.py")
    assert "print('hi')" in out
    assert out.startswith("     1\t")  # line-numbered


def test_write_reports_create_vs_overwrite(ws):
    assert "Created" in ws.write_file("a.txt", "one")
    assert "Overwrote" in ws.write_file("a.txt", "two")


def test_edit_unique_replacement(ws):
    ws.write_file("m.py", "x = 1\ny = 2\n")
    ws.edit_file("m.py", "y = 2", "y = 3")
    assert "y = 3" in ws.read_file("m.py")


def test_edit_missing_snippet_errors(ws):
    ws.write_file("m.py", "x = 1\n")
    with pytest.raises(ToolError):
        ws.edit_file("m.py", "not there", "z")


def test_edit_ambiguous_snippet_errors(ws):
    ws.write_file("m.py", "a\na\n")
    with pytest.raises(ToolError):
        ws.edit_file("m.py", "a", "b")


def test_read_view_range(ws):
    ws.write_file("f.txt", "l1\nl2\nl3\nl4\n")
    out = ws.read_file("f.txt", view_range=[2, 3])
    assert "l2" in out and "l3" in out and "l1" not in out and "l4" not in out


def test_list_dir_hides_noise(ws):
    ws.write_file("keep.py", "")
    (ws.root / "__pycache__").mkdir()
    entries = ws.list_dir(".")
    assert "keep.py" in entries and "__pycache__" not in entries


def test_path_escape_is_blocked(ws):
    with pytest.raises(ToolError):
        ws.read_file("../../etc/passwd")
    with pytest.raises(ToolError):
        ws.write_file("/tmp/evil.txt", "nope")


def test_bash_captures_output_and_exit_code(ws):
    out = ws.bash("echo hello && exit 3")
    assert "hello" in out
    assert "exit code 3" in out


def test_bash_runs_in_workspace(ws):
    ws.write_file("marker.txt", "x")
    out = ws.bash("ls")
    assert "marker.txt" in out
