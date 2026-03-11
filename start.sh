#!/bin/bash

ACTION="${1:-start}"
SERVICE_NAME="agentshell"

case "$ACTION" in
    start)
        echo "Starting AgentShell service..."
        # Kill any stale process still holding port 4010
        STALE=$(lsof -ti :4010 2>/dev/null)
        [ -n "$STALE" ] && kill -9 $STALE 2>/dev/null && sleep 0.5
        sudo systemctl start "$SERVICE_NAME"
        echo "Service started."
        ;;
    stop)
        echo "Stopping AgentShell service..."
        sudo systemctl stop "$SERVICE_NAME"
        echo "Service stopped."
        ;;
    restart)
        echo "Restarting AgentShell service..."
        sudo systemctl restart "$SERVICE_NAME"
        echo "Service restarted."
        ;;
    logs)
        sudo journalctl -u "$SERVICE_NAME" -f --no-pager "${@:2}"
        ;;
    logs-full)
        sudo journalctl -u "$SERVICE_NAME" --no-pager "${@:2}"
        ;;
    status)
        sudo systemctl status "$SERVICE_NAME"
        ;;
    enable)
        sudo systemctl enable "$SERVICE_NAME"
        echo "Service enabled to start on boot."
        ;;
    disable)
        sudo systemctl disable "$SERVICE_NAME"
        echo "Service disabled."
        ;;
    *)
        echo "Usage: $0 {start|stop|restart|logs|logs-full|status|enable|disable}"
        echo ""
        echo "Commands:"
        echo "  start      - Start the service"
        echo "  stop       - Stop the service"
        echo "  restart    - Restart the service"
        echo "  logs       - Follow logs (Ctrl+C to exit)"
        echo "  logs-full  - View full logs from start"
        echo "  status     - Show service status"
        echo "  enable     - Enable service to start on boot"
        echo "  disable    - Disable service from starting on boot"
        exit 1
        ;;
esac
