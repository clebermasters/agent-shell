#!/bin/bash
# Debug script to check AgentShell chat history

DB_PATH="/opt/agentshell/backend/chat_events.db"

echo "=== AgentShell Chat History Debug ==="
echo ""

# Check if database exists
if [ ! -f "$DB_PATH" ]; then
    echo "ERROR: Database not found at $DB_PATH"
    exit 1
fi

echo "Database location: $DB_PATH"
echo "Database size: $(du -h $DB_PATH | cut -f1)"
echo ""

# List all sessions with message counts
echo "=== Sessions in database ==="
python3 << EOF
import sqlite3
import sys

db_path = "$DB_PATH"

try:
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()
    
    # Get all sessions
    cursor.execute("""
        SELECT session_name, COUNT(*) as count, MIN(timestamp_millis) as oldest, MAX(timestamp_millis) as newest
        FROM chat_events
        GROUP BY session_name
        ORDER BY newest DESC
    """)
    
    print(f"{'Session Name':<50} {'Count':>6} {'Oldest':>20} {'Newest':>20}")
    print("-" * 100)
    
    for row in cursor.fetchall():
        import datetime
        oldest = datetime.datetime.fromtimestamp(row['oldest']/1000).strftime('%Y-%m-%d %H:%M') if row['oldest'] else 'N/A'
        newest = datetime.datetime.fromtimestamp(row['newest']/1000).strftime('%Y-%m-%d %H:%M') if row['newest'] else 'N/A'
        print(f"{row['session_name']:<50} {row['count']:>6} {oldest:>20} {newest:>20}")
    
    print("")
    
    # Get sample messages for ACP sessions
    cursor.execute("""
        SELECT session_name, message_json, timestamp_millis
        FROM chat_events
        WHERE session_name LIKE 'acp_%'
        ORDER BY timestamp_millis DESC
        LIMIT 5
    """)
    
    print("=== Recent ACP Messages ===")
    for row in cursor.fetchall():
        import json
        from datetime import datetime
        try:
            msg = json.loads(row['message_json'])
            ts = datetime.fromtimestamp(row['timestamp_millis']/1000).strftime('%Y-%m-%d %H:%M')
            role = msg.get('role', 'unknown')
            blocks = msg.get('blocks', [])
            block_types = [b.get('text', b.get('name', b.get('tool_name', 'unknown')))[:30] for b in blocks]
            print(f"[{ts}] {row['session_name']:<30} role={role:<10} blocks={block_types}")
        except Exception as e:
            print(f"Error parsing: {e}")
    
    conn.close()
    
except Exception as e:
    print(f"ERROR: {e}")
    sys.exit(1)
EOF
