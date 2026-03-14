#!/usr/bin/env python3
"""
Diagnostic script to capture and analyze terminal escape sequences
after running a command, to identify cursor positioning issues.

Connects to the AgentShell backend via WebSocket, attaches to a tmux
session, sends a command, and analyzes the escape sequences in the output.
"""

import asyncio
import json
import os
import re
import sys
import time

import websockets

# ── Configuration ──────────────────────────────────────────────────────────
BACKEND_HOST = os.environ.get("BACKEND_HOST", "localhost")
BACKEND_PORT = int(os.environ.get("BACKEND_PORT", "4010"))
AUTH_TOKEN = os.environ.get("AUTH_TOKEN", "")
SESSION_NAME = sys.argv[1] if len(sys.argv) > 1 else "AgentShell"
COLS = int(sys.argv[2]) if len(sys.argv) > 2 else 80
ROWS = int(sys.argv[3]) if len(sys.argv) > 3 else 24
COMMAND = sys.argv[4] if len(sys.argv) > 4 else "date"

WS_URL = f"ws://{BACKEND_HOST}:{BACKEND_PORT}/ws?token={AUTH_TOKEN}"

# ── Escape sequence patterns ──────────────────────────────────────────────
# CUP: ESC[row;colH  or  ESC[row;colf
RE_CUP = re.compile(r'\x1b\[(\d+)?;?(\d+)?[Hf]')
# DECSTBM: ESC[top;bottomr
RE_DECSTBM = re.compile(r'\x1b\[(\d+)?;?(\d+)?r')
# Cursor Up: ESC[nA
RE_CUU = re.compile(r'\x1b\[(\d+)?A')
# Cursor Down: ESC[nB
RE_CUD = re.compile(r'\x1b\[(\d+)?B')
# Cursor Forward: ESC[nC
RE_CUF = re.compile(r'\x1b\[(\d+)?C')
# Cursor Back: ESC[nD
RE_CUB = re.compile(r'\x1b\[(\d+)?D')
# VPA: ESC[nd  (cursor vertical absolute)
RE_VPA = re.compile(r'\x1b\[(\d+)?d')
# Erase in Display: ESC[nJ
RE_ED = re.compile(r'\x1b\[(\d+)?J')
# Erase in Line: ESC[nK
RE_EL = re.compile(r'\x1b\[(\d+)?K')
# Newline / carriage return
RE_NEWLINE = re.compile(r'[\r\n]')
# Save/Restore cursor
RE_SAVE = re.compile(r'\x1b7|\x1b\[s')
RE_RESTORE = re.compile(r'\x1b8|\x1b\[u')
# SGR (color/style) - for display but not position tracking
RE_SGR = re.compile(r'\x1b\[[\d;]*m')
# DA response
RE_DA = re.compile(r'\x1b\[\??[>]?[\d;]*[cRn]')


