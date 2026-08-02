#!/usr/bin/env python3
"""Cheap, non-costly test: load latticejack.itinerario.io in a real
(headed) Chromium via Playwright, no automation stealth/evasion, and see
whether Cloudflare Turnstile passes invisibly or shows a challenge.
Does NOT click Start -- purely observational, so no VM gets triggered
even if this succeeds."""
import time
from playwright.sync_api import sync_playwright

URL = "https://latticejack.itinerario.io"

with sync_playwright() as pw:
    browser = pw.chromium.launch(headless=False)
    page = browser.new_page(viewport={"width": 1280, "height": 800})
    page.goto(URL, wait_until="networkidle")
    print("Page loaded. Waiting 8s for Turnstile to settle...")
    page.wait_for_timeout(8000)

    # Turnstile renders into a div/iframe; check for its response token
    # input (cf-turnstile-response) being populated -- that's the real
    # signal it passed, not just that the widget is visually present.
    token_val = page.evaluate(
        """() => {
            const el = document.querySelector('input[name="cf-turnstile-response"]');
            return el ? el.value : null;
        }"""
    )
    print(f"cf-turnstile-response length: {len(token_val) if token_val else 0}")
    print(f"cf-turnstile-response present and non-empty: {bool(token_val)}")

    # Also grab a screenshot for visual confirmation either way.
    page.screenshot(path="turnstile_test.png")
    print("Screenshot saved to turnstile_test.png")

    # Check navigator.webdriver -- a classic automation tell, informative
    # even though CDP-level detection doesn't strictly require this.
    webdriver_flag = page.evaluate("() => navigator.webdriver")
    print(f"navigator.webdriver: {webdriver_flag}")

    time.sleep(2)
    browser.close()
