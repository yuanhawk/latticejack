import { describe, expect, it } from "vitest";
import { verifyTurnstile } from "../src/turnstile";
import type { FetchLike } from "../src/azure";

function fakeFetch(handler: (url: string, init?: RequestInit) => Response): FetchLike {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    return handler(url, init);
  }) as FetchLike;
}

describe("verifyTurnstile", () => {
  it("returns success:false without calling siteverify when the token is empty", async () => {
    let called = false;
    const fetchImpl = fakeFetch(() => {
      called = true;
      return new Response(JSON.stringify({ success: true }), { status: 200 });
    });
    const result = await verifyTurnstile("secret", "", "1.2.3.4", fetchImpl);
    expect(result.success).toBe(false);
    expect(called).toBe(false);
  });

  it("posts secret/response/remoteip and returns success:true on a passing verification", async () => {
    let capturedBody = "";
    const fetchImpl = fakeFetch((url, init) => {
      expect(url).toBe("https://challenges.cloudflare.com/turnstile/v0/siteverify");
      capturedBody = String(init?.body ?? "");
      return new Response(JSON.stringify({ success: true }), { status: 200 });
    });
    const result = await verifyTurnstile("my-secret", "the-token", "9.9.9.9", fetchImpl);
    expect(result.success).toBe(true);
    expect(capturedBody).toContain("secret=my-secret");
    expect(capturedBody).toContain("response=the-token");
    expect(capturedBody).toContain("remoteip=9.9.9.9");
  });

  it("surfaces error-codes on a failed verification", async () => {
    const fetchImpl = fakeFetch(
      () =>
        new Response(JSON.stringify({ success: false, "error-codes": ["timeout-or-duplicate"] }), {
          status: 200,
        }),
    );
    const result = await verifyTurnstile("secret", "token", undefined, fetchImpl);
    expect(result.success).toBe(false);
    expect(result.errorCodes).toEqual(["timeout-or-duplicate"]);
  });

  it("treats a non-ok HTTP response as failure", async () => {
    const fetchImpl = fakeFetch(() => new Response("error", { status: 500 }));
    const result = await verifyTurnstile("secret", "token", undefined, fetchImpl);
    expect(result.success).toBe(false);
  });
});
