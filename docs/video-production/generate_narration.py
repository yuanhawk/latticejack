#!/usr/bin/env python3
"""Generate narration audio for Latticejack's demo video, one MP3 per
timed beat of docs/demo-video-script.md, via DashScope CosyVoice TTS.

Segment split matches the script's own beat boundaries (each narration
block gets its own file so it can be synced to its on-screen cut in a
video editor, rather than one continuous track that would drift).

Recipe (model, voice, endpoint) carried over as-is from a prior project's
working setup (~/tokenguard-demo-video/generate_narration.py) - confirmed
there that the DASHSCOPE_API_KEY in use is provisioned for the
INTERNATIONAL (Singapore) region, not China: the default SDK endpoints
point at China and fail (401 InvalidApiKey on China REST; ModelNotFound
for cosyvoice-v2, a China-only model, on international). Both base URLs
must be set before constructing SpeechSynthesizer.

Usage: DASHSCOPE_API_KEY=... python generate_narration.py [output_dir]
"""

import os
import sys

import dashscope

dashscope.base_http_api_url = "https://dashscope-intl.aliyuncs.com/api/v1"
dashscope.base_websocket_api_url = "wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference"

from dashscope.audio.tts_v2 import AudioFormat, SpeechSynthesizer  # noqa: E402

dashscope.api_key = os.environ.get("DASHSCOPE_API_KEY")
if not dashscope.api_key:
    sys.exit("DASHSCOPE_API_KEY not set")

OUT_DIR = sys.argv[1] if len(sys.argv) > 1 else "narration_out"
os.makedirs(OUT_DIR, exist_ok=True)

# cosyvoice-v2 (the pure-English-voice model) is China-region only --
# ModelNotFound on international. cosyvoice-v3-flash is the international
# equivalent; its English-capable voices are bilingual (Chinese/English),
# not English-only, but read English text fine (confirmed in the prior
# project this recipe is carried over from).
MODEL = "cosyvoice-v3-flash"
VOICE = "longanyang"  # bilingual (zh/en) voice, reads English cleanly
# 1.0 measured ~193s total across all 6 segments vs. the script's 170s
# (2:50) target - bumped to fit without cutting any narration text.
SPEECH_RATE = 1.15

# (filename, target beat, budget_seconds, narration text) - text copied
# verbatim from docs/demo-video-script.md's own narration blocks, nothing
# paraphrased here so the two never drift.
SEGMENTS = [
    (
        "01_problem",
        "0:00-0:15",
        15,
        "Java shops migrating to post-quantum crypto have no real "
        "playbook. The migration path is undocumented, and nobody's "
        "measured what it actually costs on Arm64, the architecture a "
        "growing share of cloud AI infrastructure runs on. Latticejack "
        "is that playbook: a working migration, benchmarked and "
        "optimized entirely on real Arm64 hardware.",
    ),
    (
        "02_its_real_part1",
        "0:15-0:40",
        25,
        "Click Start, and this boots a real Azure Arm64 VM, on demand, "
        "and streams its raw output back to this page, not a recording, "
        "not simulated. Classical TLS, then hybrid post-quantum TLS, "
        "both running live, both self-verifying. That line matters: it "
        "doesn't just check the handshake completed, it checks the "
        "post-quantum group actually negotiated, not a silent fallback "
        "to classical.",
    ),
    (
        "03_chart",
        "0:40-1:10",
        30,
        "Going hybrid nearly doubles handshake latency on real hardware, "
        "46 to 89 milliseconds at the median. So we measured eight "
        "different ways to claw that back, on the same real Arm64 "
        "silicon, not a laptop. Two came back null, reported as "
        "findings, not hidden. And two gave real wins, both named on "
        "screen: a hand-tuned NEON assembly library, four times faster "
        "per operation; and ahead-of-time native compilation, "
        "seven-point-nine times faster cold start.",
    ),
    (
        "04_ai_audit_bug",
        "1:10-1:45",
        35,
        "Here's what makes this different from a typical benchmark "
        "project: every claim in this repo was adversarially re-checked "
        "by independent AI models, run blind to each other, the same "
        "way you'd want a second human reviewer, but applied "
        "consistently, not once. One of those audits caught something "
        "real: our fastest optimization was deriving actual TLS session "
        "secrets from deterministic, non-cryptographic key material, a "
        "genuine security bug. We fixed it, verified two keygens now "
        "produce different keys where before they were identical, and "
        "re-measured on real hardware to see what the fix actually "
        "cost. That discipline runs through this whole project: every "
        "place a measurement turned out wrong, that's documented, not "
        "quietly corrected.",
    ),
    (
        "05_ai_workload",
        "1:45-2:10",
        25,
        "This is what actually makes Latticejack an AI solution, not "
        "just a crypto migration that happens to run on Arm: a real, "
        "quantized language model served by llama.cpp with Arm's own "
        "KleidiAI acceleration, verified actually engaged, sitting "
        "behind this exact hybrid post-quantum handshake. The "
        "handshake cost is about eleven times smaller than the AI "
        "request it fronts. And the VM deallocates itself when it's "
        "done, no idle cost between demos.",
    ),
    (
        "06_close",
        "2:18-2:37",
        20,
        "Eight optimization levers, a real security bug found and fixed "
        "by an AI audit, and a real AI workload running behind the "
        "migration it's pitching, all on real Arm64 hardware, all "
        "self-verifying, and all one click away at latticejack dot "
        "itinerario dot io. Latticejack, github dot com slash yuanhawk "
        "slash latticejack.",
    ),
]


def main() -> int:
    total_chars = sum(len(text) for *_, text in SEGMENTS)
    print(f"{len(SEGMENTS)} segments, {total_chars} characters total\n")

    for name, window, budget_s, text in SEGMENTS:
        out_path = os.path.join(OUT_DIR, f"{name}.mp3")
        print(f"== {name} ({window}, budget {budget_s}s, {len(text)} chars) ==")
        # A fresh SpeechSynthesizer per call -- reusing one instance across
        # multiple .call()s breaks the websocket connection on the 2nd use
        # (confirmed in the prior project this recipe is carried over from).
        synthesizer = SpeechSynthesizer(
            model=MODEL,
            voice=VOICE,
            format=AudioFormat.MP3_22050HZ_MONO_256KBPS,
            volume=50,
            speech_rate=SPEECH_RATE,
            pitch_rate=1.0,
        )
        audio = synthesizer.call(text)
        with open(out_path, "wb") as f:
            f.write(audio)
        size_kb = len(audio) / 1024
        # Actual output is 128kbps mono regardless of requested format
        # (international model default, confirmed in the prior project)
        # -> ~16 KB/s.
        est_s = size_kb / 16
        flag = "  <-- OVER BUDGET, consider speech_rate>1.0" if est_s > budget_s * 1.15 else ""
        print(f"  wrote {out_path} ({size_kb:.0f} KB, ~{est_s:.1f}s estimated){flag}")

    print("\nDone. Estimated total runtime vs. the script's 2:50 target -- ")
    print("check each file's actual duration (ffprobe) before editing,")
    print("the KB-based estimate above is rough.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
