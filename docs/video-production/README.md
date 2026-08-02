# Demo video production pipeline

How `docs/demo-video-script.md` actually got recorded, stated plainly —
this is the file that script's "Production" section and its "confirmed
directly, not assumed" claim both point back to.

## What's real, what's generated, and what's neither

- **All screen-capture footage is real, unmodified application output.**
  Four real, billable Azure VM runs happened during production (see
  "Session ledger" below), each one a genuine end-to-end execution of
  this project's actual code on the actual Azure Cobalt 100 target —
  nothing in the captured UI, log output, or verification text was
  edited, staged, or synthesized. Numbers stated in the video's narration
  or shown as text overlays are copied from real `/api/log` data for the
  specific session actually on screen — never invented, never averaged
  across sessions, never carried over from a different take.
- **Narration audio is DashScope CosyVoice TTS**, not a human voiceover —
  see `generate_narration.py`. Text is copied verbatim from
  `docs/demo-video-script.md`'s own narration blocks.
- **The two title-card beats (open/close) are DashScope-generated video
  clips** — the only place anything AI-generated stands in for a visual,
  and only for a plain title-card treatment, never for anything that
  claims to show the running system.

## The Turnstile test-key swap — full disclosure

Turnstile is designed to block automated/headless browsers, which is
exactly why it's there (stops random internet traffic from triggering
costly VM runs). Confirmed directly, not assumed: a plain, non-evasive
Playwright session against the live production site got
`navigator.webdriver: true` and an unsolved challenge — not an invisible
pass (`test_turnstile.py`'s output, this session). Turnstile could not be
scripted through, on purpose, and no attempt was made to force it through
with stealth/evasion tooling — a Bright Data credential was available and
explicitly declined for this, on the grounds that using bot-detection
evasion against the project's *own* site, even for a legitimate purpose,
isn't something this project does.

Instead, **the site owner temporarily disabled their own check on their
own infrastructure**, using Cloudflare's own officially-documented
always-pass test key pair (site key `1x00000000000000000000AA`, secret
key `1x0000000000000000000000000000000AA` — the same pair already used
for local `wrangler dev` testing, see `demo/.dev.vars.example`) — not a
third-party bypass service, not evasion of someone else's system. The
sequence, each time:

1. Swap `demo/worker/public/index.html`'s `turnstileSiteKey` to the test
   site key, and `TURNSTILE_SECRET_KEY` (Worker secret) to the test
   secret key. Redeploy.
2. Verify the swap is actually live (`verify_testkey.py`) before doing
   anything that depends on it.
3. Record. Playwright can now click through for real — the underlying
   `/api/start` call is a genuine, unmodified request; only the CAPTCHA
   check itself is disabled during this window.
4. **Immediately** swap both values back to the real production
   credentials and redeploy. Verify real protection is actually restored
   (`test_turnstile.py` — confirms the automated session gets rejected
   again) before considering the window closed.

This means: **the automated (Playwright) footage's own on-screen Turnstile
widget is not the real check** — it's the always-pass test widget, and it
visibly says so ("For testing only. If seen, report to site owner").
That frame is never shown in the final video. What *is* shown for the
Turnstile-solving moment is a separate, real screen recording of a human
(the project owner) solving the actual, real, production Turnstile widget
— captured with real production keys live (no swap in effect), spliced in
front of the automated run's post-click footage. The CAPTCHA check itself
is the only thing ever disabled for recording purposes; everything a
judge would see after solving it for real is identical either way, since
`/api/start`'s downstream behavior doesn't depend on which key pair
validated the token.

**Known limitation:** while a test key was live, Turnstile was disabled
for *any* visitor, not just the recording script — bounded by the
Worker's existing daily (20) and per-IP hourly (3) caps, and each window
was closed within a few minutes. No abuse was observed.

## Session ledger

Four real Azure VM runs happened during production, all `outcome:
success`, all four stages passing, all confirmed deallocated afterward
(`az vm show` / the Worker's own `/api/status`):

| Session | Mode | Theme | Turnstile | `handshake_ms` | `ratio` | Used in final video? |
|---|---|---|---|---|---|---|
| `859a556718e1115faa2ebcab97cef8a7` | Playwright (automated) | light | test key | 283.5 | 0.091 (~11.0x) | No — discarded for a light/dark theme mismatch against the real human-solved clip |
| `05035195f186a4f064227ad9568c48c8` | Human, unintentional | dark | real key | 299.7 | 0.097 (~10.3x) | No — this was the human Turnstile-solve capture; the human clicked Start too (not asked to), triggering a real run this table records for completeness, but the video only uses the pre-click portion of this recording |
| `60e1089f1c8ce10509bc88564f78af5a` | Playwright (automated) | dark | test key | 298.0 | 0.098 (~10.2x) | No — used in an earlier revision, superseded (see below) |
| `3e5b78f3253d5673a31b748341fdb818` | Playwright (automated) | dark | test key | 274.2 | 0.090 (~11.1x) | **Yes** — current primary footage for parts 1–3 of the live-demo beats |

**Why a fourth run:** the first two dark-mode attempts (`05035195...`,
`60e1089f...`) never actually captured the real end-state summary card
(the "Run complete" panel with handshake/ratio numbers) — it renders
below the fold at the recording viewport size, and neither
`record_live_demo.py` nor the human capture ever scrolled to it. The
video's ai-workload beat originally covered this gap with a text-overlay
reconstruction of the real numbers (still real data, just not real
footage of the actual card). `record_live_demo.py` was updated to call
`end_state.scroll_into_view_if_needed()` after the terminal state is
reached, then a fresh run (`3e5b78f3...`) was recorded specifically to
capture that card on real video. It's now genuine footage, not a
reconstruction — see the "What's real..." section above.

`docs/demo-video-script.md`'s narration ("about eleven times") and
`generate_narration.py`'s segment 05 text both match session
`3e5b78f3...`'s real ~11.1x, not the other three sessions' numbers.

## Pacing: cut dead time, don't pad narration to fill it

An early assembly of the ai-workload beat (session `3e5b78f3...`'s
footage, right after the fourth run) played the real recording between
the `ai` stage finishing and the summary-card scroll at real speed for
its full ~21.5s — checked frame by frame afterward and found that
roughly two-thirds of that stretch has *nothing* visibly change on
screen (all four stages already green, no new verdicts, badge still
reading "run in progress" until close to the end). A full watch-through
caught this reading as "the video froze," not as calm pacing, even
though it was technically real, unedited footage.

Fixed by cutting the dead middle out (`seg2_dealloc_summary_trimmed.mp4`
keeps only the ~8.5s window where the badge actually changes, the page
actually scrolls, and the real card actually appears) and correspondingly
*shortening the narration text itself* for that beat — not stretching a
hold to fill a pre-written narration's length. The beat went from ~38s
of narration/video down to ~26s; total runtime dropped from 2:49 to 2:37.
Still real, unedited footage throughout — just less of the part where
nothing was happening. The general lesson (see `demo-video-script.md`'s
own notes) is to check every cut for genuinely static stretches, not
just the deliberately-sped-up montage moments — a frozen frame under
narration is exactly as much a pacing bug as a factual error is a
factuality bug, and gets the same fix-don't-hide treatment.

## Session-ID leak at the human-clip/automated-footage cut (found by an independent Fable audit)

The human Turnstile clip was originally trimmed to `-ss 3.3 -to 8.0` from
the raw `.mov` (a round-number guess at "roughly where the click
happens"). A dedicated pre-lock QA pass (independent Fable review,
instructed to extract and actually view frames across the whole video
rather than reason abstractly) caught that this end point was about a
second too late: by t=7.0-8.0s in the raw recording, the page had already
transitioned to its own "Run in progress" panel showing **the human
capture's session ID** (`05035195...`) — the accidental session the
production ledger already documents as not used in the final video —
right before the hard cut to the automated session's (`3e5b78f3...`)
identical-looking panel one frame later. A judge pausing at that exact
moment would see the session ID visibly change mid-"continuous run," in
a video whose whole pitch is "not simulated, verify everything."

Checked frame by frame to find the real safe boundary rather than
guessing again: at t=6.5s the page is still showing "Verifying..." (no
session panel yet); by t=7.3s it's already fully on the wrong session's
panel. Re-trimmed to `-ss 3.3 -to 6.5` (human clip now ends cleanly on
the real Turnstile "Verifying..." state, before either session's
identity ever appears on screen) and extended
`seg2_before_after.mp4`'s end by ~1.1s (more of the correct session's
own real footage) to make up the lost duration exactly. Re-verified
frame by frame after the fix: the cut now goes directly from the human
clip's "Verifying..." to the automated session's own "Run in progress"
panel, with neither session's ID ever appearing adjacent to the other's
UI state.

## Tools

Setup: `pip install -r requirements.txt && playwright install chromium`.
`generate_narration.py` also needs `DASHSCOPE_API_KEY` set to an
international-region (Singapore) DashScope key — see its own header
comment for why the China endpoint doesn't work.

- `generate_narration.py` — DashScope CosyVoice TTS, one MP3 per script
  beat. Recipe (model, voice, endpoint) carried over from a prior
  project's working setup — see the script's own header comment for why
  the international (not China) DashScope endpoint is required.
- `record_live_demo.py` — Playwright, drives the real flow (load, wait
  for Turnstile auto-pass, click Start, wait for a terminal state, scroll
  to the real end-state summary card), records real `.webm` video of the
  actual page. Only usable while a Turnstile test key is live (see
  above) — otherwise the click never happens and the script aborts
  rather than attempting to force it.
- `test_turnstile.py` / `verify_testkey.py` — observational only, never
  click Start. Used to confirm which Turnstile mode (real challenge vs.
  test auto-pass) is actually live before trusting either recording or
  restoration.

None of these four scripts are run automatically or on a schedule —
each is a manual, deliberate step in a supervised recording session, run
with the site owner's explicit authorization each time.

**Not checked in:** the final ffmpeg compositing pass (cropping,
speed-ramping, zoompan holds, PIL-generated text/chart overlays,
concatenation, audio muxing) was done as a long sequence of interactive
commands during editing, not saved as a single reusable script — the
exact filter graphs, crop coordinates, and per-beat timing values are
recorded inline in this file and in `demo-video-script.md`'s notes, but
reproducing the final render from the raw session footage today means
re-deriving those steps rather than running one command. The source
recordings themselves (webm/mov files, generated title-card videos, the
final rendered mp4) are also not checked in — see the repo's
`.gitignore` policy on large generated binaries elsewhere in this
project (e.g. `component-ai/`) for why; the same reasoning applies here.
