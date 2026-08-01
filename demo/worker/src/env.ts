// Shared Env (Worker bindings + vars/secrets) type. One definition used by
// both worker.ts and orchestrator.ts so they can't drift apart.

export interface Env {
  // Bindings
  ASSETS: Fetcher;
  DEMO_ORCHESTRATOR: DurableObjectNamespace;
  LAST_RUN_KV: KVNamespace;

  // Non-secret vars (see wrangler.toml [vars])
  AZURE_TENANT_ID: string;
  AZURE_CLIENT_ID: string;
  AZURE_SUBSCRIPTION_ID: string;
  AZURE_RESOURCE_GROUP: string;
  AZURE_VM_NAME: string;
  AZURE_API_VERSION: string;
  DEMO_DAILY_CAP: string;
  DEMO_PER_IP_HOURLY_CAP: string;
  DEMO_ALARM_INTERVAL_SECONDS: string;
  DEMO_HARD_CAP_SECONDS: string;
  DEMO_SILENCE_TIMEOUT_SECONDS: string;
  DEMO_VM_SCRIPT_PATH: string;

  // Secrets (`wrangler secret put ...` / .dev.vars locally)
  AZURE_CLIENT_SECRET: string;
  TURNSTILE_SECRET_KEY: string;
}

export function azureConfigFromEnv(env: Env) {
  return {
    tenantId: env.AZURE_TENANT_ID,
    clientId: env.AZURE_CLIENT_ID,
    clientSecret: env.AZURE_CLIENT_SECRET,
    subscriptionId: env.AZURE_SUBSCRIPTION_ID,
    resourceGroup: env.AZURE_RESOURCE_GROUP,
    vmName: env.AZURE_VM_NAME,
    apiVersion: env.AZURE_API_VERSION,
  };
}
