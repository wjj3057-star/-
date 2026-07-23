"""Forge — a high-performance, coding-specialized AI agent built on Claude."""

from .agent import ForgeAgent
from .tools import Workspace, Tool, ToolError

__version__ = "0.1.0"
__all__ = ["ForgeAgent", "Workspace", "Tool", "ToolError"]
