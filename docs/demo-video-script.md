# Demo video script

Target: under 3 minutes (the official rules' hard cap — "Judges are not
required to watch beyond three minutes"). Written to be recordable
directly: each beat names the exact screen content and the exact command
to run, using data and behavior already verified elsewhere in this repo —
nothing here should require improvising a new number on camera.

Recommended capture setup: terminal recording (asciinema, or plain screen
capture) on the actual Arm64 target — either the Azure Cobalt 100 VM
(`az vm start` first, `az vm deallocate` after — see
`docs/arm64-instance-setup.md`) or Apple Silicon locally if the VM isn't
up; note on camera which one it is, since the real-hardware claim is part
of the pitch. A simple slide deck (even plain Keynote/Google Slides) for
the two chart beats is fine — the SVGs in `docs/charts/` can be dropped in
directly.

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

## 0:15–0:40 — It's real, not simulated (terminal)

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
> negotiated, not a silent fallback to classical. A working handshake that
> quietly downgrades is worse than one that fails loudly — this project
> found and fixed exactly that bug once already."

---

## 0:40–1:15 — What it costs, and what closes the gap (charts)

**On screen:** `docs/charts/b1-latency.svg`, then `docs/charts/b2-levers.svg`
(drop both into a slide, or `open` them full-screen if recording a
terminal-only demo).

**Narration:**
> "Going hybrid nearly doubles handshake latency on real hardware — 45 to
> 89 milliseconds at the median. So we measured eight different ways to
> claw that back, on the same real Arm64 silicon, not a laptop. Two came
> back null — session resumption, JVM tuning flags — reported as findings,
> not hidden. Three gave modest wins. And two gave real ones: mlkem-native's
> hand-tuned NEON assembly, four times faster per operation; GraalVM
> native-image, eliminating JVM startup entirely, seven-point-nine times
> faster cold start."

---

## 1:15–2:00 — The differentiator: an AI audit caught a real security bug

**On screen:** terminal, `git log --oneline -5` scrolled to show commit
`c98261a` ("Fix lever-5 native-KEM RNG..."), or a slide quoting the commit
message. Optionally re-run `./run-nativekem.sh` live to show the
`[native-mlkem-provider]` trace-marker verification.

**Narration:**
> "Here's what makes this different from a typical benchmark project:
> every claim in this repo was adversarially re-checked by independent AI
> models, run blind to each other — the same way you'd want a second human
> reviewer, but applied consistently across eight levers, not once. One of
> those audits caught something real: the fastest optimization in this
> project was deriving actual TLS session secrets from deterministic,
> non-cryptographic key material — a genuine security bug, not a style
> nit. We fixed it, verified two keygens now produce different keys where
> before they were identical, and re-measured on real hardware to see what
> the fix actually cost. That's the discipline this whole project runs on:
> every place a measurement turned out wrong, that's documented, not
> quietly corrected."

---

## 2:00–2:35 — Built for the audience that actually needs this

**On screen:** `docs/regulated-deployment-guide.md` scrolled briefly, or
the CBOM JSON (`component-c/cbom/after.cbom.json`) showing the honestly
-unmigrated ECDSA entry.

**Narration:**
> "This isn't aimed at a green-field rewrite — it's aimed at Java shops in
> regulated industries who can't rip out a certified codebase. A Claude
> Code Skill catches classical-crypto regressions before they ship — we
> ran it live against fresh code, not just wrote it up. A schema-validated
> Cryptography Bill of Materials tracks exactly what's migrated and what
> isn't — and it stays honest even when that's inconvenient: this one
> still lists a certificate algorithm as unmigrated, because it genuinely
> is."

---

## 2:35–2:55 — Close

**On screen:** repo URL / README title card.

**Narration:**
> "Eight levers, two honest nulls, one real security bug found and fixed,
> all on real Arm64 hardware. Latticejack — github.com/yuanhawk/latticejack."

---

## Notes for whoever records this

- Every number above is quoted from committed docs (`README.md`,
  `WRITEUP.md`, `benchmarks/nativekem-e2e-bench/README.md`) — don't
  round differently on camera than the write-up does, a judge who
  cross-checks will notice.
- If recording on the Azure VM: start it, record, `az vm deallocate`
  immediately after — this project has a strict no-idle-VM cost policy,
  don't let the recording session leave it running.
- The `./run after` step already takes a few seconds of real handshake
  time — don't cut that out, the "it's live, not a mockup" beat is worth
  more than the seconds saved.
- If total runtime creeps past 2:55, cut the 2:00–2:35 beat first (it's
  the weakest visually) before cutting anything from 0:15–2:00 — the
  hardware-proof and the AI-audit-caught-a-real-bug beats are this
  project's actual differentiators and should survive any trim.
