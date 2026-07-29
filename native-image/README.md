# B2 lever 4: GraalVM native-image

Ahead-of-time-compiles the "after" (hybrid PQC) mTLS reference service to a
native Arm64 executable, eliminating JIT warmup entirely - the recurring
suspect behind every other lever's weak/null result on real hardware (see
top-level `README.md`'s B2 row and `benchmarks/samples/azure-cobalt100-2vcpu/README.md`).

Build: `GRAALVM_HOME=/path/to/graalvm-jdk-21 ./scripts/build-native-image.sh`
Benchmark: `./scripts/bench-native-image.sh [N]` (build first)

## Two GraalVM/BouncyCastle incompatibilities, found and fixed

Both are specific to running BouncyCastle 1.85 under GraalVM native-image -
neither is a BC or a project bug, and neither affects the regular JVM build.

**1. JCE provider build-time verification.** `KeyStore.load()` on a PKCS12
store internally does `Mac.getInstance("...", "BC")`, which under native-image
requires the "BC" provider to have been registered *and verified* during
image generation - `-H:AdditionalSecurityProviders=<class>` alone does not do
this (it only affects a class-initialization ordering check, not the runtime
`Security.getProviders()` list a running image actually consults). The fix:
`BouncyCastleFeature.java`, a GraalVM `Feature` that calls
`Security.addProvider()` for both BC and BCJSSE during `afterRegistration`
(build time) - native-image bakes whatever's registered in `java.security.Security`
at build time into the image heap, so this instance is both pre-verified and
present at runtime. `ProviderBootstrap.install()` was changed to check
`Security.getProvider("BC")`/`("BCJSSE")` first and reuse it if present, rather
than always instantiating fresh - a freshly-constructed instance at runtime
would be a different object with no cached verification result, throwing the
same error again even with the Feature in place.

**2. `--initialize-at-build-time=org.bouncycastle` (needed so the Feature's
build-time provider registration actually takes effect) then fails a
different way**: BC's DRBG classes
(`org.bouncycastle.jcajce.provider.drbg.DRBG$Default` /`$NonceAndIV`) cache a
`SecureRandom` instance in a static field, and native-image refuses to bake a
`Random`/`SecureRandom` instance into the image heap (their seed state
wouldn't behave correctly if frozen at build time and reused across every
run of the binary). Fixed by exporting those two classes back out to
run-time init: `--initialize-at-run-time='org.bouncycastle.jcajce.provider.drbg.DRBG$Default,org.bouncycastle.jcajce.provider.drbg.DRBG$NonceAndIV'`.

**Correctness verification method note**: `-Djava.util.logging.config.file`
(used by `run-after.sh` to enable BC's FINEST-level HelloRetryRequest check -
see that script's header comment for why this check exists) doesn't work
under native-image with the above flags: `LogManager`'s static initializer
reads that property, but BC's own classes touch `java.util.logging` during
the *build-time* init forced by fix #2 above, so `LogManager` is already
build-time-initialized before the runtime property could apply. Worked around
by wiring the same FINEST-on-`org.bouncycastle` config programmatically in
`ProviderBootstrap.install()` (guarded by `-Dlatticejack.tls.debug=true`
instead), via plain `Logger`/`Handler` instance calls that work regardless of
when `LogManager` itself was initialized.

## Files

- `BouncyCastleFeature.java` - the build-time provider-registration Feature (fix #1).
- `config/` - merged `native-image-agent` tracing output (reflection/JNI/resource
  config), captured by running `EchoTlsServer`/`EchoTlsClient` once each under
  `-agentlib:native-image-agent=config-output-dir=...` and merging with
  `native-image-configure generate`. Regenerate if the app's reflection surface
  changes (new classes touched via `Class.forName`/keystore/JSSE internals).
