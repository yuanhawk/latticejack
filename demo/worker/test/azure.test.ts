import { describe, expect, it, vi } from "vitest";
import {
  AzureApiError,
  composeRunCommandScript,
  deallocateVm,
  dispatchRunCommand,
  fetchAadToken,
  getInstanceViewPowerState,
  startVm,
  type FetchLike,
} from "../src/azure";
import type { AzureConfig } from "../src/types";

const cfg: AzureConfig = {
  tenantId: "tenant-1",
  clientId: "client-1",
  clientSecret: "secret-1",
  subscriptionId: "sub-1",
  resourceGroup: "rg-1",
  vmName: "vm-1",
  apiVersion: "2024-07-01",
};

function fakeFetch(handler: (url: string, init?: RequestInit) => Response): FetchLike {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    return handler(url, init);
  }) as FetchLike;
}

describe("fetchAadToken", () => {
  it("posts client-credentials grant to the tenant token endpoint and returns the token", async () => {
    let capturedUrl = "";
    let capturedBody = "";
    const fetchImpl = fakeFetch((url, init) => {
      capturedUrl = url;
      capturedBody = String(init?.body ?? "");
      return new Response(JSON.stringify({ access_token: "tok-abc", expires_in: 3600 }), {
        status: 200,
      });
    });
    const result = await fetchAadToken(cfg, fetchImpl);
    expect(capturedUrl).toBe("https://login.microsoftonline.com/tenant-1/oauth2/v2.0/token");
    expect(capturedBody).toContain("grant_type=client_credentials");
    expect(capturedBody).toContain("scope=https%3A%2F%2Fmanagement.azure.com%2F.default");
    expect(result).toEqual({ access_token: "tok-abc", expires_in: 3600 });
  });

  it("throws AzureApiError on a non-2xx response", async () => {
    const fetchImpl = fakeFetch(() => new Response("bad creds", { status: 401 }));
    await expect(fetchAadToken(cfg, fetchImpl)).rejects.toBeInstanceOf(AzureApiError);
  });
});

describe("startVm / deallocateVm", () => {
  it("startVm posts to the /start endpoint with the api-version and bearer token", async () => {
    let capturedUrl = "";
    let capturedAuth = "";
    const fetchImpl = fakeFetch((url, init) => {
      capturedUrl = url;
      capturedAuth = String((init?.headers as Record<string, string>)?.Authorization ?? "");
      return new Response("", { status: 202 });
    });
    await startVm(cfg, "bearer-tok", fetchImpl);
    expect(capturedUrl).toBe(
      "https://management.azure.com/subscriptions/sub-1/resourceGroups/rg-1/providers/Microsoft.Compute/virtualMachines/vm-1/start?api-version=2024-07-01",
    );
    expect(capturedAuth).toBe("Bearer bearer-tok");
  });

  it("deallocateVm posts to /deallocate and accepts 200 or 202", async () => {
    const fetchImpl = fakeFetch((url) => {
      expect(url).toContain("/deallocate?api-version=");
      return new Response("", { status: 200 });
    });
    await expect(deallocateVm(cfg, "tok", fetchImpl)).resolves.toBeUndefined();
  });

  it("throws AzureApiError on unexpected status codes", async () => {
    const fetchImpl = fakeFetch(() => new Response("nope", { status: 403 }));
    await expect(startVm(cfg, "tok", fetchImpl)).rejects.toBeInstanceOf(AzureApiError);
  });
});

describe("getInstanceViewPowerState", () => {
  it("extracts the PowerState/* code from the statuses array", async () => {
    const fetchImpl = fakeFetch(() =>
      new Response(
        JSON.stringify({
          statuses: [
            { code: "ProvisioningState/succeeded" },
            { code: "PowerState/running" },
          ],
        }),
        { status: 200 },
      ),
    );
    const state = await getInstanceViewPowerState(cfg, "tok", fetchImpl);
    expect(state).toBe("PowerState/running");
  });

  it("returns PowerState/unknown when no PowerState/* code is present", async () => {
    const fetchImpl = fakeFetch(
      () => new Response(JSON.stringify({ statuses: [] }), { status: 200 }),
    );
    const state = await getInstanceViewPowerState(cfg, "tok", fetchImpl);
    expect(state).toBe("PowerState/unknown");
  });

  it("throws on non-200", async () => {
    const fetchImpl = fakeFetch(() => new Response("err", { status: 500 }));
    await expect(getInstanceViewPowerState(cfg, "tok", fetchImpl)).rejects.toBeInstanceOf(
      AzureApiError,
    );
  });
});

describe("composeRunCommandScript", () => {
  it("uses systemd-run with a setsid/nohup fallback, never a bare nohup+disown", () => {
    const lines = composeRunCommandScript("sess-1", "tok-1", "/opt/latticejack-demo/run-demo.sh");
    const script = lines.join("\n");
    expect(script).toContain("systemd-run --unit=latticejack-demo-sess-1 --scope --");
    expect(script).toContain("setsid nohup /opt/latticejack-demo/run-demo.sh sess-1 tok-1");
    expect(script).toContain("sess-1");
    expect(script).toContain("tok-1");
    // The dangerous pattern (bare `nohup ... & disown` with no setsid/systemd-run
    // wrapping) must never appear as the sole detachment mechanism.
    expect(script).not.toMatch(/^\s*nohup .* & *disown/m);
  });
});

describe("dispatchRunCommand", () => {
  it("succeeds on the first attempt without sleeping", async () => {
    let calls = 0;
    const fetchImpl = fakeFetch(() => {
      calls++;
      return new Response("{}", { status: 202 });
    });
    const sleep = vi.fn(() => Promise.resolve());
    await dispatchRunCommand(cfg, "tok", ["echo hi"], fetchImpl, 3, 20_000, sleep);
    expect(calls).toBe(1);
    expect(sleep).not.toHaveBeenCalled();
  });

  it("retries on failure up to maxAttempts, sleeping between attempts", async () => {
    let calls = 0;
    const fetchImpl = fakeFetch(() => {
      calls++;
      return new Response("still warming up", { status: 502 });
    });
    const sleep = vi.fn(() => Promise.resolve());
    await expect(
      dispatchRunCommand(cfg, "tok", ["echo hi"], fetchImpl, 3, 20_000, sleep),
    ).rejects.toBeInstanceOf(AzureApiError);
    expect(calls).toBe(3);
    expect(sleep).toHaveBeenCalledTimes(2); // slept between attempts 1->2 and 2->3, not after the last
  });

  it("succeeds on a later attempt after earlier failures", async () => {
    let calls = 0;
    const fetchImpl = fakeFetch(() => {
      calls++;
      if (calls < 3) return new Response("nope", { status: 500 });
      return new Response("{}", { status: 200 });
    });
    const sleep = vi.fn(() => Promise.resolve());
    await dispatchRunCommand(cfg, "tok", ["echo hi"], fetchImpl, 3, 20_000, sleep);
    expect(calls).toBe(3);
  });
});
