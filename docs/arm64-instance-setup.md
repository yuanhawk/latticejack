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

## Once on the instance

```bash
git clone <this-repo-url> latticejack && cd latticejack
./run-before.sh   # classical baseline — should complete a handshake + echo
LATTICEJACK_DEBUG=1 ./run-after.sh   # hybrid PQC KEX — grep output for X25519MLKEM768
```

If `run-before.sh` doesn't complete cleanly on the instance, stop and fix
that before touching PQC or benchmarking — a broken baseline invalidates
every delta measured on top of it.

## Benchmarking note (for B1/B2, not this setup step)

The plan calls for **Arm Performix** for the Arm-side benchmark numbers
(arm-hackathon-plan.md §8, §4 days 8-9). That's separate future work — this
doc only covers getting a verified Arm64 box with a working JDK on it.
