# Demo video script

Target: under 3 minutes (the official rules' hard cap — "Judges are not
required to watch beyond three minutes"). Written to be recordable
directly: each beat names the exact screen content, using data and
behavior already verified elsewhere in this repo — nothing here should
require improvising a new number on camera.

**Revised twice.** First to add the AI inference workload
(`benchmarks/ai-inference-pqc/`) after it landed. Second — this
revision — to show the real, now-deployed live-demo web feature
(`latticejack.itinerario.io`, see `demo/README.md`) instead of raw
terminal commands: a judge can watch the exact same real Azure Cobalt 100
run this video shows, live, themselves, whenever they want. That's a
stronger "not simulated" claim than a terminal recording can make on its
own, so it now carries most of the video.

Production: audio narration generated via DashScope CosyVoice TTS (see
`docs/video-production/generate_narration.py`); the two title-card beats
(open/close) as short DashScope-generated video clips; everything else is
real captured footage — no AI-generated visuals stand in for anything
that actually runs. Turnstile blocks automated/headless browsers by
design (confirmed directly: a plain Playwright session got `navigator.
webdriver: true` and an unsolved challenge, not an invisible pass) — the
live-demo footage was captured from a real human-driven browser session,
screen-recorded, not scripted automation. See
`docs/video-production/README.md` for the full recording/editing
pipeline.

---

## 0:00–0:15 — The problem (AI-generated title card)

**On screen:** short DashScope-generated video clip, repo name / plain
title card treatment — not literal footage of anything, just a visual
backdrop for the opening line.

**Narration:**
> "Java shops migrating to post-quantum crypto have no real playbook. The
> migration path is undocumented, and nobody's measured what it actually
> costs on Arm64 — the architecture a growing share of cloud AI
> infrastructure runs on. Latticejack is that playbook: a working
> migration, benchmarked and optimized entirely on real Arm64 hardware."

---

## 0:15–0:40 — It's real, not simulated (live web demo, part 1)

**On screen:** real screen recording, a real browser at
`latticejack.itinerario.io`:

1. Page loads — the "What this does" copy and the Turnstile checkbox are
   both visible for a beat (let the real Cloudflare Turnstile widget sit
   on screen — that's part of the "not simulated" claim: a judge doing
   this themselves goes through the exact same real bot-check).
2. Solve Turnstile, click **Start demo**.
3. VM boot (real, ~20–30s) — **sped up** in the edit, clearly, e.g. a
   visible fast-scrub or a small "sped up" on-screen label; don't present
   real-time footage as if it weren't edited.
4. Cut to real-time the moment `stage_start`/`log_chunk` events begin
   arriving in the log pane — let `before` and `after` both complete and
   their `VERIFIED:` text land on screen at real speed.

**What appears (verified — this is real `/api/log` output from an actual
run this session, `demo/README.md`'s "Then deployed live and run for
real" section):**
```
VERIFIED: HelloRetryRequest observed (2 ClientHellos) - consistent
with X25519MLKEM768 (the first-preference group) being negotiated, not a
silent fallback to secp256r1.
```

**Narration (over the boot montage, then the real-time VERIFIED lines):**
> "Click Start, and this boots a real Arm64 VM, on demand, and
> streams its raw output back to this page — not a recording, not
> simulated. Classical TLS, then hybrid post-quantum TLS, both running
> live, both self-verifying. That line matters: it doesn't just check the
> handshake completed, it checks the post-quantum group actually
> negotiated, not a silent fallback to classical."

**Update: "Azure" dropped from this line.** The project owner caught,
watching the published video with sound on, that this TTS voice
mispronounces "Azure" (reportedly as "Azour") — a real bug, not caught
earlier since generating audio doesn't include listening to it. Dropped
rather than respelled: the live-demo footage playing at this exact
moment already shows "Azure" as real on-screen text (the page's own copy
literally says "Clicking Start boots a real Azure VM"), so the spoken
line doesn't need to repeat it to keep the claim intact. Full account,
including two other terms fixed the same way (`Arm64`, and this
project's own name), in `docs/video-production/generate_narration.py`'s
header comment and `docs/video-production/README.md`.

---

## 0:40–1:10 — What it costs, and what closes the gap (chart)

**On screen:** `docs/charts/b1-latency.svg`, then `docs/charts/b2-levers.svg`.

**Narration:**
> "Going hybrid nearly doubles handshake latency on real hardware — 46 to
> 89 milliseconds at the median. So we measured eight different ways to
> claw that back, on the same real Arm64 silicon, not a laptop. Two came
> back null — reported as findings, not hidden. And two gave real wins,
> both named on screen: a hand-tuned NEON assembly library, four times
> faster per operation; and ahead-of-time native compilation,
> seven-point-nine times faster cold start."

**Note:** the original narration here said "mlkem-native's" and
"GraalVM native-image" explicitly — reworded after a watch-through
reported the audio as unintelligible at exactly those two phrases (timed
independently: 17.76–19.76s and 23.76–25.76s into this beat's narration
both landed squarely on those terms). CosyVoice (bilingual zh/en, not an
English specialist per `generate_narration.py`'s own header comment)
struggling with hyphenated technical proper nouns is the working theory,
not confirmed by ear directly since generating audio doesn't include
listening to it — the fix was to drop the exact jargon from narration
(both names are already legible on screen in the chart itself, so
nothing factual is lost) and re-check after regenerating.

---

## 1:10–1:45 — An AI audit caught a real security bug in our own code (live web demo, part 2)

**On screen:** back to the same real browser tab — the recording picked
up where part 1 left off (edited for time, not a new take): the log pane
now scrolled into the `nativekem` stage, its `VERIFIED:` block landing on
screen in real time.

**What appears (verified, real output):**
```
VERIFIED: [native-mlkem-provider] trace marker observed for keygen
(1), encaps (1), and decaps (1) - mlkem-native's
FFM path actually handled ML-KEM-768, not BC's pure-Java implementation.
```

**Narration:**
> "Here's what makes this different from a typical benchmark project:
> every claim in this repo was adversarially re-checked by independent AI
> models, run blind to each other — the same way you'd want a second human
> reviewer, but applied consistently, not once. One of those audits caught
> something real: our fastest optimization was deriving actual TLS session
> secrets from deterministic, non-cryptographic key material — a genuine
> security bug. We fixed it, verified two keygens now produce different
> keys where before they were identical, and re-measured on real hardware
> to see what the fix actually cost. That discipline runs through this
> whole project: every place a measurement turned out wrong, that's
> documented, not quietly corrected."

---

## 1:52–2:18 — A real AI workload, secured by the migration itself (live web demo, part 3)

**On screen:** same continuous recording, now the `ai` stage — let the
model reply and both `VERIFIED:` checks land on screen for a beat each.

**What appears** (verified against the actual print order and bracket
label in `PqcAiTlsClient.java`/`run-ai.sh` — the label is `[ai-pqc-kex]`,
not `[ai-client]`; this is real output, ratio varies run to run — the
committed 3-run table in `benchmarks/ai-inference-pqc/README.md` shows
9.1x–9.8x, ~9.4x average, the real live-demo verification run in
`demo/README.md` landed at ~10.3x, and the actual take used in this video
(dark-mode capture, re-recorded a third time specifically to capture the
real end-state summary card — see "Session ledger" in
`docs/video-production/README.md` — session
`3e5b78f3253d5673a31b748341fdb818`, fetched live from `/api/log` right
after recording: `handshake_ms=274.2, request_to_response_ms=3050.3,
ratio=0.090`) landed at ~11.1x — the narration below says "about eleven
times" to match *this specific take*, since that's what's on screen; if
this ever gets re-recorded, re-check the real number via `/api/log` and
update both here and in `generate_narration.py` rather than leaving a
mismatch. Two earlier takes exist but aren't used in the final video: a
light-mode take (session `859a556718e1115faa2ebcab97cef8a7`, ~11.0x),
discarded for a light/dark theme mismatch against the real human-solved
Turnstile clip spliced in front of it; and a dark-mode take (session
`60e1089f1c8ce10509bc88564f78af5a`, ~10.2x) used in an earlier revision
of this video, superseded once it became clear the real end-state
summary card (handshake/ratio numbers) was never actually captured on
screen in either earlier take — Playwright never scrolled the page down
to it, so those numbers only ever reached the video as a text-overlay
reconstruction rather than real footage. See
`docs/video-production/README.md`'s "Session ledger" for all three):
```
[ai-pqc-kex] ai-client: model replied " A lattice is a mathematical
concept used in cryptography..."

VERIFIED: client received a real model reply through the encrypted channel.

VERIFIED: HelloRetryRequest observed (2 ClientHellos) - consistent
with X25519MLKEM768 (the first-preference group) being negotiated, not a
silent fallback to secp256r1.
```
Then cut — real time, no speed-up needed, this part of the recording is
short — to the page's own real `done` state: the badge changing to "VM
deallocated (idle)", then scrolled to the real `#end-state` summary card
(handshake/ratio numbers, genuinely on screen, not a reconstruction — see
above). **Editing note:** the raw capture between the `ai` stage ending
and that scroll contains a long dead stretch (nothing on screen changes
for roughly the first two-thirds of it — checked frame by frame, not
assumed) before the badge-change/scroll/reveal happens in a ~6–7s window
near the end. Cut straight to that window; don't pad the dead middle to
fill time, and don't stretch the narration to match a duration the real
footage doesn't earn — this beat was deliberately shortened for exactly
this reason (a full watch-through caught the original cut sitting on a
frozen frame for ~17 real seconds).

**Narration:**
> "This is what actually makes Latticejack an AI solution, not just a
> crypto migration that happens to run on Arm: a real, quantized language
> model served by llama.cpp with Arm's own KleidiAI acceleration, verified
> actually engaged, sitting behind this exact hybrid post-quantum
> handshake. The handshake cost is about eleven times smaller than the AI
> request it fronts. And the VM deallocates itself when it's done, no
> idle cost between demos."

---

## 2:18–2:37 — Close (AI-generated title card)

**On screen:** short DashScope-generated video clip, repo URL / README
title treatment, matching the open.

**Narration:**
> "Eight optimization levers, a real security bug found and fixed by an
> AI audit, and a real AI workload running behind the migration it's
> pitching — all on real Arm64 hardware, all self-verifying, and all one
> click away at latticejack.itinerario.io. Latticejack —
> github.com/yuanhawk/latticejack."

---

## Notes for whoever records/edits this

- Every number above is quoted from committed docs (`README.md`,
  `WRITEUP.md`, `benchmarks/nativekem-e2e-bench/README.md`,
  `benchmarks/ai-inference-pqc/README.md`, `demo/README.md`) — don't
  round differently on camera than the write-up does, a judge who
  cross-checks will notice. The AI-workload ratio varies run to run — say
  "about X times" rather than a false-precision single decimal if the
  live take lands somewhere off the previously-recorded numbers.
- **This is one continuous real recording, edited into three chunks**
  (0:15–0:40, 1:10–1:45, 1:52–2:18) with the chart beat sandwiched in the
  middle as a natural cut point — not three separate takes. Record the
  whole real run once, start to finish (expect 4–7 real minutes per the
  page's own copy), then cut it down in editing. Speeding up the VM-boot/
  build/llama-server-startup dead time is fine and expected in a demo
  video; just don't present sped-up footage as real-time without saying
  so on screen. **Check every cut for genuinely dead stretches before
  finalizing, not just at the obvious sped-up-montage spots** — the
  1:52–2:18 beat's raw footage had a long static stretch between the `ai`
  stage finishing and the real summary card appearing that got missed on
  the first assembly and only caught on a full watch-through; a static
  frame sitting on screen for many real seconds while narration talks
  over it reads as "the video froze," not as calm pacing. Cut to the
  frames that actually change, and shorten the narration to match rather
  than stretching real footage (or a held frame) to fill a pre-written
  narration's length — this project's whole "verify, don't assume"
  culture applies to the video's own pacing too, not just its factual
  claims.
- Turnstile categorically requires a real human clicking it — confirmed
  directly, not assumed (see the production README). Don't attempt
  headless/automated capture of this beat; it will not pass, and trying
  to force it through with stealth/evasion tooling isn't something this
  project does even against its own site.
- If recording turns into a real Azure VM run: this is real, billable
  compute — one clean take is the goal, and the Worker's own cost
  controls (silence timeout, hard cap, cron dead-man's-switch) mean an
  abandoned/failed take still deallocates itself, but don't rely on that
  as a substitute for paying attention.
- The regulated-industry/Component C angle (guardrail Skill, CBOM) didn't
  make the cut in this revision purely on time — if there's room to extend
  past 3:00 for a non-judged cut, or a follow-up video, it's a strong
  beat: `docs/regulated-deployment-guide.md` and
  `component-c/cbom/after.cbom.json`'s honestly-unmigrated ECDSA entry are
  both still real, checkable material.
