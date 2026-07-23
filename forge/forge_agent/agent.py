"""The Forge coding agent: a plan -> act -> verify -> reflect loop over the
Claude Messages API.

Design choices that matter for coding performance:

* Model: claude-opus-4-8 with adaptive thinking and `xhigh` effort, the best
  setting for coding and agentic work.
* Streaming: required at high max_tokens to avoid HTTP timeouts; we use the
  streaming helper and read the final accumulated message each turn.
* A manual agentic loop (rather than the SDK tool runner) so we own control
  flow: we run tools ourselves, feed all results back in one user turn, and
  keep going until the model stops calling tools.
"""

from __future__ import annotations

import json
import sys
from typing import Any, Callable

import anthropic

from .prompts import build_system_prompt
from .tools import Tool, ToolError, Workspace

MODEL = "claude-opus-4-8"
MAX_TOKENS = 32000
EFFORT = "xhigh"  # best for coding/agentic work on Opus 4.8


class ForgeAgent:
    def __init__(
        self,
        workspace: str = ".",
        *,
        model: str = MODEL,
        effort: str = EFFORT,
        max_turns: int = 60,
        show_thinking: bool = True,
        on_event: Callable[[str, str], None] | None = None,
        extra_context: str | None = None,
    ):
        self.client = anthropic.Anthropic()
        self.workspace = Workspace(workspace)
        self.model = model
        self.effort = effort
        self.max_turns = max_turns
        self.show_thinking = show_thinking
        self.on_event = on_event or _default_event
        self.tools: list[Tool] = self.workspace.tools()
        self._tool_map: dict[str, Tool] = {t.name: t for t in self.tools}
        self.system = build_system_prompt(str(self.workspace.root), extra_context)
        self.messages: list[dict[str, Any]] = []

    # --- public API ----------------------------------------------------------

    def run(self, task: str) -> str:
        """Run one task to completion. Returns the final assistant text."""
        self.messages.append({"role": "user", "content": task})
        final_text = ""

        for turn in range(self.max_turns):
            response = self._call_model()
            final_text = _text_of(response.content)

            # Persist the assistant turn verbatim (thinking blocks included) so
            # multi-turn tool use stays valid.
            self.messages.append({"role": "assistant", "content": response.content})

            if response.stop_reason == "pause_turn":
                # Server-side tool paused; re-send to let it resume.
                continue

            tool_uses = [b for b in response.content if b.type == "tool_use"]
            if not tool_uses:
                return final_text  # model is done

            results = self._run_tools(tool_uses)
            self.messages.append({"role": "user", "content": results})

        self.on_event("warn", f"Reached max_turns ({self.max_turns}).")
        return final_text

    # --- internals -----------------------------------------------------------

    def _call_model(self):
        tool_defs = [t.definition() for t in self.tools]
        with self.client.messages.stream(
            model=self.model,
            max_tokens=MAX_TOKENS,
            system=self.system,
            messages=self.messages,
            tools=tool_defs,
            thinking={"type": "adaptive", "display": "summarized"},
            output_config={"effort": self.effort},
        ) as stream:
            for event in stream:
                self._handle_stream_event(event)
            return stream.get_final_message()

    def _handle_stream_event(self, event) -> None:
        etype = getattr(event, "type", "")
        if etype == "content_block_delta":
            delta = event.delta
            if delta.type == "thinking_delta" and self.show_thinking:
                self.on_event("thinking", delta.thinking)
            elif delta.type == "text_delta":
                self.on_event("text", delta.text)
        elif etype == "content_block_start":
            block = event.content_block
            if block.type == "tool_use":
                self.on_event("tool_call", block.name)

    def _run_tools(self, tool_uses: list[Any]) -> list[dict[str, Any]]:
        results = []
        for block in tool_uses:
            self.on_event("tool_run", f"{block.name}({_short(block.input)})")
            try:
                tool = self._tool_map[block.name]
                output = tool.func(**block.input)
                is_error = False
            except ToolError as exc:
                output, is_error = f"Error: {exc}", True
            except Exception as exc:  # noqa: BLE001 - surface all tool failures
                output, is_error = f"Unexpected error: {exc}", True
            self.on_event("tool_result", _truncate(output, 400))
            results.append(
                {
                    "type": "tool_result",
                    "tool_use_id": block.id,
                    "content": output,
                    "is_error": is_error,
                }
            )
        return results


# --- helpers -----------------------------------------------------------------


def _text_of(content: list[Any]) -> str:
    return "".join(b.text for b in content if getattr(b, "type", "") == "text")


def _short(data: dict[str, Any]) -> str:
    return _truncate(json.dumps(data, ensure_ascii=False), 120)


def _truncate(text: str, limit: int) -> str:
    text = str(text)
    return text if len(text) <= limit else text[:limit] + f"… (+{len(text) - limit} chars)"


_COLORS = {
    "thinking": "\033[2;37m",  # dim grey
    "text": "\033[0m",
    "tool_call": "\033[36m",  # cyan
    "tool_run": "\033[36m",
    "tool_result": "\033[2;36m",
    "warn": "\033[33m",
}
_RESET = "\033[0m"


def _default_event(kind: str, payload: str) -> None:
    if kind == "text":
        sys.stdout.write(payload)
    elif kind == "thinking":
        sys.stdout.write(f"{_COLORS['thinking']}{payload}{_RESET}")
    elif kind == "tool_call":
        sys.stdout.write(f"\n{_COLORS['tool_call']}▸ {payload}{_RESET}")
    elif kind == "tool_run":
        sys.stdout.write(f"\n{_COLORS['tool_run']}⚙ {payload}{_RESET}\n")
    elif kind == "tool_result":
        sys.stdout.write(f"{_COLORS['tool_result']}{payload}{_RESET}\n")
    elif kind == "warn":
        sys.stdout.write(f"\n{_COLORS['warn']}⚠ {payload}{_RESET}\n")
    sys.stdout.flush()
