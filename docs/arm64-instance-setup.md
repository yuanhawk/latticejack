# Arm64 instance setup

Guidance for arm-hackathon-plan.md §10 item 1 ("confirm Arm64 access — this
gates everything in week 2") and §8 ("Arm64 instance"). I can't provision
cloud infrastructure on your behalf — this is the exact set of steps/commands
to run yourself. Pick one path; AWS Graviton is the recommended default
(most recognizable "Arm64 cloud" name, pairs cleanly with the Cloud AI
track), Ampere/OCI is the cheaper fallback if you want a free-tier option.

## Option A (recommended): AWS Graviton

1. **Pick an instance type.** `c7g.large` (Graviton3, compute-optimized,
   2 vCPU/4GB) is a reasonable default for handshake benchmarking — it's
   CPU-bound work, not memory-bound. `c8g.large` (Graviton4) if available in
   your region and you want the newest core. Avoid burstable `t4g` for
   benchmarking — its CPU credits will contaminate your latency numbers.

2. **Launch:**
   ```bash
   aws ec2 run-instances \
     --image-id <arm64-al2023-ami-id> \
     --instance-type c7g.large \
     --key-name <your-keypair> \
     --security-group-ids <sg-allowing-ssh-and-8443-8444> \
     --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=latticejack-pqc-arm}]'
   ```
   Get a current Arm64 AMI id for your region:
   ```bash
   aws ssm get-parameter \
     --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64 \
     --query 'Parameter.Value' --output text
   ```

3. **Confirm you're actually on Arm64** once connected (don't trust the
   instance-type name alone):
   ```bash
   ssh ec2-user@<instance-ip>
   uname -m        # expect: aarch64
   lscpu | head    # confirm core count, check for SVE/NEON flags on Graviton3/4
   ```

4. **Install a JDK 21 build for aarch64** (Amazon Corretto is the safest
   default on AWS; Temurin is a fine alternative):
   ```bash
   sudo dnf install -y java-21-amazon-corretto-devel git maven
   java -version   # confirm it reports aarch64, not a cross-compiled x86 shim
   ```

5. **Open the ports** the reference service uses (8443 for `run-before.sh`,
   8444 for `run-after.sh`, or whatever `LATTICEJACK_PORT` you set) in the
   security group if benchmarking from a separate client machine; for
   same-host client+server (the common case here) this isn't needed.

## Option B: Oracle Cloud Ampere (free-tier alternative)

1. OCI's "Always Free" tier includes Ampere A1 (up to 4 OCPU / 24GB across
   instances) — cheapest way to get real Arm64 hardware for this project if
   AWS credits are a concern.
2. Launch an Ampere A1 Compute instance via the OCI console, Ubuntu or
   Oracle Linux aarch64 image.
3. Same verification as above: `uname -m` → `aarch64`, install JDK 21 for
   aarch64 (`apt install openjdk-21-jdk maven git` on Ubuntu), confirm
   `java -version` reports the right architecture.

## Option C (what this project actually used): Azure, Cobalt 100

Not in the original plan (§8 named AWS/Ampere) but this is what got
provisioned, and it worked well — recorded here as the actual, verified
path rather than a hypothetical third option.

1. **Check quota before picking a size.** Newer/free-tier subscriptions
   often have `0` approved quota for specific Arm64 VM families even in
   regions where the family is sold — `az vm list-usage --location eastus
   --output table | grep -i Family` shows current limits. This project hit
   `0` quota for the `Dplsv5` (Ampere Altra) family in `eastus`, but found
   `10` vCPU already approved for the newer `Dpsv6`/`Dplsv6`/`Dpldsv6`/`Dpdsv6`
   families (Azure's own **Cobalt 100** Arm64 silicon, Neoverse-N2 cores,
   SVE2) with no request needed.

2. **Even with quota, a specific region/size pair can lack physical
   capacity right now** (`SkuNotAvailable` / "Capacity Restrictions") —
   this is independent of quota and just means try another region.
   `eastus2` had both quota and capacity for `Standard_D2pls_v6` when
   `eastus` didn't.

