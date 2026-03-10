/**
 * WebMux Plugin for OpenCode
 *
 * Injects WebMux environment variables for skills:
 * - WEBMUX_ACP_SESSION_ID: Current ACP session ID
 * - WEBMUX_ACP_CWD: Current ACP session working directory
 * - WEBMUX_WS_URL: WebSocket URL for WebMux
 *
 * Reads session info from ~/.webmux/acp_session
 */

import path from 'path';
import fs from 'fs';
import os from 'os';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const DEFAULT_WS_URL = 'ws://localhost:5173/ws';
const SESSION_FILE = '.webmux/acp_session';

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
    console.error('[webmux-plugin] Error reading session file:', error.message);
  }
  
  return null;
};

export const WebMuxPlugin = async ({ client, directory }) => {
  return {
    /**
     * Shell environment hook - injects WebMux env vars for every shell command
     */
    'shell.env': async (input, output) => {
      const session = readSessionFile();
      
      if (session) {
        output.env = {
          WEBMUX_ACP_SESSION_ID: session.sessionId || '',
          WEBMUX_ACP_CWD: session.cwd || '',
          WEBMUX_WS_URL: session.wsUrl || DEFAULT_WS_URL,
        };
        
        console.log('[webmux-plugin] Set WebMux environment variables:', {
          sessionId: session.sessionId,
          cwd: session.cwd,
        });
      }
    },
    
    /**
     * System prompt transform - add WebMux context to system prompt
     */
    'experimental.chat.system.transform': async (_input, output) => {
      const session = readSessionFile();
      
      if (session && session.sessionId) {
        const context = `
<webmux-context>
Current WebMux ACP Session:
- Session ID: ${session.sessionId}
- Working Directory: ${session.cwd}
- WebSocket: ${session.wsUrl || DEFAULT_WS_URL}

Skills can use these environment variables:
- WEBMUX_ACP_SESSION_ID
- WEBMUX_ACP_CWD  
- WEBMUX_WS_URL
</webmux-context>
`;
        (output.system ||= []).push(context);
      }
    },
  };
};
