# Demo video script

Target: under 3 minutes (the official rules' hard cap — "Judges are not
required to watch beyond three minutes"). Written to be recordable
directly: each beat names the exact screen content and the exact command
to run, using data and behavior already verified elsewhere in this repo —
nothing here should require improvising a new number on camera.

**Revised after the AI inference workload landed** (`benchmarks/ai-inference-pqc/`)
— the original version of this script predated that work and didn't
mention it at all. Every audit run against this project since has agreed
it's the single strongest, newest piece of evidence (it's what actually
answers "is this an AI solution," not just argues it), so it now has the
best slot in the video, not a footnote.

Recommended capture setup: terminal recording (asciinema, or plain screen
capture) on the actual Arm64 target — either the Azure Cobalt 100 VM
(`az vm start` first, `az vm deallocate` after — see
`docs/arm64-instance-setup.md`) or Apple Silicon locally if the VM isn't
up; note on camera which one it is, since the real-hardware claim is part
of the pitch. A simple slide deck (even plain Keynote/Google Slides) for
the chart beat is fine — the SVGs in `docs/charts/` can be dropped in
directly. The AI-workload beat needs `run-ai.sh` already working on
whichever machine you record on (llama-server built with KleidiAI and
running — see `component-ai/README.md`); don't try to build that live on
camera, it takes too long.

---

## 0:00–0:15 — The problem (slide or voiceover over a static title card)

**On screen:** repo name/README title, or a plain title card.

**Narration:**
> "Java shops migrating to post-quantum crypto have no real playbook. The
> migration path is undocumented, and nobody's measured what it actually
> costs on Arm64 — the architecture a growing share of cloud AI
> infrastructure runs on. Latticejack is that playbook: a working
> migration, benchmarked and optimized entirely on real Arm64 hardware."

---

## 0:15–0:35 — It's real, not simulated (terminal)

**On screen:** terminal, this exact sequence, on the real target hardware:

```bash
git clone https://github.com/yuanhawk/latticejack && cd latticejack
uname -m   # <- pause here, let "aarch64" sit on screen for a beat
./run before
./run after
```

**What appears (verified, this is the actual output):**
```
[before-classical] client: server replied "echo: hello from client"
...
VERIFIED: HelloRetryRequest observed (2 ClientHellos) - consistent
with X25519MLKEM768 (the first-preference group) being negotiated, not a
silent fallback to secp256r1.
```

**Narration (over the output, or as it scrolls):**
> "Classical TLS, then hybrid post-quantum TLS — both running live, both
> self-verifying. That second line matters: the script doesn't just check
> the handshake completed, it checks the post-quantum group actually
> negotiated, not a silent fallback to classical."

---

## 0:35–1:05 — What it costs, and what closes the gap (chart)

**On screen:** `docs/charts/b1-latency.svg`, then `docs/charts/b2-levers.svg`.

**Narration:**
> "Going hybrid nearly doubles handshake latency on real hardware — 45 to
> 89 milliseconds at the median. So we measured eight different ways to
> claw that back, on the same real Arm64 silicon, not a laptop. Two came
> back null — reported as findings, not hidden. And two gave real wins:
> mlkem-native's hand-tuned NEON assembly, four times faster per
> operation; GraalVM native-image, seven-point-nine times faster cold
> start."

---

## 1:05–1:45 — An AI audit caught a real security bug in our own code

**On screen:** terminal, `git log --oneline -8` scrolled to show commit
`c98261a` ("Fix lever-5 native-KEM RNG..."). Optionally re-run
`./run-nativekem.sh` live to show the `[native-mlkem-provider]` trace
-marker verification.

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

## 1:45–2:30 — A real AI workload, secured by the migration itself

**On screen:** terminal, `./run-ai.sh` running live (or a recording of it
if the backend takes too long to start on camera) — let the real model
reply and the verification lines both sit on screen for a beat each.

**What appears** (verified against the actual print order and bracket
label in `PqcAiTlsClient.java`/`run-ai.sh` — the label is `[ai-pqc-kex]`,
not `[ai-client]`, and the model-reply/timing lines print *before*
run-ai.sh's own two VERIFIED checks, not after; this is real run 1's
output from the committed 3-run table in
`benchmarks/ai-inference-pqc/README.md`, not an averaged/composite
number — expect the exact ratio to vary run to run, 9.1x-9.8x across the
three real runs on file, ~9.4x average):
```
[ai-pqc-kex] ai-client: model replied " A lattice is a mathematical
concept used in cryptography..."
[ai-pqc-kex]   handshake / inference ratio = 0.110  (i.e. handshake cost
is 9.1x smaller than the AI workload it fronts)

VERIFIED: client received a real model reply through the encrypted channel.

VERIFIED: HelloRetryRequest observed (2 ClientHellos) - consistent
with X25519MLKEM768 (the first-preference group) being negotiated, not a
silent fallback to secp256r1.
```

**Narration:**
> "This is what actually makes Latticejack an AI solution, not just a
> crypto migration that happens to run on Arm: a real, quantized language
> model, served by llama.cpp with Arm's own KleidiAI acceleration — we
> checked it actually engaged, not just linked, down to the kernel
> selection log and a non-zero accelerated-buffer size — sitting behind
> this exact hybrid post-quantum handshake. On the real Azure Cobalt 100
> target, the handshake cost is about nine times smaller than the AI
> request it fronts — quantum-safe TLS is noise next to the AI workload it
> protects. We even found a real memory problem getting here — the model
> server got OOM-killed on this VM's limited RAM — and fixed it live
> rather than working around it quietly."

---

## 2:30–2:50 — Close

**On screen:** repo URL / README title card.

**Narration:**
> "Eight optimization levers, a real security bug found and fixed by an
> AI audit, and a real AI workload running behind the migration it's
> pitching — all on real Arm64 hardware, all self-verifying. Latticejack —
> github.com/yuanhawk/latticejack."

---

## Notes for whoever records this

- Every number above is quoted from committed docs (`README.md`,
  `WRITEUP.md`, `benchmarks/nativekem-e2e-bench/README.md`,
  `benchmarks/ai-inference-pqc/README.md`) — don't round differently on
  camera than the write-up does, a judge who cross-checks will notice.
  The AI-workload ratio varies run to run (measured 9.1x-9.8x across 3
  real runs, ~9.4x average) — say "about nine times" rather than a false
  -precision single decimal if the live run lands somewhere in that range
  rather than exactly on the average.
- If recording on the Azure VM: start it, record, `az vm deallocate`
  immediately after — this project has a strict no-idle-VM cost policy,
  don't let the recording session leave it running. Also remember
  `llama-server` needs to already be running and healthy before
  `run-ai.sh` is called — start it first, off camera, matching
  `component-ai/README.md`'s instructions (and cap context size per that
  README's own OOM note if running on the VM's limited RAM).
- The AI-workload beat (1:45-2:30) is the single most important addition
  in this revision — every audit run against this project agrees it's
  what actually answers the "is this an AI solution" question, not just
  argues it. If total runtime creeps past 2:55, cut duration from the
  0:35-1:05 chart beat or tighten narration pacing elsewhere before
  touching this one.
- The regulated-industry/Component C angle (guardrail Skill, CBOM) didn't
  make the cut in this revision purely on time — if there's room to extend
  past 3:00 for a non-judged cut, or a follow-up video, it's a strong
  beat: `docs/regulated-deployment-guide.md` and
  `component-c/cbom/after.cbom.json`'s honestly-unmigrated ECDSA entry are
  both still real, checkable material.
