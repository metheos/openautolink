# GAL/PDK compatibility versions

OpenAutoLink exposes a **GAL / PDK protocol version** selector in **Settings → Video**. The selected raw head-unit-requested version applies on the next Android Auto session; use **Save & Reconnect** after changing it.

The safe default and fallback remain **1.7**. Existing installations that had the former Experimental GAL 6.0 Boolean enabled migrate to **6.0**; all other installations remain at 1.7.

## Cumulative version policy

The raw requested version—not a higher compatible tuple reported by the phone—is the sole local policy input.

| Requested version | OAL behavior |
|---|---|
| 1.7 | Legacy display policy and per-packet audio ACKs. |
| 4.3 | Modern display ownership metadata and active media session IDs. |
| 5.0 | Ackless phone-to-HU audio and one advertised codec family per display. |
| 5.1 | Adds typed and raw handling for audio `MediaOptions` (`0x8014`) and navigation `VehicleEnergyForecast` (`0x8008`). |
| 6.0 | Adds typed and raw modern video Start/MediaConfig and video `MediaOptions`, and activates Gearhead's short HEVC keyframe policy. |

All versions retain OAL's proven `max_unacked=30`. Video remains ACKed at every version: legacy 1.7 preserves `session_id=0`, while GAL 4.3+ uses the active video `Start.session_id`. Modern audio is ackless.

## Version-response admission

The AASDK control parser now:

- rejects every response shorter than the six-byte fixed prefix;
- safely decodes major, minor, and raw status as big-endian 16-bit words;
- preserves every trailing response byte for reconstructable diagnostics;
- requires `MATCH`/success;
- for requested 4.3 or newer, requires the reported tuple to be numerically equal or higher;
- accepts 6.1 for a 6.0 request;
- rejects a lower modern response before TLS.

## Display and codec correctness

For GAL 4.3+, hidden clock/battery/signal ownership is serialized in modern `UiConfig`. Whenever hidden UI exists, field-1 margins contain all four explicit edges, including zero values, while legacy total margins remain unchanged.

For GAL 5.0+, the media sink's top-level codec declaration matches the selected codec family in its video configurations. Legacy 1.7/4.3 retain the prior top-level H.264 declaration for compatibility.

## Modern message diagnostics

Known modern envelopes are parsed into typed protobufs without assigning unproven semantics. Complete original bytes are also written as numbered compact-hex chunks below DiagnosticLog's 500-character cap, preserving unknown fields and allowing exact reconstruction.

Typed handling covers:

- rich Start fields and nested `MediaConfig`;
- audio and video `MediaOptions` (`0x8014`);
- navigation `VehicleEnergyForecast` (`0x8008`), including its nested forecast.

Malformed optional modern messages are logged and retained; they do not invent replies or alter session policy.

## Expected H.265 effect

Gearhead's legacy wireless HEVC policy uses a 60-second I-frame interval. GAL 6.0 selects its two-second interval.

| Advertised FPS | GAL 1.7–5.1 | GAL 6.0 |
|---:|---:|---:|
| 60 | ~3600 frames | ~120 frames |
| 30 | ~1800 frames | ~60 frames |

These are framework-derived predictions, not vehicle verification.

## Runtime evidence required

A useful GAL 6 in-car capture must contain:

- `GAL policy: requested=6.0 modernDisplay=true singleCodec=true`
- `GAL negotiated: requested=6.0 response=... status=... trailing_bytes=...`
- `GAL video Start envelope: ... session_type=... media_config_bytes=...`
- typed `MediaConfig`, `MediaOptions`, or `VehicleEnergyForecast` lines when those conditional messages are delivered
- matching `GAL payload id=... chunk=... total_chunks=... hex=...` lines
- `H265-IDR`, `H265-pflow`, and continuous `vflow`

The positive success signal is not merely a 6.x response. It is a stable end-to-end session with video, audio, touch, navigation, assistant, and approximately 120-frame real H.265 IDR spacing at advertised 60 FPS.

## Risk and fallback

If projection, audio, touch, or navigation regresses, select **1.7** and use **Save & Reconnect**. Do not use video-focus bouncing as a keyframe workaround; it caused a verified encoder-throttle regression.

A Play release containing this code is an implementation attempt until the next in-vehicle upload proves the selected policy ran and projection remained healthy.