def parse_escape_sequences(data: str) -> list:
    """Parse data into a list of (type, detail, raw) tuples."""
    events = []
    i = 0
    while i < len(data):
        # ESC sequence
        if data[i] == '\x1b':
            # CSI: ESC [
            if i + 1 < len(data) and data[i + 1] == '[':
                # Find the end of the CSI sequence
                j = i + 2
                while j < len(data) and data[j] in '0123456789;?!>':
                    j += 1
                if j < len(data):
                    final_char = data[j]
                    params_str = data[i + 2:j]
                    raw = data[i:j + 1]

                    if final_char in ('H', 'f'):
                        parts = params_str.split(';') if params_str else ['1', '1']
                        row = int(parts[0]) if parts[0] else 1
                        col = int(parts[1]) if len(parts) > 1 and parts[1] else 1
                        events.append(('CUP', f'row={row} col={col}', raw))
                    elif final_char == 'r':
                        parts = params_str.split(';') if params_str else []
                        top = int(parts[0]) if parts and parts[0] else 1
                        bot = int(parts[1]) if len(parts) > 1 and parts[1] else ROWS
                        events.append(('DECSTBM', f'top={top} bottom={bot}', raw))
                    elif final_char == 'A':
                        n = int(params_str) if params_str else 1
                        events.append(('CUU', f'up={n}', raw))
                    elif final_char == 'B':
                        n = int(params_str) if params_str else 1
                        events.append(('CUD', f'down={n}', raw))
                    elif final_char == 'C':
                        n = int(params_str) if params_str else 1
                        events.append(('CUF', f'forward={n}', raw))
                    elif final_char == 'D':
                        n = int(params_str) if params_str else 1
                        events.append(('CUB', f'back={n}', raw))
                    elif final_char == 'd':
                        n = int(params_str) if params_str else 1
                        events.append(('VPA', f'row={n}', raw))
                    elif final_char == 'J':
                        n = int(params_str) if params_str else 0
                        events.append(('ED', f'mode={n}', raw))
                    elif final_char == 'K':
                        n = int(params_str) if params_str else 0
                        events.append(('EL', f'mode={n}', raw))
                    elif final_char == 'G':
                        n = int(params_str) if params_str else 1
                        events.append(('CHA', f'col={n}', raw))
                    elif final_char == 'm':
                        events.append(('SGR', params_str, raw))
                    elif final_char == 'L':
                        n = int(params_str) if params_str else 1
                        events.append(('IL', f'lines={n}', raw))
                    elif final_char == 'M':
                        n = int(params_str) if params_str else 1
                        events.append(('DL', f'lines={n}', raw))
                    elif final_char in ('c', 'R', 'n'):
                        events.append(('DA/DSR', params_str, raw))
                    elif final_char == 'S':
                        n = int(params_str) if params_str else 1
                        events.append(('SU', f'scroll_up={n}', raw))
                    elif final_char == 'T':
                        n = int(params_str) if params_str else 1
                        events.append(('SD', f'scroll_down={n}', raw))
                    elif final_char == 'X':
                        n = int(params_str) if params_str else 1
                        events.append(('ECH', f'chars={n}', raw))
                    elif final_char == '@':
                        n = int(params_str) if params_str else 1
                        events.append(('ICH', f'chars={n}', raw))
                    elif final_char == 'P':
                        n = int(params_str) if params_str else 1
                        events.append(('DCH', f'chars={n}', raw))
                    elif final_char == 'h':
                        events.append(('SM', params_str, raw))
                    elif final_char == 'l':
                        events.append(('RM', params_str, raw))
                    else:
                        events.append(('CSI?', f'final={final_char} params={params_str}', raw))
                    i = j + 1
                    continue
                else:
                    events.append(('ESC_INCOMPLETE', '', data[i:]))
                    break
            # ESC followed by single char
            elif i + 1 < len(data):
                ch = data[i + 1]
                if ch == '7':
                    events.append(('SAVE_CURSOR', '', data[i:i + 2]))
                elif ch == '8':
                    events.append(('RESTORE_CURSOR', '', data[i:i + 2]))
                elif ch == 'D':
                    events.append(('IND', 'index', data[i:i + 2]))
                elif ch == 'E':
                    events.append(('NEL', 'next_line', data[i:i + 2]))
                elif ch == 'M':
                    events.append(('RI', 'reverse_index', data[i:i + 2]))
                elif ch == '(':
                    if i + 2 < len(data):
                        events.append(('SCS', data[i + 2], data[i:i + 3]))
                        i += 3
                        continue
                elif ch == ')':
                    if i + 2 < len(data):
                        events.append(('SCS', data[i + 2], data[i:i + 3]))
                        i += 3
                        continue
                elif ch == ']':
                    # OSC sequence - find ST
                    j = i + 2
                    while j < len(data) and data[j] != '\x07':
                        if j + 1 < len(data) and data[j] == '\x1b' and data[j + 1] == '\\':
                            j += 1
                            break
                        j += 1
                    osc_data = data[i + 2:j]
                    events.append(('OSC', osc_data[:50], data[i:j + 1]))
                    i = j + 1
                    continue
                else:
                    events.append(('ESC', ch, data[i:i + 2]))
                i += 2
                continue
            else:
                events.append(('ESC_INCOMPLETE', '', data[i:]))
                break
        elif data[i] == '\r':
            events.append(('CR', '', '\\r'))
            i += 1
        elif data[i] == '\n':
            events.append(('LF', '', '\\n'))
            i += 1
        elif data[i] == '\x08':
            events.append(('BS', '', '\\b'))
            i += 1
        elif data[i] == '\x07':
            events.append(('BEL', '', '\\a'))
            i += 1
        elif ord(data[i]) < 32:
            events.append(('CTRL', f'0x{ord(data[i]):02x}', f'\\x{ord(data[i]):02x}'))
            i += 1
        else:
            # Printable text - collect run
            j = i
            while j < len(data) and ord(data[j]) >= 32 and data[j] != '\x1b':
                j += 1
            text = data[i:j]
            if len(text) > 60:
                events.append(('TEXT', text[:30] + '...' + text[-20:], f'({len(text)} chars)'))
            else:
                events.append(('TEXT', text, ''))
            i = j
    return events


