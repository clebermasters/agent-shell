#!/usr/bin/env python3
"""Publish an attachment to this agent's verified AgentShell conversation."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import sys


RECEIPT_FIELDS = (
    "published", "bindingId", "conversationKey", "sessionName", "windowIndex", "fileIds",
)


def load_adapter():
    path = Path(__file__).resolve().with_name("agent-ui-mcp.py")
    if not path.is_file():
        raise RuntimeError("AgentShell publisher is not installed beside this command")
    spec = importlib.util.spec_from_file_location("agentshell_file_publisher", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("Cannot load the AgentShell publisher")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    if not callable(getattr(module, "publish_chat_file", None)):
        raise RuntimeError("AgentShell publisher needs an update before it can publish files")
    return module


def current_metadata():
    """Read invocation identity afresh; a reusable agent process can serve many threads."""
    for name in ("CODEX_THREAD_ID", "CODEX_SESSION_ID"):
        thread = os.environ.get(name, "").strip()
        if thread:
            return {"threadId": thread}
    return None


def validate_receipt(value, expected_session=None, expected_window=None):
    if not isinstance(value, dict) or value.get("published") is not True:
        raise RuntimeError("The backend did not confirm file publication")
    for name in ("bindingId", "conversationKey", "sessionName"):
        if not isinstance(value.get(name), str) or not value[name].strip():
            raise RuntimeError("The backend returned an incomplete publication receipt")
    window = value.get("windowIndex")
    if isinstance(window, bool) or not isinstance(window, int) or window < 0:
        raise RuntimeError("The backend returned an invalid publication window")
    files = value.get("fileIds")
    if not isinstance(files, list) or len(files) != 1 or not all(isinstance(item, str) and item.strip() for item in files):
        raise RuntimeError("The backend did not confirm the published file")
    if expected_session is not None and value["sessionName"] != expected_session:
        raise RuntimeError("The publication receipt does not match the expected session")
    if expected_window is not None and window != expected_window:
        raise RuntimeError("The publication receipt does not match the expected window")
    return {name: value[name] for name in RECEIPT_FIELDS}


def build_parser():
    parser = argparse.ArgumentParser(
        description="Publish a file to the current, verified AgentShell conversation.",
        epilog=("Connection and credentials use the shared AgentShell publisher configuration "
                "(AGENTSHELL_UI_CONFIG, AGENTSHELL_UI_URL and AGENTSHELL_UI_TOKEN). "
                "A session name is only an assertion; it never selects another chat."),
    )
    parser.add_argument("file", nargs="?", type=Path, help="File, image, or audio attachment to publish")
    parser.add_argument("--prompt", default="", help="Optional description shown with the attachment")
    parser.add_argument("--mode", choices=("auto", "current", "tmux", "acp"), metavar="{auto,current,tmux}", default="auto",
                        help="auto/current/tmux all publish to the verified current conversation")
    parser.add_argument("--session-name", help="Require the resolved current session to have this name")
    parser.add_argument("--window-index", type=int, help="Require the resolved current window index")
    parser.add_argument("--json", action="store_true", help="Print the verified publication receipt as JSON")
    parser.add_argument("--verbose", action="store_true", help="Print the verified publication receipt as JSON")
    # Parse old flags only to reject them without echoing credentials or silently
    # falling back to the legacy phone-to-agent input operation.
    for name in ("token", "ws-url", "session-id", "cwd"):
        parser.add_argument("--" + name, help=argparse.SUPPRESS)
    parser.add_argument("--list-sessions", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--skip-verify", action="store_true", help=argparse.SUPPRESS)
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.token is not None:
        parser.error("--token is not accepted; use private AgentShell configuration or AGENTSHELL_UI_TOKEN")
    if args.mode == "acp" or args.session_id is not None or args.cwd is not None or args.list_sessions:
        parser.error("Legacy ACP input mode is not file publication; publish from the current agent conversation instead")
    if args.skip_verify:
        parser.error("Conversation verification cannot be skipped")
    if args.ws_url is not None:
        parser.error("--ws-url is no longer used; configure the HTTP backend with AGENTSHELL_UI_URL")
    if args.file is None:
        parser.error("FILE is required")
    if args.window_index is not None and args.window_index < 0:
        parser.error("--window-index must be nonnegative")
    if args.session_name is not None and not args.session_name.strip():
        parser.error("--session-name must not be blank")
    try:
        adapter = load_adapter()
        result = adapter.publish_chat_file(
            str(args.file.expanduser()), prompt=args.prompt, metadata=current_metadata(),
            expected_session_name=args.session_name, expected_window_index=args.window_index,
        )
        receipt = validate_receipt(result, args.session_name, args.window_index)
    except Exception as error:
        detail = str(error)
        for name in ("AUTH_TOKEN", "AGENTSHELL_AUTH_TOKEN", "AGENTSHELL_UI_TOKEN"):
            token = os.environ.get(name)
            if token:
                detail = detail.replace(token, "[redacted]")
        print("File publication failed: " + detail, file=sys.stderr)
        return 4
    if args.json or args.verbose:
        print(json.dumps(receipt))
    else:
        print(f"Published {args.file.name} to {receipt['sessionName']} (window {receipt['windowIndex']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
