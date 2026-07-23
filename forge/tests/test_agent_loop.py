"""Exercise the agent's control loop without hitting the network.

We stub the Anthropic client with a scripted two-turn conversation:
  turn 1: the model calls write_file (tool_use)
  turn 2: the model returns a final text answer (no tools -> loop ends)

This verifies tool dispatch, message threading, and loop termination — the
parts of ForgeAgent that don't depend on the real model.
"""

from types import SimpleNamespace

import forge_agent.agent as agent_mod
from forge_agent.agent import ForgeAgent


class _Block(SimpleNamespace):
    pass


class _FakeStream:
    def __init__(self, message):
        self._message = message

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def __iter__(self):
        return iter(())  # no streamed deltas needed for this test

    def get_final_message(self):
        return self._message


class _FakeMessages:
    def __init__(self, script):
        self._script = list(script)

    def stream(self, **_kwargs):
        return _FakeStream(self._script.pop(0))


class _FakeClient:
    def __init__(self, script):
        self.messages = _FakeMessages(script)


def test_full_loop_writes_file_then_finishes(tmp_path, monkeypatch):
    script = [
        SimpleNamespace(
            stop_reason="tool_use",
            content=[
                _Block(type="text", text="I'll create the file."),
                _Block(
                    type="tool_use",
                    id="tool_1",
                    name="write_file",
                    input={"path": "hi.txt", "content": "hello world\n"},
                ),
            ],
        ),
        SimpleNamespace(
            stop_reason="end_turn",
            content=[_Block(type="text", text="Done — created hi.txt.")],
        ),
    ]

    monkeypatch.setattr(
        agent_mod.anthropic, "Anthropic", lambda *a, **k: _FakeClient(script)
    )

    events = []
    agent = ForgeAgent(
        workspace=str(tmp_path),
        show_thinking=False,
        on_event=lambda kind, payload: events.append((kind, payload)),
    )
    final = agent.run("create hi.txt saying hello world")

    # The tool actually ran: the file exists with the right content.
    assert (tmp_path / "hi.txt").read_text() == "hello world\n"
    # The loop terminated on the text-only turn and returned that text.
    assert "Done" in final
    # A tool_result was threaded back into the conversation.
    roles = [m["role"] for m in agent.messages]
    assert roles == ["user", "assistant", "user", "assistant"]
    tool_results = agent.messages[2]["content"]
    assert tool_results[0]["type"] == "tool_result"
    assert tool_results[0]["tool_use_id"] == "tool_1"
    assert tool_results[0]["is_error"] is False
