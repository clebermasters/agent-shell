/**
 * AgentShell Plugin for OpenCode
 *
 * Injects AgentShell environment variables for skills:
 * - AGENTSHELL_ACP_SESSION_ID: Current ACP session ID
 * - AGENTSHELL_ACP_CWD: Current ACP session working directory
 * - AGENTSHELL_WS_URL: WebSocket URL for AgentShell
 *
 * Reads session info from ~/.agentshell/acp_session
 */

import path from 'path';
import fs from 'fs';
import os from 'os';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const DEFAULT_WS_URL = 'ws://localhost:5173/ws';
const SESSION_FILE = '.agentshell/acp_session';

const getSessionFilePath = () => {
  const homeDir = os.homedir();
  return path.join(homeDir, SESSION_FILE);
};

const readSessionFile = () => {
  const sessionFile = getSessionFilePath();
  
  try {
    if (fs.existsSync(sessionFile)) {
      const content = fs.readFileSync(sessionFile, 'utf8').trim();
      if (content) {
        return JSON.parse(content);
      }
    }
  } catch (error) {
    console.error('[agentshell-plugin] Error reading session file:', error.message);
  }
  
  return null;
};

export const AgentShellPlugin = async ({ client, directory }) => {
  return {
    /**
     * Shell environment hook - injects AgentShell env vars for every shell command
     */
    'shell.env': async (input, output) => {
      const session = readSessionFile();
      
      if (session) {
        output.env = {
          AGENTSHELL_ACP_SESSION_ID: session.sessionId || '',
          AGENTSHELL_ACP_CWD: session.cwd || '',
          AGENTSHELL_WS_URL: session.wsUrl || DEFAULT_WS_URL,
        };
        
        console.log('[agentshell-plugin] Set AgentShell environment variables:', {
          sessionId: session.sessionId,
          cwd: session.cwd,
        });
      }
    },
    
    /**
     * System prompt transform - add AgentShell context to system prompt
     */
    'experimental.chat.system.transform': async (_input, output) => {
      const session = readSessionFile();
      
      if (session && session.sessionId) {
        const context = `
<agentshell-context>
Current AgentShell ACP Session:
- Session ID: ${session.sessionId}
- Working Directory: ${session.cwd}
- WebSocket: ${session.wsUrl || DEFAULT_WS_URL}

Skills can use these environment variables:
- AGENTSHELL_ACP_SESSION_ID
- AGENTSHELL_ACP_CWD  
- AGENTSHELL_WS_URL
</agentshell-context>
`;
        (output.system ||= []).push(context);
      }
    },
  };
};
