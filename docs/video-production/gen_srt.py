#!/usr/bin/env python3
"""Generate an SRT subtitle file for the Latticejack demo video, synced
via character-proportional timing within each narration segment (no
word-level ASR timestamps available - DashScope Paraformer is China-region
only, this project's key is international). Same estimation technique
already validated this session (used to correctly locate two mispronounced
phrases to within ~2s of their actual position in the audio).
"""
import re

# (segment_start_s, segment_duration_s, spoken_text) - spoken_text is
# copied verbatim from generate_narration.py's SEGMENTS (the exact text
# actually sent to TTS), start/duration from the exact rendered mp3s.
SEGMENTS = [
    (0.0, 20.043875,
     "Java shops migrating to post-quantum crypto have no real "
     "playbook. The migration path is undocumented, and nobody's "
     "measured what it actually costs on Arm64, the architecture a "
     "growing share of cloud AI infrastructure runs on. Latticejack "
     "is that playbook: a working migration, benchmarked and "
     "optimized entirely on real Arm64 hardware."),
    (20.043875, 24.066750,
     "Click Start, and this boots a real Azure Arm64 VM, on demand, "
     "and streams its raw output back to this page, not a recording, "
     "not simulated. Classical TLS, then hybrid post-quantum TLS, "
     "both running live, both self-verifying. That line matters: it "
     "doesn't just check the handshake completed, it checks the "
     "post-quantum group actually negotiated, not a silent fallback "
     "to classical."),
    (44.110625, 27.149188,
     "Going hybrid nearly doubles handshake latency on real hardware, "
     "46 to 89 milliseconds at the median. So we measured eight "
     "different ways to claw that back, on the same real Arm64 "
     "silicon, not a laptop. Two came back null, reported as "
     "findings, not hidden. And two gave real wins, both named on "
     "screen: a hand-tuned NEON assembly library, four times faster "
     "per operation; and ahead-of-time native compilation, "
     "seven-point-nine times faster cold start."),
    (71.259813, 40.419375,
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
     "quietly corrected."),
    (111.679188, 25.712438,
     "This is what actually makes Latticejack an AI solution, not "
     "just a crypto migration that happens to run on Arm: a real, "
     "quantized language model served by llama.cpp with Arm's own "
     "KleidiAI acceleration, verified actually engaged, sitting "
     "behind this exact hybrid post-quantum handshake. The "
     "handshake cost is about eleven times smaller than the AI "
     "request it fronts. And the VM deallocates itself when it's "
     "done, no idle cost between demos."),
    (137.391626, 19.573688,
     "Eight optimization levers, a real security bug found and fixed "
     "by an AI audit, and a real AI workload running behind the "
     "migration it's pitching, all on real Arm64 hardware, all "
     "self-verifying, and all one click away at latticejack dot "
     "itinerario dot io. Latticejack, github dot com slash yuanhawk "
     "slash latticejack."),
]

MAX_CHARS = 68  # per caption (YouTube convention: ~2 short lines)


def split_into_chunks(text):
    """Split on clause boundaries (,;:.) first, then hard-wrap anything
    still too long on word boundaries. Returns list of (chunk_text,
    char_span) where char_span is the chunk's length INCLUDING its
    trailing separator/space, for proportional timing."""
    # Split, keeping delimiters attached to the preceding chunk.
    pieces = re.split(r"(?<=[,;:.])\s+", text)
    chunks = []
    for piece in pieces:
        piece = piece.strip()
        if not piece:
            continue
        if len(piece) <= MAX_CHARS:
            chunks.append(piece)
        else:
            words = piece.split(" ")
            cur = ""
            for w in words:
                trial = (cur + " " + w).strip()
                if len(trial) > MAX_CHARS and cur:
                    chunks.append(cur)
                    cur = w
                else:
                    cur = trial
            if cur:
                chunks.append(cur)
    return chunks


def merge_short_chunks(chunks, min_len=20):
    """Merge a clause-split chunk into its shorter-combined neighbor if
    it's short enough to risk flashing by too fast on screen
    (proportional timing gives short text short duration, but very short
    durations read as choppy regardless of how little text there is).

    Found by an independent Opus audit: an earlier version of this
    function only tried merging within MAX_CHARS, so a short fragment
    sandwiched between two already-near-the-cap neighbors fell through
    unmerged (produced a real 0.875s caption). Avoiding a sub-1s flash
    caption matters more than strictly respecting the display-line cap by
    a few extra characters, so the merge cap here is deliberately looser
    than MAX_CHARS - this function's whole job is the exception case.
    """
    MERGE_CAP = MAX_CHARS + 25
    merged = []
    i = 0
    while i < len(chunks):
        c = chunks[i]
        if len(c) < min_len and (merged or i + 1 < len(chunks)):
            prev_len = len(merged[-1]) + 1 + len(c) if merged else None
            next_len = len(c) + 1 + len(chunks[i + 1]) if i + 1 < len(chunks) else None
            # Prefer whichever neighbor merge is shorter (stays closer to
            # normal caption length); only actually merge if it fits.
            candidates = [x for x in (prev_len, next_len) if x is not None and x <= MERGE_CAP]
            if candidates and (prev_len is not None and prev_len == min(candidates)):
                merged[-1] = merged[-1] + " " + c
            elif candidates and (next_len is not None and next_len == min(candidates)):
                merged.append(c + " " + chunks[i + 1])
                i += 1
            else:
                merged.append(c)
        else:
            merged.append(c)
        i += 1
    return merged


def clean_display(text):
    """TTS-phonetic spellings back to natural written form for display."""
    text = text.replace(" dot ", ".")
    text = text.replace(" slash ", "/")
    return text


def fmt_ts(t):
    h = int(t // 3600)
    m = int((t % 3600) // 60)
    s = int(t % 60)
    ms = int(round((t - int(t)) * 1000))
    if ms == 1000:
        ms = 0
        s += 1
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def main():
    entries = []
    for seg_start, seg_dur, text in SEGMENTS:
        chunks = merge_short_chunks(split_into_chunks(text))
        total_chars = sum(len(c) + 1 for c in chunks)  # +1 for the space/gap consumed
        t = seg_start
        for c in chunks:
            span = (len(c) + 1) / total_chars * seg_dur
            start = t
            end = t + span
            entries.append((start, end, clean_display(c)))
            t = end

    with open("latticejack_subtitles.srt", "w") as f:
        for i, (start, end, text) in enumerate(entries, 1):
            f.write(f"{i}\n{fmt_ts(start)} --> {fmt_ts(end)}\n{text}\n\n")

    print(f"Wrote {len(entries)} captions to latticejack_subtitles.srt")
    print(f"Last caption ends at {fmt_ts(entries[-1][1])}")


if __name__ == "__main__":
    main()
