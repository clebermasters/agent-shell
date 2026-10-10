#!/usr/bin/env python3
"""Compatibility entry point for the archived preview harness."""
import runpy
from pathlib import Path
runpy.run_path(str(Path(__file__).resolve().parents[2]/"scripts/agent-ui-mcp.py"),run_name="__main__")
