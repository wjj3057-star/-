"""minigpt — a small GPT trained from scratch on Python source code."""

from .model import GPT, GPTConfig
from .data import CharTokenizer, Dataset
from .reasoning import think, ThinkResult, EFFORT_LEVELS, python_validity_score
from .mathsolve import solve_math, MathResult, MathError
from .chat import Assistant

__all__ = [
    "GPT", "GPTConfig", "CharTokenizer", "Dataset",
    "think", "ThinkResult", "EFFORT_LEVELS", "python_validity_score",
    "solve_math", "MathResult", "MathError", "Assistant",
]
