# Latticejack — PQC Migration for Java on Arm64

Arm AI Optimization Challenge, Track 2 (Migration/Adoption). Full plan: [arm-hackathon-plan.md](arm-hackathon-plan.md).

Migrates a Java TLS/mTLS reference service from classical crypto
(ECDSA/X25519) to hybrid post-quantum crypto (ML-KEM/ML-DSA), then tunes the
migrated path for Arm64 (AWS Graviton / Ampere).

## Status

| Piece | State |
|---|---|
| **Before** (classical mTLS, JDK-only) | Working — `./run-before.sh` |
| **After** (hybrid X25519MLKEM768 KEX) | In progress, known gap — `./run-after.sh` fails at a specific, documented BouncyCastle group-negotiation issue. See [docs/bouncycastle-pqc-notes.md](docs/bouncycastle-pqc-notes.md) §3a. |
| ML-DSA certificate auth | Not started — experimental upstream in BouncyCastle, not enabled by default ([bcgit/bc-java#2102](https://github.com/bcgit/bc-java/issues/2102)) |
| Arm64 benchmarking (B1/B2) | Not started |
| Authoring guardrail / CBOM (Component C) | Not started |

## Quick start

Requires JDK 21 and Maven.

```bash
./run-before.sh                    # classical mTLS handshake — works end to end
LATTICEJACK_DEBUG=1 ./run-after.sh # hybrid PQC KEX attempt — currently fails, see docs/
```

Both scripts generate their own test keystores under `keys/classical/` on
first run (`scripts/gen-classical-keys.sh`, ECDSA P-256, 30-day validity —
never commit these; `.gitignore` already excludes them).

Set `LATTICEJACK_DEBUG=1` to dump the full TLS handshake
(`-Djavax.net.debug=ssl:handshake`), which is how to verify what actually
negotiated — see the correctness note in `EchoTlsServer.java` and
arm-hackathon-plan.md §8: a handshake that looks PQC but silently falls back
to classical is not an acceptable outcome, so it's worth checking explicitly
rather than trusting a "handshake complete" line alone.

## Running on Arm64

See [docs/arm64-instance-setup.md](docs/arm64-instance-setup.md) for
provisioning a Graviton or Ampere instance and getting a verified `aarch64`
JDK 21 environment. Once there, the same two commands above apply.

## Repo layout

```
src/main/java/com/latticejack/pqc/   the reference TLS/mTLS service (Component A)
scripts/gen-classical-keys.sh        classical (ECDSA P-256) test keystore generation
run-before.sh / run-after.sh         the two configurations, per arm-hackathon-plan.md §3
docs/bouncycastle-pqc-notes.md       BouncyCastle PQC/JSSE research + live debugging findings
docs/arm64-instance-setup.md         Arm64 provisioning guidance
```

## License

Apache-2.0 — see [LICENSE](LICENSE).