3. **The `Ubuntu2404` image alias defaults to x86_64.** Azure's VM size and
   its image are validated independently, so `az vm create --size
   Standard_D2pls_v6 --image Ubuntu2404` fails late with an
   architecture-mismatch error rather than resolving to the arm64 variant.
   Find the correct SKU explicitly:
   ```bash
   az vm image list-skus --location eastus2 --publisher Canonical \
     --offer ubuntu-24_04-lts --output table
   # -> server-arm64
   ```
   Then use the full URN: `Canonical:ubuntu-24_04-lts:server-arm64:latest`.

4. **Full working command** (adjust resource group/region/names):
   ```bash
   az group create --name latticejack-arm64-rg --location eastus2
   ssh-keygen -t ed25519 -f ~/.ssh/latticejack_arm64 -N ""
   az vm create \
     --resource-group latticejack-arm64-rg \
     --name latticejack-arm64-runner \
     --image "Canonical:ubuntu-24_04-lts:server-arm64:latest" \
     --size Standard_D2pls_v6 \
     --admin-username azureuser \
     --ssh-key-values ~/.ssh/latticejack_arm64.pub \
     --public-ip-sku Standard
   ```
   `Standard_D2pls_v6` (2 vCPU/4GB) cost **$0.062/hr** in `eastus2` at
   verification time — check current pricing before relying on this number.

5. **Repo access without a broad token on the VM:** generate a dedicated
   SSH keypair *on the VM itself* and register it as a GitHub deploy key
   (`gh repo deploy-key add`, read-only) rather than embedding a personal
   access token in `.git/config` there — scoped to this one repo,
   independently revocable.

6. **Cost control:** `az vm deallocate --resource-group ... --name ...`
   stops compute billing (disk/IP costs continue at a much smaller rate);
   `az vm start` brings it back. If this VM is also a GitHub Actions
   self-hosted runner (see below), it only picks up CI jobs while running —
   stopping it between uses trades "CI runs automatically" for "near-zero
   idle cost." This project chose the latter.

### Registering it as a GitHub Actions self-hosted runner

Doubles as CI infrastructure and Component B hardware, and self-hosted
runners don't consume GitHub-hosted Actions minutes (relevant if
GitHub-hosted CI is blocked on account billing, as it was for this repo).

```bash
# On your machine: get a short-lived registration token
gh api repos/<owner>/<repo>/actions/runners/registration-token -X POST --jq '.token'

# On the VM:
mkdir -p ~/actions-runner && cd ~/actions-runner
curl -o runner.tar.gz -L https://github.com/actions/runner/releases/download/v<VERSION>/actions-runner-linux-arm64-<VERSION>.tar.gz
tar xzf runner.tar.gz
./config.sh --url https://github.com/<owner>/<repo> --token <TOKEN> \
  --labels self-hosted,arm64,azure,cobalt100 --unattended
sudo ./svc.sh install azureuser
sudo ./svc.sh start
```
Verify it's online: `gh api repos/<owner>/<repo>/actions/runners --jq '.runners[] | {name,status}'`.
Then point a workflow job at it with `runs-on: [self-hosted, arm64, azure]`
(see `.github/workflows/ci.yml`) — give it a `timeout-minutes` if the VM
gets stopped between uses, so a push while it's off fails fast instead of
queuing for hours waiting for a runner that isn't coming.

## Once on the instance (any option above)

```bash
git clone <this-repo-url> latticejack && cd latticejack
./run before   # classical baseline — should complete a handshake + echo
./run after    # hybrid PQC KEX, self-verifying (see MIGRATION.md)
./run-benchmark.sh before   # B1 characterization, real hardware numbers
./run-benchmark.sh after
```

If `./run before` doesn't complete cleanly on the instance, stop and fix
that before touching PQC or benchmarking — a broken baseline invalidates
every delta measured on top of it.

`scripts/require-jdk21.sh`'s JDK-detection candidate list may need
extending for your distro/vendor if none of its paths match — check what
`apt`/`dnf` actually installed (`update-alternatives --list java` on
Debian/Ubuntu) and add that path rather than fighting the script.

## Benchmarking note

The plan originally called for **Arm Performix** for the Arm-side benchmark
numbers (arm-hackathon-plan.md §8, §4 days 8-9); `run-benchmark.sh` +
`benchmarks/` in this repo is what actually got built and used instead
(latency percentiles, throughput, bytes-on-wire, CPU) — see
`benchmarks/README.md`. Revisit Performix specifically only if its output
format is a hard requirement for submission.
