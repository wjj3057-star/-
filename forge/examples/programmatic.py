"""Use Forge as a library instead of the CLI.

    export ANTHROPIC_API_KEY=sk-ant-...
    python examples/programmatic.py
"""

from forge_agent import ForgeAgent


def main() -> None:
    agent = ForgeAgent(workspace="./scratch")
    summary = agent.run(
        "Create a Python module `primes.py` with a function `nth_prime(n)` "
        "returning the n-th prime (1-indexed). Add pytest tests covering the "
        "first few primes and an edge case, then run them and make them pass."
    )
    print("\n\n=== FINAL SUMMARY ===\n")
    print(summary)


if __name__ == "__main__":
    main()
