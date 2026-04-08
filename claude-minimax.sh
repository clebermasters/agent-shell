#!/bin/bash
# claude-minimax.sh - Start Claude Code with MiniMax

export ANTHROPIC_BASE_URL="https://api.minimax.io/anthropic"
export ANTHROPIC_AUTH_TOKEN="sk-cp-mJmYmRwMwPlGDP83K7qY_1B1QVC7w_pZB9R82b4aW1MkzCCAuHNRWr4fFd8dboNG36O96gcyh56C2hlh_qFdqQrmGhB01vTwVp-FFytvTwMg2k-Qi0_yvtM"
export API_TIMEOUT_MS="3000000"
export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
export ANTHROPIC_MODEL="MiniMax-M2.7-highspeed"
export ANTHROPIC_SMALL_FAST_MODEL="MiniMax-M2.7-highspeed"
export ANTHROPIC_DEFAULT_SONNET_MODEL="MiniMax-M2.7-highspeed"
export ANTHROPIC_DEFAULT_OPUS_MODEL="MiniMax-M2.7-highspeed"
export ANTHROPIC_DEFAULT_HAIKU_MODEL="MiniMax-M2.7-highspeed"

claude "$@"
