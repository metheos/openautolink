# Post-mortem: the #35 focus bounce starved projected video

**Date:** 2026-07-16

**Regressing build:** 0.1.361 (vc361), built from commit `3ec6cfec` on
`fix/force-focus-keyframe-reimpl-35`. The test build was distributed, but the
branch was not merged.

**Rollback build:** 0.1.362 (vc362), built from `main` commit `d6afb55b`, the
clean pre-#35 baseline.

## Symptom

On a cold connection with vc361, video appeared from the cached seed IDR and then
froze for the drive. The captured stream metrics dropped to roughly 0–12 kbps
about one second after the focus bounce; 104 of 140 bitrate samples were below
60 kbps. The same test recorded no subsequent real IDRs, compared with 19 in the
vc359 baseline on the same phone and car. Upload controls, vehicle data, and
audio continued to work, isolating the regression to projected video.

The regression was not left in the release line: vc362 restored the unmodified
`main` behavior by removing the focus-bounce change.

## Change that caused the regression

The vc361 implementation tried to obtain a fresh IDR by sending two unsolicited
video-focus notifications in immediate succession:

1. `VIDEO_FOCUS_NATIVE_TRANSIENT`
2. `VIDEO_FOCUS_PROJECTED`

That assumption was wrong. `NATIVE_TRANSIENT` describes a temporary loss of
projected focus; it is not a keyframe-request mechanism.

## Phone-side behavior evidence

The static analysis used the Android Auto package
`com.google.android.projection.gearhead`, version
`16.9.666314-release` (version code `169666314`). The artifact is proprietary and
its decompiled symbols are obfuscated, so this document records the relevant
behavior rather than depending on a local teardown path or unstable method name:

- An incoming transition to `VIDEO_FOCUS_NATIVE_TRANSIENT` enters the same
  video-focus-lost handler as `VIDEO_FOCUS_NATIVE`, with only the `transient`
  flag changed.
- That path logs `VideoFocus lost`, clears the projected-focus state, and queues
  a transient focus-lost event for the video pipeline.
- A transition back to `VIDEO_FOCUS_PROJECTED` enters a separate focus-gained
  path. That path queues focus-gained work asynchronously and also has a
  rejection path that logs `Video focus rejected` and `Relinquishing video
  focus`.

This static behavior explains why a rapid yield-and-reacquire is not equivalent
to requesting an IDR. The timing from the vc361 drive supplies the behavioral
link: bitrate collapsed immediately after the new bounce, real IDRs stopped, and
the rollback removed the failure. The evidence supports the focus bounce as the
cause of this regression; it does not establish that every Android Auto version
or phone encoder reacts identically.

## Why pre-flight review missed it

Review checked that an outbound focus notification would not invoke the car
app's own inbound `onVideoFocusRequest` teardown path. It did not check the
phone's semantics for receiving `NATIVE_TRANSIENT`. Compilation and the absence
of a local car-side teardown were therefore insufficient safety checks.

## Rule and follow-up

Do not use `VIDEO_FOCUS_NATIVE` or `VIDEO_FOCUS_NATIVE_TRANSIENT` to provoke a
keyframe. Those modes tell the phone that native UI—not projected video—owns the
display. The protocol also defines `VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS`; that
mode remains projected but without input focus, and this investigation did not
test it as a keyframe mechanism. No focus transition should be treated as a
keyframe request without separate phone-side and runtime evidence.

The slow-first-keyframe / seed-IDR stall remains open. A future approach must not
change video focus; candidates include relying on the phone's natural GOP or a
decoder-side recovery mechanism. Validate any replacement on a bench or short
drive, and compare frame arrival, bitrate, and real-IDR counts before shipping.
