"""Command-line interface for the Forge coding agent.

Usage:
    forge "Add a --json flag to the CLI and test it"        # one-shot task
    forge --workspace ./myproj "Fix the failing test"
    forge --repl                                            # interactive session
"""

from __future__ import annotations

import argparse
import sys

from .agent import ForgeAgent


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="forge", description="Forge — a high-performance coding agent."
    )
    parser.add_argument("task", nargs="?", help="The coding task to perform.")
    parser.add_argument(
        "-w", "--workspace", default=".", help="Workspace root (default: cwd)."
    )
    parser.add_argument("--model", default="claude-opus-4-8", help="Claude model id.")
    parser.add_argument(
        "--effort",
        default="xhigh",
        choices=["low", "medium", "high", "xhigh", "max"],
        help="Reasoning effort (default: xhigh — best for coding).",
    )
    parser.add_argument(
        "--max-turns", type=int, default=60, help="Max agent loop iterations."
    )
    parser.add_argument(
        "--no-thinking", action="store_true", help="Hide the model's reasoning."
    )
    parser.add_argument(
        "--repl", action="store_true", help="Interactive multi-task session."
    )
    args = parser.parse_args(argv)

    agent = ForgeAgent(
        workspace=args.workspace,
        model=args.model,
        effort=args.effort,
        max_turns=args.max_turns,
        show_thinking=not args.no_thinking,
    )

    if args.repl:
        return _repl(agent)

    if not args.task:
        parser.error("provide a task, or use --repl for interactive mode")

    agent.run(args.task)
    print()
    return 0


def _repl(agent: ForgeAgent) -> int:
    print("Forge REPL — type a task, or 'exit' to quit.\n")
    while True:
        try:
            task = input("\033[1;35mforge>\033[0m ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return 0
        if task.lower() in {"exit", "quit"}:
            return 0
        if not task:
            continue
        agent.run(task)
        print("\n")


if __name__ == "__main__":
    sys.exit(main())
