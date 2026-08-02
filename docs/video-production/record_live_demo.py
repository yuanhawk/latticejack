#!/usr/bin/env python3
"""Records one real end-to-end run of Latticejack's live demo
(latticejack.itinerario.io) via Playwright, while Turnstile is
temporarily in Cloudflare's documented always-pass test-key mode (see
docs/video-production/README.md for why this is legitimate -- the site
owner disabling their own check on their own infra, not evasion).

This triggers a REAL, BILLABLE Azure VM run. Only run this once
Turnstile's test keys are confirmed live (verify_testkey.py) and you
intend to actually record a take.

Output: one .webm video in OUT_DIR (Playwright's own video capture, not
a screen-capture tool -- captures exactly the page content at the
recorded viewport size).

Usage: python record_live_demo.py [out_dir]
"""
import sys
import time

from playwright.sync_api import sync_playwright

OUT_DIR = sys.argv[1] if len(sys.argv) > 1 else "screen_capture"
VIEWPORT = {"width": 1280, "height": 800}
URL = "https://latticejack.itinerario.io"
# Real runs measured this session: ~4-7 min end to end per the page's own
# copy. Generous ceiling so a slow VM boot doesn't truncate the capture.
TERMINAL_TIMEOUT_MS = 600_000


def main() -> int:
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=False)
        context = browser.new_context(
            viewport=VIEWPORT,
            record_video_dir=OUT_DIR,
            record_video_size=VIEWPORT,
            color_scheme="dark",  # match the real human-solved Turnstile capture
        )
        page = context.new_page()

        print(f"Loading {URL} ...")
        page.goto(URL, wait_until="networkidle")

        print("Waiting for Turnstile test-key auto-pass...")
        page.wait_for_timeout(6000)
        token_val = page.evaluate(
            """() => {
                const el = document.querySelector('input[name="cf-turnstile-response"]');
                return el ? el.value : null;
            }"""
        )
        if not token_val:
            print("ERROR: Turnstile did not auto-pass -- are the test keys "
                  "actually deployed? Aborting without clicking Start.")
            context.close()
            browser.close()
            return 1

        start_btn = page.get_by_role("button", name="Start demo")
        start_btn.wait_for(state="visible", timeout=10000)
        print("Clicking Start -- this triggers a REAL Azure VM run now.")
        t0 = time.time()
        start_btn.click()

        print(f"Waiting up to {TERMINAL_TIMEOUT_MS/1000:.0f}s for a terminal state...")
        end_state = page.locator("#end-state")
        end_state.wait_for(state="visible", timeout=TERMINAL_TIMEOUT_MS)
        elapsed = time.time() - t0
        title = page.locator("#end-state-title").text_content()
        print(f"Terminal state reached after {elapsed:.1f}s: {title}")

        # Let the final numbers/verdict sit on screen for a beat before
        # cutting the recording.
        page.wait_for_timeout(2000)

        # The real end-state summary card (handshake/ratio numbers) sits
        # below the fold at this viewport size -- previous recordings never
        # scrolled to it, so the real numbers never appeared in captured
        # footage. Scroll it into view for real this time.
        print("Scrolling to the real end-state summary card...")
        end_state.scroll_into_view_if_needed()
        page.wait_for_timeout(4000)

        print("Closing context (finalizes the .webm)...")
        context.close()
        browser.close()

    print(f"\nDone. Real captured footage in {OUT_DIR}/ -- "
          "cut/speed-ramp in the ffmpeg edit pass per docs/demo-video-script.md's "
          "notes (real time for the stage_start/VERIFIED beats, sped up for "
          "the VM-boot/build dead time).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