def simulate_cursor(events: list, rows: int, cols: int) -> dict:
    """Simulate cursor movement through escape sequence events."""
    cursor_row = 1  # 1-based
    cursor_col = 1  # 1-based
    scroll_top = 1
    scroll_bottom = rows
    saved_row = 1
    saved_col = 1
    history = []

    for ev_type, detail, raw in events:
        prev = (cursor_row, cursor_col)

        if ev_type == 'CUP':
            parts = detail.split()
            cursor_row = int(parts[0].split('=')[1])
            cursor_col = int(parts[1].split('=')[1])
        elif ev_type == 'VPA':
            cursor_row = int(detail.split('=')[1])
        elif ev_type == 'CHA':
            cursor_col = int(detail.split('=')[1])
        elif ev_type == 'CUU':
            n = int(detail.split('=')[1])
            cursor_row = max(scroll_top, cursor_row - n)
        elif ev_type == 'CUD':
            n = int(detail.split('=')[1])
            cursor_row = min(scroll_bottom, cursor_row + n)
        elif ev_type == 'CUF':
            n = int(detail.split('=')[1])
            cursor_col = min(cols, cursor_col + n)
        elif ev_type == 'CUB':
            n = int(detail.split('=')[1])
            cursor_col = max(1, cursor_col - n)
        elif ev_type == 'CR':
            cursor_col = 1
        elif ev_type == 'LF':
            if cursor_row >= scroll_bottom:
                pass  # scroll, cursor stays
            else:
                cursor_row += 1
        elif ev_type == 'IND':
            if cursor_row >= scroll_bottom:
                pass  # scroll
            else:
                cursor_row += 1
        elif ev_type == 'NEL':
            cursor_col = 1
            if cursor_row >= scroll_bottom:
                pass  # scroll
            else:
                cursor_row += 1
        elif ev_type == 'RI':
            if cursor_row <= scroll_top:
                pass  # scroll down
            else:
                cursor_row -= 1
        elif ev_type == 'DECSTBM':
            parts = detail.split()
            scroll_top = int(parts[0].split('=')[1])
            scroll_bottom = int(parts[1].split('=')[1])
            cursor_row = 1
            cursor_col = 1
        elif ev_type == 'SAVE_CURSOR':
            saved_row = cursor_row
            saved_col = cursor_col
        elif ev_type == 'RESTORE_CURSOR':
            cursor_row = saved_row
            cursor_col = saved_col
        elif ev_type == 'TEXT':
            text = detail
            cursor_col += len(text)
            # Wrap detection (simplified)
            while cursor_col > cols:
                cursor_col -= cols
                if cursor_row < scroll_bottom:
                    cursor_row += 1

        if (cursor_row, cursor_col) != prev and ev_type not in ('SGR', 'TEXT'):
            history.append({
                'type': ev_type,
                'detail': detail,
                'cursor': f'row={cursor_row} col={cursor_col}',
                'scroll_region': f'{scroll_top}-{scroll_bottom}',
            })

    return {
        'final_cursor_row': cursor_row,
        'final_cursor_col': cursor_col,
        'scroll_top': scroll_top,
        'scroll_bottom': scroll_bottom,
        'history': history,
    }


