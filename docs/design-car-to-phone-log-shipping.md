# Design note: car→phone log shipping (phone-side upload)

**Status:** idea / not built. Captured 2026-07-01.

**Motivation:** the car head unit may not have internet connectivity, while the
phone will often retain a mobile-data path. The phone is therefore a better
candidate for uploading diagnostics; the car should not require its own internet
connection.

## Current behavior

- Each app's `LogUploader` reads only that app's private log directory. The car
  implementation is in `app/src/main/java/com/openautolink/app/diagnostics/LogUploader.kt`;
  the phone implementation is in
  `companion/src/main/java/com/openautolink/companion/diagnostics/LogUploader.kt`.
  Android sandboxing and the device boundary prevent either app from directly
  reading the other's files.
- Consequently, the car uploads car logs and the companion uploads companion
  logs. There is no cross-device log transfer today.
- Both paths require the uploading device to have connectivity at upload time.
  That is the weakness on a head unit without an internet path.

## Proposed design

Two possible shapes were considered:

1. **Phone pulls car logs on demand.** This is a poor fit for the failures of
   interest: if the car↔phone connection is already down, the phone cannot pull
   the logs describing the drop.

2. **Car ships logs to the phone opportunistically while connected**
   (**recommended**).
   - The car continues to keep its rolling on-disk logs as the source of truth.
   - While the car↔phone link is healthy, the car sends log deltas to the
     companion over a side channel or the existing OAL transport.
   - The companion persists received car logs and uploads them later using its
     own network path.
   - Logs sent before a disconnect remain available on the phone. The car keeps
     any unsent tail and catches up after a later reconnect.

## Hard constraints and open questions

- **Assume the link can drop at any time.** Shipping must be best-effort catch-up,
  not the only copy of the logs.
- **Transport:** reuse the existing companion connection with a new control
  message, or add a separate socket. Reuse reduces connection management but log
  traffic must be low priority so it cannot interfere with projection.
- **Deduplication and ordering:** define stable source, session, file, offset, and
  chunk identifiers. Both the companion and upload receiver must accept retries
  and overlapping ranges without duplicating records. Do not depend on an
  implementation-specific server deduplication rule.
- **Source identity:** logs relayed by the phone must retain the car's source
  identity rather than inheriting the phone's label. The upload format therefore
  needs an explicit source-device field.
- **Privacy and size:** diagnostics can contain sensitive device or location
  data. Keep collection opt-in, authenticate uploads, bound the retained data,
  and document what is collected before implementation.

## Repository and distribution boundaries

- Both applications are in this repository: the car app is the `app` module and
  the phone app is the `companion` module.
- Companion changes are built from this source tree and distributed through the
  GitHub Actions companion workflow (`.github/workflows/build-companion.yml`).
- Any upload-receiver changes are outside the Android modules and must be
  designed alongside the message and upload formats; no existing private server
  layout is assumed by this note.

## Not doing now

This is a design record, not an implemented feature. The shipped behavior remains
independent, manually triggered uploads from each device until a cross-device
protocol is designed, implemented, and tested.
