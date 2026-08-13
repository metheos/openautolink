# Error-30 reconnect storm (#30) — read-only investigation findings

**Date:** 2026-07-16
**Scope:** READ-ONLY. No code changed. Basis for deciding a real fix.
**Logs:** 07-15 baseline (vc359) drives 1&2; 07-16 (vc361) drive. Car logcat +
companion logs under `/Docker/oal-logs/canonical/`.

## TL;DR
The "sluggish/slow-to-stream" symptom is NOT a video/keyframe problem. Video
comes up ~200ms off the seed IDR and runs at healthy bitrate. The delay is the
**Error-30 (OPERATION_ABORTED) session teardown + reconnect churn**. There are
(at least) **two distinct triggers**, and they are DIFFERENT root causes:

### Trigger A — handshake-timeout self-abort (code bug, RF-independent)
07-16 19:54:25-41: car TCP connects fine (`Connected to companion ...25.250`),
`Creating SSL cryptor` + `Sending version request (v1.7)` — then **no response
for 15s** → `Session abort: handshake timeout (15s, no SSL/version response)` →
Error-30 cascade across all channels → 5-errors-in-2s escalation → reconnect.
- Source: `jni_session.cpp:395-408` handshake watchdog: if `streaming_` isn't
  true within 15s of TCP-up, `triggerAbort`.
- On the phone the proxy is visibly churning at that moment (warm proxy socket
  closed/reopened, `Car connected` re-lands). So the phone-side gearhead/proxy
  handshake genuinely stalled — the car's 15s abort is a REACTION, but the
  teardown-everything + chooser escalation is what the USER sees as "stuck."

### Trigger B — mid-session socket drop from Wi-Fi loss (RF, not code)
07-15 drive 2 companion log `oal_companion_2026-07-15_16-09-55.log` has **715**
`OAL_CarWifi "<car SSID>" lost` / `Attempt N/12` lines — the PHONE repeatedly loses
association to the CAR's hotspot. Each loss drops the bridge → car read thread
`null returned (stream closed)` → Error-30 cascade → reconnect. This matches the
skill's known "starved link / RF, not app logic" fault. Drive 1 (15:31:10)
is the same shape: healthy stream for ~64s (real IDR 15:30:06, audio 15:30:11),
then `Read thread: null returned (stream closed)` → Error-30.

## Why it LOOKS like a slow/black connect
On Error-30 the code tears down ALL channels and, at reconnect attempt 2, opens
the phone chooser (`ProjectionViewModel.kt:994` "escalation threshold — opening
chooser"). So even though the underlying video was fine, the user is bounced
back to the connecting/chooser overlay while the session re-handshakes — that IS
the "sat on the connection screen / black for a while" experience.

## Key correlations / gotchas
- **Device clocks are skewed** (car vs phone ~mins apart) — align by shared
  events (`Car connected from` ↔ `Connected to companion`), NOT raw timestamps.
- Baseline video health during the "gap" (disproves keyframe theory):
  drive1 bitrate 103→1435→1256 kbps, first frame 190ms; drive2 only 1/103
  samples sub-60kbps. Video was flowing.
- 07-16 vc361 additionally shows the focus-bounce encoder starvation on TOP of
  a handshake-timeout abort — two bugs stacked. (Focus bounce already reverted.)

## Existing guards (already in main)
- `CONNECT_SETTLE_MS=2000` in ProjectionViewModel: guards against the
  auto-reconnect collector firing a 2nd connect mid-handshake (a KNOWN self-
  inflicted Error-30 source). Present — but does NOT cover Trigger A/B.
- `consecutiveReconnectFailures` backoff in AasdkSession (extended backoff on
  protocol error). Present.
- NO warmup-grace and NO `forcedStopInFlight` classifier in main (the PR#49
  ideas from notes are NOT in the shipped baseline).

## Candidate fix DIRECTIONS (not yet chosen — needs bench proof first)
1. **Don't nuke the whole session + pop the chooser on the FIRST Error-30.**
   The escalation-to-chooser at attempt 2 is the user-visible harm. A silent,
   in-place reconnect (keep the surface, don't foreground the chooser) for the
   first N attempts within a warmup window would hide the churn the stream
   already survives. Lowest-risk, purely car-side, no phone manipulation.
2. **Handshake timeout (Trigger A):** 15s is long; but the real issue is the
   phone proxy stalling. Investigate whether the car re-dials too eagerly
   (CONNECT_SETTLE interaction) and whether a cleaner single retry beats a full
   teardown. Needs a bench repro.
3. **Trigger B is RF** — not fixable in app logic. Only mitigation is the
   sustained-low-bitrate/association-loss reconnect (PR#49 idea) + actual
   car-AP/phone RF work. Do NOT chase this in the decoder.

## Hard rule carried over
Prove ANY candidate on a bench / short drive BEFORE shipping to the `completed`
Play track. The last video-side guess (focus bounce) shipped to `completed` and
broke the car. Transport changes are even easier to get wrong.
