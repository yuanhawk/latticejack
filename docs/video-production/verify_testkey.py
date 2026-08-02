#!/usr/bin/env python3
"""Verify the always-pass Turnstile test key is live and actually passing
before attempting a real recorded run."""
from playwright.sync_api import sync_playwright

URL = "https://latticejack.itinerario.io"

with sync_playwright() as pw:
    browser = pw.chromium.launch(headless=False)
    page = browser.new_page(viewport={"width": 1280, "height": 800})
    page.goto(URL, wait_until="networkidle")
    page.wait_for_timeout(6000)

    token_val = page.evaluate(
        """() => {
            const el = document.querySelector('input[name="cf-turnstile-response"]');
            return el ? el.value : null;
        }"""
    )
    print(f"cf-turnstile-response present and non-empty: {bool(token_val)}")
    start_btn = page.get_by_role("button", name="Start demo")
    print(f"Start button enabled: {start_btn.is_enabled()}")
    page.screenshot(path="testkey_verify.png")
    browser.close()
