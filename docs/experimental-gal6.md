# Experimental GAL/PDK 6.0 compatibility mode

OpenAutoLink normally requests Android Auto GAL 1.7. The **Experimental GAL 6.0**
toggle in **Settings → Video** changes only the next AA session; use **Save &
Reconnect** after changing it.

The toggle is **off by default**.

## What enabling it changes

- Requests raw GAL/PDK version **6.0** instead of 1.7 during the pre-TLS version
  exchange; a rejected version response aborts before TLS instead of continuing
  under an invalid assumption.
- Preserves OAL's proven 30-frame AV setup window; GAL 5+ ackless audio no
  longer uses this value as per-buffer ACK credit.
- Treats phone-to-head-unit audio as ackless; legacy sessions continue sending
  per-frame audio ACKs.
- Uses the active `Start.session_id` for legacy audio ACKs and all video ACKs.
- Parses and logs GAL 5/6 `Start` extensions (`session_type` and raw
  `media_config`).
- Explicitly accepts and logs standalone audio/video `MediaOptions` (`0x8014`).
- Explicitly accepts and logs navigation `VehicleEnergyForecast` (`0x8008`).
- Sends modern `UiConfig.hidden_ui_elements` values for the existing hide-clock,
  hide-battery, and hide-signal preferences; the legacy session bitmask remains
  populated too.

## Expected H.265 effect

Gearhead's legacy wireless HEVC policy uses a 60-second I-frame interval. Android's
OMX codec path converts that to a frame count using the configured frame rate, so
60 FPS produces an approximately 3600-frame GOP. OAL measured normal real HEVC
IDRs at 3575–3641-frame intervals.

Gearhead's GAL 6 path selects its 2-second HEVC interval. Predictions:

| Advertised FPS | Legacy GAL | GAL 6.0 |
|---:|---:|---:|
| 60 | ~3600 frames | ~120 frames |
| 30 | ~1800 frames | ~60 frames |

These are framework-derived predictions, not vehicle verification.

## Runtime evidence

Uploaded diagnostic logs use the `gal6` tag. A useful GAL 6 test must contain:

- `GAL policy: requested=6.0 experimental=true`
- `GAL negotiated: requested=6.0 response=... status=...`
- `GAL6 video Start envelope: ... session_type=... media_config_bytes=...`
- any `GAL6 MediaOptions ...` and `GAL6 VehicleEnergyForecast ...` lines
- `H265-IDR` and `H265-pflow` lines
- continuous `vflow` after the session reaches streaming

The positive success signal is not merely a 6.x version response. It is a stable
end-to-end session with video, audio, touch, navigation, and substantially shorter
**frame-count** spacing between real H.265 IDRs.

## Risk and fallback

GAL 5.0+ changes audio flow control and AV start envelopes; GAL 5.1+ adds
asynchronous MediaOptions and VehicleEnergyForecast; GAL 6.0 changes video media
options and HEVC behavior. The mode is therefore experimental even though OAL
accepts these known envelopes.

If projection, audio, touch, or navigation regresses, turn the toggle off and use
**Save & Reconnect** to return to GAL 1.7. Do not use video-focus bouncing as a
keyframe workaround: it caused a verified encoder-throttle regression.
