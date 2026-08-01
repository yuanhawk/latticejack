import type { FetchLike } from "./azure";

const SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

export interface TurnstileResult {
  success: boolean;
  errorCodes?: string[];
}

/**
 * Server-side verification of a Turnstile token against Cloudflare's
 * siteverify endpoint. Never trust a client-supplied "I passed the
 * challenge" flag without this round trip.
 */
export async function verifyTurnstile(
  secretKey: string,
  token: string,
  remoteIp: string | undefined,
  fetchImpl: FetchLike = fetch,
): Promise<TurnstileResult> {
  if (!token) {
    return { success: false, errorCodes: ["missing-input-response"] };
  }
  const body = new URLSearchParams({ secret: secretKey, response: token });
  if (remoteIp) body.set("remoteip", remoteIp);

  const res = await fetchImpl(SITEVERIFY_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: body.toString(),
  });
  if (!res.ok) {
    return { success: false, errorCodes: [`siteverify-http-${res.status}`] };
  }
  const json = (await res.json()) as { success: boolean; "error-codes"?: string[] };
  return { success: json.success === true, errorCodes: json["error-codes"] };
}