async def diagnose():
    print(f"=== Terminal Cursor Diagnostic ===")
    print(f"Backend:  ws://{BACKEND_HOST}:{BACKEND_PORT}")
    print(f"Session:  {SESSION_NAME}")
    print(f"Size:     {COLS}x{ROWS}")
    print(f"Command:  {COMMAND}")
    print()

    try:
        async with websockets.connect(WS_URL) as ws:
            print("[1] Connected to WebSocket")

            # Phase 1: Attach to session
            attach_msg = json.dumps({
                'type': 'attach-session',
                'sessionName': SESSION_NAME,
                'cols': COLS,
                'rows': ROWS,
                'windowIndex': 0,
            })
            await ws.send(attach_msg)
            print(f"[2] Sent attach-session ({COLS}x{ROWS})")

            # Phase 2: Drain bootstrap/history output (wait for stability)
            all_output = []
            bootstrap_done = False
            print("[3] Waiting for history bootstrap to complete...")

            deadline = time.time() + 10  # 10s max for bootstrap
            while time.time() < deadline:
                try:
                    msg = await asyncio.wait_for(ws.recv(), timeout=2.0)
                    data = json.loads(msg)
                    msg_type = data.get('type', '')

                    if msg_type == 'terminal-history-end':
                        bootstrap_done = True
                        print("    History bootstrap complete")
                    elif msg_type == 'output':
                        if bootstrap_done:
                            # We're getting live output after bootstrap
                            break
                except asyncio.TimeoutError:
                    if bootstrap_done:
                        break
                    print("    (waiting...)")

            if not bootstrap_done:
                print("    WARNING: History bootstrap did not complete, proceeding anyway")

            # Drain any remaining buffered output
            drained = 0
            while True:
                try:
                    msg = await asyncio.wait_for(ws.recv(), timeout=1.0)
                    data = json.loads(msg)
                    if data.get('type') == 'output':
                        drained += 1
                except asyncio.TimeoutError:
                    break
            if drained:
                print(f"    Drained {drained} buffered output messages")

            # Phase 3: Send a resize to ensure correct dimensions
            resize_msg = json.dumps({
                'type': 'resize',
                'cols': COLS,
                'rows': ROWS,
            })
            await ws.send(resize_msg)
            print(f"[4] Sent resize ({COLS}x{ROWS})")

            # Wait for tmux to process resize and re-render
            await asyncio.sleep(1.5)

            # Drain post-resize output
            pre_command_output = ""
            while True:
                try:
                    msg = await asyncio.wait_for(ws.recv(), timeout=0.5)
                    data = json.loads(msg)
                    if data.get('type') == 'output':
                        pre_command_output += data.get('data', '')
                except asyncio.TimeoutError:
                    break

            if pre_command_output:
                print(f"[5] Post-resize output: {len(pre_command_output)} bytes")
                # Analyze the scroll region in post-resize output
                events = parse_escape_sequences(pre_command_output)
                for ev_type, detail, raw in events:
                    if ev_type == 'DECSTBM':
                        print(f"    Scroll region after resize: {detail}")

            # Phase 4: Send the command
            input_msg = json.dumps({
                'type': 'input',
                'data': COMMAND + '\r',
            })
            await ws.send(input_msg)
            print(f"[6] Sent command: '{COMMAND}'")

            # Phase 5: Capture output for a few seconds
            command_output = ""
            output_chunks = []
            capture_start = time.time()
            capture_duration = 3.0  # capture for 3 seconds

            while time.time() - capture_start < capture_duration:
                try:
                    msg = await asyncio.wait_for(ws.recv(), timeout=0.5)
                    data = json.loads(msg)
                    if data.get('type') == 'output':
                        chunk = data.get('data', '')
                        command_output += chunk
                        output_chunks.append({
                            'time': time.time() - capture_start,
                            'size': len(chunk),
                            'data': chunk,
                        })
                except asyncio.TimeoutError:
                    continue

            print(f"[7] Captured {len(command_output)} bytes in {len(output_chunks)} chunks")
            print()

            # Phase 6: Parse and analyze
            print("=" * 70)
            print("RAW OUTPUT ANALYSIS")
            print("=" * 70)
            print()

            events = parse_escape_sequences(command_output)

            # Show all events (skip SGR for clarity)
            print("--- Escape Sequence Events (excluding SGR) ---")
            for i, (ev_type, detail, raw) in enumerate(events):
                if ev_type == 'SGR':
                    continue
                if ev_type == 'TEXT' and not raw:
                    print(f"  [{i:3d}] TEXT: {repr(detail)}")
                else:
                    print(f"  [{i:3d}] {ev_type}: {detail}")

            print()
            print("--- Cursor Position Simulation ---")
            sim = simulate_cursor(events, ROWS, COLS)
            print(f"  Terminal size: {COLS}x{ROWS}")
            print(f"  Scroll region: {sim['scroll_top']}-{sim['scroll_bottom']}")
            print(f"  Final cursor: row={sim['final_cursor_row']}, col={sim['final_cursor_col']}")
            print()

            if sim['history']:
                print("--- Cursor Movement History ---")
                for h in sim['history']:
                    print(f"  {h['type']:15s} {h['detail']:30s} → cursor={h['cursor']} scroll={h['scroll_region']}")

            print()

            # Phase 7: Key diagnostics
            print("=" * 70)
            print("DIAGNOSTICS")
            print("=" * 70)
            print()

            # Check scroll region vs terminal size
            expected_content_rows = ROWS  # or ROWS-1 if status bar
            if sim['scroll_bottom'] > ROWS:
                print(f"  ⚠️  SCROLL REGION EXCEEDS TERMINAL: bottom={sim['scroll_bottom']} > rows={ROWS}")
            elif sim['scroll_bottom'] == ROWS:
                print(f"  ℹ️  Scroll region = full terminal ({sim['scroll_top']}-{sim['scroll_bottom']}), no tmux status bar")
            elif sim['scroll_bottom'] == ROWS - 1:
                print(f"  ℹ️  Scroll region = {sim['scroll_top']}-{sim['scroll_bottom']}, tmux status bar on row {ROWS}")
            else:
                print(f"  ⚠️  Scroll region is smaller than expected: {sim['scroll_top']}-{sim['scroll_bottom']} for {ROWS}-row terminal")

            # Check final cursor position
            if sim['final_cursor_row'] > sim['scroll_bottom']:
                print(f"  🚨 CURSOR BELOW SCROLL REGION: cursor row={sim['final_cursor_row']} > bottom={sim['scroll_bottom']}")
            elif sim['final_cursor_row'] > ROWS:
                print(f"  🚨 CURSOR BELOW TERMINAL: cursor row={sim['final_cursor_row']} > rows={ROWS}")

            # Find where the command output text is
            prompt_events = [e for e in events if e[0] == 'TEXT' and ('$' in e[1] or '❯' in e[1] or '>' in e[1])]
            if prompt_events:
                print(f"  ℹ️  Prompt-like text found: {repr(prompt_events[-1][1][:50])}")

            # Check for DECSTBM in command output
            stbm_events = [e for e in events if e[0] == 'DECSTBM']
            if stbm_events:
                print(f"  ℹ️  Scroll region changes during command output:")
                for e in stbm_events:
                    print(f"       {e[1]}")

            # Check timing of chunks
            if output_chunks:
                print()
                print("--- Output Chunk Timing ---")
                for c in output_chunks:
                    print(f"  t={c['time']:.3f}s  {c['size']} bytes")

            # Also query tmux for its window size
            print()
            print("--- tmux Window Size Verification ---")
            import subprocess
            try:
                result = subprocess.run(
                    ['tmux', 'display-message', '-t', SESSION_NAME, '-p',
                     '#{window_width} #{window_height} #{pane_width} #{pane_height}'],
                    capture_output=True, text=True, timeout=5
                )
                if result.returncode == 0:
                    parts = result.stdout.strip().split()
                    if len(parts) == 4:
                        print(f"  tmux window: {parts[0]}x{parts[1]}")
                        print(f"  tmux pane:   {parts[2]}x{parts[3]}")
                        if int(parts[1]) != ROWS and int(parts[1]) != ROWS - 1:
                            print(f"  ⚠️  MISMATCH: tmux height={parts[1]} vs requested rows={ROWS}")
                        if int(parts[3]) != ROWS and int(parts[3]) != ROWS - 1:
                            print(f"  ⚠️  MISMATCH: tmux pane height={parts[3]} vs requested rows={ROWS}")
                    else:
                        print(f"  tmux output: {result.stdout.strip()}")
                else:
                    print(f"  tmux error: {result.stderr.strip()}")
            except Exception as e:
                print(f"  tmux query failed: {e}")

            # Detach cleanly
            print()
            print("[DONE] Diagnostic complete")

    except Exception as e:
        print(f"ERROR: {e}")
        import traceback
        traceback.print_exc()


if __name__ == '__main__':
    print("Usage: python3 diagnose_cursor.py [SESSION] [COLS] [ROWS] [COMMAND]")
    print()
    asyncio.run(diagnose())
