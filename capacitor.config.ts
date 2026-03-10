import type { CapacitorConfig } from "@capacitor/cli";

const config = {
  appId: "com.agentshell.app",
  appName: "AgentShell",
  webDir: "dist",
  cleartext: true,
  server: {
    androidScheme: "http",
  },
} as CapacitorConfig;

export default config;
