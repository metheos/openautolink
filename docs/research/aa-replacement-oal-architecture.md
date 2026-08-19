# Android-Auto-independent OpenAutoLink successor: architecture and feasibility audit

**Audit baseline:** repository `mossyhub/openautolink`, `main` at `71244797` (2026-08-18, merge of PR #83).

**Scope:** tracked car app, companion, native JNI/aasdk/protobuf, Templates Host/cluster, VHAL, media/audio, touch/input, transport, build/distribution, tests, and public repository documentation.

**Excluded:** generated build trees, prebuilt third-party contents except their build/link boundary, external dependency internals except the pinned aasdk boundary, bulk reverse-engineering corpora, and SBC build outputs. Decisive reverse-engineering conclusions are summarized in this document rather than delegated to those local corpora.

## Executive conclusion

The requested end state combines three separate goals:

1. no dependency on Google's phone-side Android Auto (`gearhead`);
2. display the **official** Maps and Spotify app UIs;
3. keep OpenAutoLink's shell, chrome, features, and layout in control.

A normal Play-installed AAOS app cannot satisfy all three at once.

- A normal app **can launch** installed native AAOS Maps or Spotify as separate tasks. It cannot embed their activities/surfaces inside its own Compose hierarchy, rearrange their internal UI, keep its own chrome reliably above them, or force their system-bar/cluster policy.
- A normal app **can own the whole screen** and build a Maps/Spotify-like dashboard from public SDKs/APIs. That is not the official apps' UI and does not inherit all native app behavior, offline assets, account state, or private car integrations.
- A normal app **can render a remote surface it owns** and forward touch/audio. It cannot turn arbitrary official phone apps into a seamless projection product without MediaProjection consent, app-specific audio-capture permission, input-injection/Accessibility constraints, secure-surface limitations, and substantial Play-policy risk.
- A platform-signed/OEM app can host tasks with system windowing primitives, alter CarSystemUI policy, coordinate cluster/HFP/VHAL, and arbitrate other apps. That requires OEM platform keys, priv-app allowlists, SELinux integration, or root/custom firmware. Play installation, regardless of release track, does not grant those capabilities.

**Therefore:** under the current no-ADB/no-sideload, Play-only, unprivileged deployment, the realistic AA-independent choices are either (a) an OpenAutoLink dashboard that hands off full-screen to official native apps, or (b) an OpenAutoLink-owned dashboard that uses public navigation/media APIs rather than embedding the official apps. The complete “official apps inside our shell” product is an OEM/partner architecture, not an app-only migration.

## 1. Current architecture at a glance

### 1.1 Runtime topology

The current car app is an Android Auto head-unit implementation, not merely a generic renderer:

```text
Phone
  Google Android Auto / gearhead
       │ localhost AA socket
       ▼
  OAL companion proxy
       │ TCP over shared Wi-Fi, normally car-dialled
       ▼
AAOS car app
  AasdkSession.kt
       │ InputStream / OutputStream
       ▼ JNI
  JniTransport → aasdk TLS/framing/multiplexing
       ├─ video → MediaCodecDecoder → SurfaceView
       ├─ audio → AudioPlayerImpl → AudioTrack purpose slots
       ├─ nav/media/phone state → Kotlin domain flows
       └─ touch/key/GPS/VHAL/mic → AA protobuf channels → phone
```

The core path is visible in:

- `companion/src/main/java/com/openautolink/companion/connection/AaProxy.kt`
- `companion/src/main/java/com/openautolink/companion/service/TcpAdvertiser.kt`
- `app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt`
- `app/src/main/java/com/openautolink/app/transport/aasdk/AasdkTransportPipe.kt`
- `app/src/main/cpp/jni_transport.cpp`
- `app/src/main/cpp/jni_session.cpp`
- `app/src/main/cpp/jni_channel_handlers.cpp`

`AasdkTransportPipe` is the narrow byte-stream boundary. Everything after it in native code is AA-specific; most rendering/playback code after the JNI callbacks is reusable.

### 1.2 Wireless startup on AA 17.4+

The active WPP path is more subtle than “the phone connects directly to the car”:

1. `app/src/main/java/com/openautolink/app/OalApplication.kt` starts process-scoped `AaWirelessBtControl`.
2. `app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtServer.kt` advertises Google's AA UUID and performs the WPP RFCOMM/protobuf exchange.
3. The car probes the phone companion for its current local AA-proxy port through `PhoneDiscovery`/the companion identity service.
4. The Bluetooth response normally advertises `127.0.0.1:<companion-proxy-port>` to gearhead.
5. `AasdkSession.startWpp()` switches to a car-outbound dial when the chosen endpoint is phone loopback; the companion splices that car socket to gearhead's localhost socket.

This is encoded in:

- `app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt`
- `app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtServer.kt`
- `app/src/main/java/com/openautolink/app/transport/PhoneDiscovery.kt`
- `app/src/main/java/com/openautolink/app/transport/hotspot/WppTcpServer.kt`
- `companion/src/main/java/com/openautolink/companion/service/TcpAdvertiser.kt`

The companion remains required because the car's telematics AP does not provide a dependable phone-to-car inbound path. The top-level `README.md` correctly states this. The older class comment in `WppTcpServer.kt` saying the companion is not in the wireless path is stale relative to the current loopback endpoint selection.

### 1.3 Car-side orchestration

`app/src/main/java/com/openautolink/app/session/SessionManager.kt` is the current composition root. It constructs and wires:

- `MediaCodecDecoder`
- `AudioPlayerImpl`
- `MicCaptureManager`
- `VehicleDataForwarderImpl`
- `ImuForwarder` and direct `LocationManager` forwarding
- process-wide `OalMediaSessionManager`
- `ClusterManager`
- `AasdkSession`
- diagnostics, watchdogs, reconnect, sleep/wake, and UI state

It is effective but is also the largest migration hazard: backend-independent concerns and AA negotiation/state are interleaved in one 2,600+ line class. A successor should not add a second protocol directly into this class.

## 2. Hard Android Auto dependencies

### 2.1 Native protocol core — replace for an AA-independent successor

The following are fundamentally an AA head-unit implementation:

- `app/src/main/cpp/jni_session.cpp`
  - AA version/TLS/auth/session lifecycle
  - AA Service Discovery Response construction
  - AA channel IDs and typed services
  - AA video focus, audio focus, ping, ByeBye, media ACKs
  - AA InputReport and SensorBatch sends
- `app/src/main/cpp/jni_session.h`
- `app/src/main/cpp/jni_channel_handlers.cpp`
- `app/src/main/cpp/jni_channel_handlers.h`
- `app/src/main/cpp/aasdk_jni.cpp`
- `app/src/main/java/com/openautolink/app/transport/aasdk/AasdkNative.kt`
- `app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSessionCallback.kt`
- `external/opencardev-aasdk` and the prebuilt `libaasdk.a` link boundary in `app/src/main/cpp/CMakeLists.txt`

The native SDR currently advertises AA services for main video, three audio sinks, microphone source, sensors, input, optional Bluetooth, navigation status, media playback status, and phone status. These are the source of Maps/Spotify/call metadata precisely because gearhead sends them; removing AA removes those feeds too.

### 2.2 Protobuf schemas — compatibility assets, not a successor domain model

Despite the `oal` package name, these files model Google's AA wire contract:

- `app/src/main/proto/oal/control.proto`
- `app/src/main/proto/oal/media.proto`
- `app/src/main/proto/oal/input.proto`
- `app/src/main/proto/oal/navigation.proto`
- `app/src/main/proto/oal/playback.proto`
- `app/src/main/proto/oal/sensors.proto`
- `app/src/main/proto/oal/vehicle_energy_model.proto`
- `app/src/main/proto/oal/wireless.proto`

They are valuable reverse-engineering knowledge and should remain in an AA backend if compatibility is retained. They should not become the public protocol for an AA-independent successor. A successor protocol should use explicit OAL-owned messages and versioning, with adapters translating AA only inside an optional AA plugin.

### 2.3 Companion AA coupling

Hard dependencies:

- `companion/src/main/java/com/openautolink/companion/connection/AaProxy.kt` exists to splice gearhead's localhost socket to the car.
- `TcpAdvertiser.fireAaLaunchIntent()` in `companion/src/main/java/com/openautolink/companion/service/TcpAdvertiser.kt` targets `com.google.android.projection.gearhead` and its wireless startup receiver/activity.
- `app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtServer.kt` publishes Google's AA UUID and WPP protocol.

Reusable scaffolding exists around those dependencies:

- foreground-service lifecycle in `CompanionService.kt`
- Bluetooth/Wi-Fi auto-start in `companion/src/main/java/com/openautolink/companion/autostart/`
- Wi-Fi attachment in `companion/src/main/java/com/openautolink/companion/wifi/CarWifiManager.kt`
- mDNS/TCP/UDP identity discovery in `TcpAdvertiser.kt` and car-side `PhoneDiscovery.kt`
- logging/upload in both modules

A custom successor companion could reuse those pieces while deleting the gearhead launch/proxy contract.

### 2.4 AA-shaped assumptions above the protocol layer

These components are reusable only after AA-specific behavior is isolated:

- `MediaCodecDecoder.kt` contains generic MediaCodec logic but also gearhead-specific seed-IDR thresholds, expected GOP behavior, VideoFocus/keyframe limitations, and AA stream diagnostics.
- `ControlMessage.kt` mixes useful domain objects with AA channel vocabulary and enum meanings.
- `SteeringWheelController.kt` maps directly to AA keycodes.
- `NavigationDisplayImpl.kt` assumes incoming AA `NavigationState`/turn events.
- `OalMediaSessionManager.kt` assumes media metadata arrives from AA's media playback status channel.
- `AudioPurposeCoordinator.kt` has a useful purpose model, but `AasdkSession.onAudioFrame()` defines how AA channel purpose numbers become those purposes.

## 3. Reusable components and replacement seams

### 3.1 Strong reuse candidates

#### Shell and display composition

- `app/src/main/java/com/openautolink/app/MainActivity.kt`
- `app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt`
- `app/src/main/java/com/openautolink/app/ui/components/`
- `app/src/main/java/com/openautolink/app/ui/settings/`
- `app/src/main/res/values/themes.xml`

`ProjectionScreen` already proves OAL can own a Compose shell around one `SurfaceView`, preserve that surface underneath settings/diagnostics overlays, calculate panel/render rectangles, and place independent controls/HUDs above the projected surface. This is an excellent shell foundation.

The limitation is ownership: the surface must be produced by OAL or a backend OAL controls. A normal app cannot substitute “the Surface of native Google Maps” or “the Spotify Activity” into this `AndroidView`.

#### Video

- `app/src/main/java/com/openautolink/app/video/VideoDecoder.kt`
- `app/src/main/java/com/openautolink/app/video/VideoFrame.kt`
- `app/src/main/java/com/openautolink/app/video/CodecSelector.kt`
- `app/src/main/java/com/openautolink/app/video/NalParser.kt`
- `app/src/main/java/com/openautolink/app/video/MarginAutoCalc.kt`
- `app/src/main/java/com/openautolink/app/video/MediaCodecDecoder.kt`

Reuse for a custom phone renderer or another owned video backend. Before reuse, move AA/gearhead policies out of `MediaCodecDecoder` into a stream policy/config object: keyframe classification, seed handling, expected IDR cadence, focus/request semantics, and reconnect diagnostics.

#### Audio and microphone

- `app/src/main/java/com/openautolink/app/audio/AudioPlayer.kt`
- `AudioPlayerImpl.kt`
- `AudioPurposeCoordinator.kt`
- `AudioPurposeSlot.kt`
- `AacDecoder.kt`
- `MicCaptureManager.kt`

The AudioTrack purpose routing and AudioAttributes are useful for any OAL-owned audio backend. HFP call audio is not provided by these classes; the native vehicle Bluetooth/audio stack remains the real call endpoint. `HfpPresenceServer` is a presence shim, not an HFP Audio Gateway.

One documentation mismatch needs cleanup during migration: several comments/docs still describe an active 500 ms ring buffer, while current `AudioPurposeSlot.kt` writes on a per-purpose executor and exposes ring-buffer sizes as zero. Treat tests/code, not `docs/architecture.md` or old milestone prose, as current truth.

#### Input and vehicle context

- `app/src/main/java/com/openautolink/app/input/TouchForwarder.kt`
- `TouchForwarderImpl.kt`
- `TouchScaler.kt`
- `KeyCaptureBus.kt`
- `SteeringWheelController.kt` (after replacing AA keycode targets with backend actions)
- `VehicleDataForwarder.kt`
- `VehicleDataForwarderImpl.kt`
- `IgnitionMonitor.kt`
- `GnssForwarder.kt` / `GnssForwarderImpl.kt`
- `ImuForwarder.kt`

Touch scaling/multitouch are directly reusable for an OAL-owned surface. VHAL/GNSS/IMU are reusable as shell context providers or custom navigation inputs. The current VHAL layer is read-oriented and gracefully handles unavailable properties.

#### Diagnostics, preferences, lifecycle knowledge

- `app/src/main/java/com/openautolink/app/diagnostics/`
- `companion/src/main/java/com/openautolink/companion/diagnostics/`
- `app/src/main/java/com/openautolink/app/data/AppPreferences.kt`
- `app/src/main/java/com/openautolink/app/OalApplication.kt`

Crash persistence, uploaded logs, process-scoped services, ignition-cycle state reset, and explicit wake/reconnect diagnostics are highly reusable. Keep the learned lifecycle invariants even if AA is removed.

### 3.2 Natural interface seam to create

Introduce an OAL-owned backend contract above transport/protocol:

```kotlin
interface OalContentBackend {
    val state: StateFlow<BackendState>
    val video: Flow<EncodedVideoFrame>       // optional
    val audio: Flow<PlaybackAudioFrame>      // optional
    val navigation: StateFlow<NavigationSnapshot?>
    val media: StateFlow<MediaSnapshot?>
    val calls: StateFlow<CallSnapshot?>

    suspend fun start(config: BackendConfig)
    suspend fun stop(reason: StopReason)
    fun attachInput(sink: InputEvents)
    fun sendVehicleContext(context: VehicleContext)
}
```

Then implement separate backends:

- `AaBackend` wrapping current `AasdkSession`/JNI stack;
- `NativeHandoffBackend` that launches native apps and exposes only shell-owned cards;
- `ApiDashboardBackend` using public navigation/media APIs;
- `RemoteRendererBackend` using a new OAL companion protocol.

`SessionManager.kt` should become a backend-agnostic coordinator. AA SDR fields, keyframe watchdogs, WPP state, and gearhead-specific reconnect policy belong inside `AaBackend`, not the shared manager.

## 4. Cluster and Templates Host

### 4.1 What works unprivileged today

The current cluster path is an important reusable success:

- manifest declarations in `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/automotive_app_desc.xml`
- `app/src/main/java/com/openautolink/app/cluster/OalClusterService.kt`
- `ClusterManager.kt`
- `ClusterMainSession.kt`
- `OalClusterSession.kt`
- `ClusterNavigationState.kt`
- `app/src/main/java/com/openautolink/app/navigation/`

OAL uses the Android Car App Library and the privileged Google Templates Host as an intermediary. OAL sends structured `Trip`/maneuver data through `NavigationManager`; the Templates Host and GM services render approved cluster UI. This is the correct unprivileged seam.

### 4.2 What it does not provide

- It does not grant arbitrary drawing or a general cluster surface.
- It does not let OAL extract turn-by-turn state from native Google Maps.
- It does not let OAL show the full Maps UI on the cluster.
- It does not grant direct `CAR_DISPLAY_IN_CLUSTER` or `CAR_NAVIGATION_MANAGER`; the Templates Host owns those privileged permissions.

The analyzed GM cluster path carries structured navigation metadata that the vehicle renders locally; it is not a general projected-video path. A regular Play app cannot route arbitrary content to the physical GM cluster display. For a successor, use the existing metadata/Templates Host path and do not plan around a cluster video surface unless OEM access is secured.

### 4.3 Successor implications

- **Native handoff to official Maps:** Maps remains responsible for its own Templates Host/cluster integration while it is the foreground navigation app. OAL cannot mirror Maps' private navigation state into its own shell.
- **API-composed navigation:** OAL can generate its own `NavigationSnapshot` and reuse the existing cluster adapters.
- **Remote phone renderer:** the companion must send normalized navigation metadata separately; inferring cluster state from pixels is not viable.

## 5. VHAL and vehicle control constraints

### 5.1 Reusable read path

`VehicleDataForwarderImpl.kt` reads AOSP-standard properties via `CarPropertyManager` reflection and hardcoded stable IDs for stripped GM fields. The manifest requests normal/dangerous read permissions for speed, energy, powertrain, environment, car info, energy ports, mileage, tires, and dynamics.

This can power an AA-independent dashboard: speed/gear/ignition, battery/range/charge state, outside temperature, tire/ABS data where exposed, and vehicle identity.

### 5.2 Hard privilege wall

The GM package and permission audit established the following boundaries:

- vendor properties require `CAR_VENDOR_EXTENSION` (`signature|privileged`);
- Ultifi/domain services use GM-signature permissions;
- door/window/seat/climate/mirror/light/horn controls require privileged control permissions;
- a normal app has essentially no vehicle write authority.

The current successor can **display** car data but should not promise deep vehicle controls. Any architecture whose shell includes HVAC, drive mode, charging schedule writes, ADAS, or body controls requires OEM privileges or an external sanctioned interface.

Do not build product behavior on the observed exported `com.gm.vehicleinfo.HistoryProvider` weakness. A vulnerability is neither a stable API nor an acceptable product dependency.

## 6. Media, audio, calls, and official Spotify

### 6.1 Current AA-derived media path

Today, Spotify metadata/control works because gearhead publishes AA media playback status and OAL translates it:

- AA parsing: `JniMediaStatusHandler` in `app/src/main/cpp/jni_channel_handlers.cpp`
- Kotlin event bridge: `AasdkSession.kt`
- AAOS publication: `app/src/main/java/com/openautolink/app/media/OalMediaSessionManager.kt`
- discoverability: `OalMediaBrowserService.kt`

Removing AA removes the metadata source; the process-wide MediaSession remains reusable as an **output** for whatever successor backend supplies.

### 6.2 Native Spotify handoff

An unprivileged shell can launch installed Spotify with an intent. It cannot embed Spotify's activity. Media control/metadata options are narrower:

- bind to any public/exported MediaBrowser contract Spotify exposes, if available and permitted;
- use a user-approved notification-listener/media-controller approach where platform policy permits;
- use Spotify's public Web API/Web Playback SDK with OAuth and account/subscription/network constraints;
- hand off to the native Spotify UI and let the AAOS system media center own playback controls.

None is equivalent to placing the official Spotify UI inside OAL's layout. The most robust unprivileged design is full-screen task handoff plus an OAL-owned, API-fed media card when available.

### 6.3 Calls

Presentation, control, and audio transport must remain separate:

- AA currently supplies projected call presentation/control state.
- The vehicle's native Bluetooth HFP/SCO stack carries call audio.
- OAL's app cannot implement/select HFP profiles or SCO as a normal app; relevant APIs require `BLUETOOTH_PRIVILEGED`.
- Native AAOS dialer takeover/suppression is controlled by OEM projection state and dialer settings, not by OAL's AudioTrack layer.

An AA-independent unprivileged successor should defer calls to the native dialer/HFP stack and avoid promising an owned call overlay beyond public notification state.

## 7. Touch and input constraints

The existing touch path is reusable only for a surface OAL owns:

- Compose overlay and `SurfaceView`: `ProjectionScreen.kt`
- coordinate normalization: `TouchForwarderImpl.kt`, `TouchScaler.kt`
- AA serialization: `AasdkSession.kt` → `AasdkNative.kt` → `JniSession::sendTouchEvent`

For native Maps/Spotify activities, Android delivers touch directly to those apps. OAL cannot intercept and redirect arbitrary touches while preserving its own task without system window/task privileges.

For a phone-mirroring backend, displaying pixels is only half the problem. Injecting touch into arbitrary phone apps normally requires Accessibility, privileged input injection, or app cooperation. That is a major UX, safety, and distribution constraint—not a small protocol feature.

Steering-wheel input has the same split:

- `SteeringWheelController.kt` can map keys to backend-neutral actions.
- GM consumes some keys (notably voice) before they reach the app; this is an observed platform boundary, not something OAL can override.
- controlling another native app's MediaSession reliably requires platform/system mediation; OAL cannot assume every key reaches its foreground Activity.

## 8. Transport inventory and successor value

### 8.1 Reusable transport primitives

- `AasdkTransportPipe.kt`: generic blocking byte-stream adapter, despite its name.
- `TcpConnector.kt`: reusable TCP client pattern, keepalive tuning, mDNS/gateway fallback.
- `PhoneDiscovery.kt`: useful mDNS/UDP/TCP identity discovery and multi-peer correlation.
- companion identity/mDNS/foreground service in `TcpAdvertiser.kt` / `CompanionService.kt`.
- `NetworkInterfaceScanner.kt`, `HostUsability.kt`, and diagnostics network probes.

These can underpin a custom OAL protocol if renamed and stripped of AA/WPP assumptions.

### 8.2 AA-specific transports

- `AaWirelessBtControl.kt` / `AaWirelessBtServer.kt`: Google's WPP and AA UUID.
- `WppTcpServer.kt`: WPP role adaptation.
- `UsbConnectionManager.kt`, `UsbAccessoryMode.kt`, `UsbTransportPipe.kt`: AOA transport designed to make the phone enter Android Auto accessory mode. The stream abstraction is reusable, but the AOA identity/handshake is AA-specific.
- `CarWifiManager.kt`: generic Wi-Fi joiner, but current WPP setup intentionally avoids running it because gearhead owns association.

### 8.3 Legacy bridge

`bridge/` and `docs/protocol.md` preserve an OAL-owned three-channel protocol and a headless SBC implementation. It is not the active topology, but it demonstrates a useful architectural idea for a successor: OAL-owned control messages with independent video/audio channels. Do not revive the whole SBC design blindly; salvage the protocol separation and mockability.

## 9. Deployment and build constraints

### 9.1 Car app

- Package: `com.openautolink.app`
- normal AAOS app, `minSdk 32`, `targetSdk 36`
- distributed as a signed AAB through Google Play
- no car ADB and no sideload path
- `app/src/main/AndroidManifest.xml` requires automotive and Templates Host features
- native ABIs: `arm64-v8a`, `x86_64`

Play installation grants no platform signature, priv-app placement, SELinux domain, hidden API access, secure settings writes, system-window control, or app-task embedding authority.

The app can request normal/dangerous runtime permissions. Signature/privileged declarations either remain unavailable or are ignored/denied for the normal UID.

### 9.2 Companion

The companion is a normal Android app distributed through GitHub release APKs. `release-apk.yml` builds both modules from a release tag; the car's actual in-vehicle path remains the Play AAB flow. A successor companion can continue using GitHub distribution, but Accessibility, MediaProjection, notification-listener, or VPN-style capabilities would add significant setup and policy friction.

### 9.3 Native dependency and CI constraints

- `app/src/main/cpp/CMakeLists.txt` links prebuilt aasdk/OpenSSL/protobuf/abseil artifacts.
- `.github/workflows/build-native-deps.yml` refreshes those artifacts when the aasdk pointer/build scripts change.
- `.github/workflows/ci.yml` currently runs car-app JVM unit tests only.
- `.github/workflows/release-apk.yml` compiles release APKs but is not the car's Play deployment path.
- `scripts/linux/bundle-release.sh` builds the signed car AAB; the external signing tooling uploads it.

Removing AA from the default backend could eliminate the native aasdk/protobuf/OpenSSL footprint and simplify car builds substantially. Keep it only if AA remains an optional compatibility backend.

### 9.4 Automotive app-policy constraint

The existing internal-track installation proves the package can be delivered, not that arbitrary public automotive functionality will pass every app-quality/category review. A successor that acts as a general launcher, mirrors arbitrary apps, or uses Accessibility/overlays should be treated as a distribution-risk change. Internal testing is a delivery channel, not a privilege or policy bypass.

## 10. Tests, issues, plans, and design-knowledge quality

### 10.1 Tests present

Tracked JVM tests cover useful reusable units:

- audio frame/purpose/ring-buffer/mic logic under `app/src/test/java/com/openautolink/app/audio/`
- touch/scaling/steering/GNSS under `app/src/test/java/com/openautolink/app/input/`
- navigation mapping/formatting under `app/src/test/java/com/openautolink/app/navigation/`
- state/recovery under `app/src/test/java/com/openautolink/app/session/`
- host usability under `app/src/test/java/com/openautolink/app/transport/`
- codec/NAL/frame logic under `app/src/test/java/com/openautolink/app/video/`

Gaps relevant to a successor:

- companion JVM coverage is limited to five focused WPP startup, proxy-port, and
  network-binding policy tests under `companion/src/test/`;
- no backend contract tests because no backend seam exists yet;
- no current connected/instrumentation suite in CI;
- CI does not run the companion tests or explicitly exercise a native build/test
  target;
- old mock-bridge and testing docs primarily describe the retired SBC topology.

A migration should add backend contract tests and a fake backend before moving code.

### 10.2 Baseline limitations that remain on current `main`

The 2026-08-18 `main` baseline still has no backend contract, only five focused
companion JVM policy/contract tests, no connected/instrumentation suite in CI,
and no CI gate that runs companion tests or explicitly exercises the native
build. Hardware and distribution variability therefore remain material migration
risks. None changes the Android certificate, task-embedding, secure-output, or
platform-permission boundaries described above.

### 10.3 Evidence precedence

For implementation claims, prefer the current tracked source and build configuration over older architecture or milestone prose. Some older public documents describe the retired SBC topology or superseded pure-Kotlin protocol work. Reverse-engineering observations are useful evidence for the optional AA backend, but they do not create Android platform privileges and are summarized here wherever they affect the feasibility decision.

## 11. Candidate architectures, ranked by feasibility

No unprivileged candidate fully meets all three headline goals. Rankings below are for the current Play-only vehicle.

### Rank 1 — Native-app handoff dashboard

**Shape:** OAL owns a home/dashboard Activity with vehicle cards, shortcuts, diagnostics, and optional media/nav summaries. Tapping Maps or Spotify launches the official native AAOS app as a separate task. Back/Home returns to OAL.

**Feasibility:** high without system privileges.

**Meets:** no AA; official native apps are displayed.

**Does not meet:** persistent OAL shell/layout while those apps are foreground; arbitrary embedding or resizing.

Reuse:

- `MainActivity.kt`, Compose UI/components/settings
- VHAL/GNSS/IMU and diagnostics
- process MediaSession for OAL-owned content
- native app intents and explicit task handoff
- Templates Host cluster path only for OAL-owned navigation; native Maps handles itself otherwise

This is the fastest honest AA-independent product and the best Phase 1 target.

### Rank 2 — API-composed OAL dashboard

**Shape:** OAL renders navigation and media itself using supported public APIs/SDKs, while launching official apps for features not represented in APIs.

**Feasibility:** medium-high technically; medium/unknown commercially due API terms, keys, quotas, account requirements, offline behavior, and automotive SDK availability.

**Meets:** no AA; OAL owns the layout and feature composition.

**Does not meet:** official app UI. It is an OAL client of official services.

Possible providers:

- approved navigation SDK or map rendering API for OAL-owned maps/guidance;
- Spotify Web API/Web Playback SDK for metadata/control/playback where account eligibility permits;
- native-app handoff as fallback.

This is the strongest app-only architecture for a genuinely owned shell.

### Rank 3 — Keep AA as a modular compatibility backend

**Shape:** first refactor current AA into `AaBackend`, then place OAL-owned cards/chrome around the single AA surface. Add native handoff/API backends later.

**Feasibility:** high because most code already exists.

**Meets:** official phone-projected Maps/Spotify and an OAL-owned outer shell.

**Does not meet:** AA independence; cannot split/rearrange the internal Maps and Spotify surfaces because gearhead sends one composed video stream.

This is the lowest-risk migration bridge and should remain available until an AA-independent backend reaches feature parity. It is not the end state requested.

### Rank 4 — Custom phone companion remote renderer

**Shape:** replace gearhead/aasdk with an OAL-owned protocol. The companion renders OAL-owned UI or captures an allowed phone display; the car reuses video/audio/touch primitives.

**Feasibility:**

- medium for an OAL-owned remote UI or browser/API dashboard;
- low for arbitrary official app mirroring;
- very low for a seamless, automatic, Play-friendly official-app projection replacement.

Blockers for official phone apps:

- MediaProjection consent/lifecycle;
- secure-window and app capture opt-out;
- playback-audio capture opt-in/usage restrictions;
- touch injection requiring Accessibility/privileged APIs or app cooperation;
- lock-screen/background-start restrictions;
- no equivalent public channel for Maps turn metadata, call integration, AA safety restrictions, or cluster state;
- significant phone battery/thermal load and Play policy review.

Use this only if the product explicitly narrows scope to OAL-owned content.

### Rank 5 — OEM/system task-host shell

**Shape:** platform-signed OAL/CarSystemUI component uses system task/container APIs to host Maps and Spotify activities in managed regions, controls system bars, arbitrates audio/focus/cluster, and accesses privileged VHAL/HFP services.

**Feasibility:** low for this project under current deployment; high only with OEM/Google/vehicle-platform partnership.

**Meets:** the complete product vision in principle.

Requires some combination of:

- platform signing and `/system/priv-app` placement;
- privapp permission allowlists;
- task/window organizer or OEM TaskView integration;
- CarSystemUI and `android.car.SYSTEM_BAR_VISIBILITY_OVERRIDE` control;
- direct cluster/display permissions;
- privileged Bluetooth/HFP and vehicle-control APIs;
- SELinux policy and firmware integration.

A root/custom-ROM variant is technically similar but incompatible with the current no-ADB/no-sideload customer model and is not a distributable product plan.

## 12. Goal/privilege truth table

| Capability | Normal Play-installed OAL | OEM/platform/root |
|---|---:|---:|
| Own full-screen Compose dashboard | Yes | Yes |
| Launch official native Maps/Spotify | Yes, separate task | Yes |
| Embed official native app Activity inside OAL layout | No supported app API | Yes, with system task/window integration |
| Keep OAL chrome over arbitrary foreground apps | Not reliably/safely | Yes, via SystemUI/window policy |
| Hide bars for another app | No; CarSystemUI reimposes policy | Yes; secure/global policy |
| Read standard user-grantable VHAL properties | Partial | Yes |
| Write HVAC/body/ADAS/vendor VHAL | No | Yes, if OEM grants |
| Publish own nav metadata via Templates Host | Yes | Yes |
| Read native Maps' private turn state | No | Possible only through OEM/partner interfaces |
| Draw arbitrary UI on GM instrument cluster | No | Yes, with cluster/display privilege |
| Control vehicle HFP/SCO endpoint | No | Yes |
| Render OAL-owned remote video surface | Yes | Yes |
| Capture/control arbitrary official phone apps automatically | Severely constrained | Possible with privileged/device-owner integration |

## 13. Migration/component matrix

| Component | Exact current paths | AA coupling | Reuse class | Successor seam / action | Privilege note |
|---|---|---|---|---|---|
| Main shell | `app/src/main/java/com/openautolink/app/MainActivity.kt`; `app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt` | Medium | Adapt | Rename projection screen to dashboard/surface host; preserve overlays and surface lifetime | Can host only OAL-owned surfaces/tasks |
| Settings/preferences | `app/src/main/java/com/openautolink/app/ui/settings/`; `app/src/main/java/com/openautolink/app/data/AppPreferences.kt` | Medium | Adapt | Split backend-neutral settings from AA SDR/WPP settings | Normal app |
| Video decoder | `app/src/main/java/com/openautolink/app/video/` | Medium-high in policy, low in codec primitives | Adapt | Introduce stream policy; keep codec/NAL/stats primitives | Normal app can decode owned streams |
| Audio playback | `app/src/main/java/com/openautolink/app/audio/` | Medium | Adapt | Accept backend-neutral purpose frames; reconcile stale ring-buffer comments/tests | Normal AudioTrack; no HFP ownership |
| Microphone | `app/src/main/java/com/openautolink/app/audio/MicCaptureManager.kt` | Medium | Adapt | Backend-neutral mic source with explicit privacy/lifecycle | Runtime RECORD_AUDIO only |
| Touch | `app/src/main/java/com/openautolink/app/input/TouchForwarderImpl.kt`; `app/src/main/java/com/openautolink/app/input/TouchScaler.kt`; `app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt` | Low until serialization | Reuse/adapt | Emit backend-neutral pointer events | Only for owned surface; not other apps |
| Steering keys | `app/src/main/java/com/openautolink/app/input/SteeringWheelController.kt`; `app/src/main/java/com/openautolink/app/MainActivity.kt` | Medium | Adapt | Map physical keys to semantic actions, then backend adapters | Some OEM keys never reach app |
| Vehicle reads | `app/src/main/java/com/openautolink/app/input/VehicleDataForwarderImpl.kt`; `app/src/main/java/com/openautolink/app/input/IgnitionMonitor.kt` | Low | Reuse | Publish `VehicleContext` independently of backend | Read-only subset; writes privileged |
| GNSS/IMU | `app/src/main/java/com/openautolink/app/input/GnssForwarderImpl.kt`; `app/src/main/java/com/openautolink/app/input/ImuForwarder.kt` | Low | Reuse | Feed owned nav/remote backend as needed | Runtime location permission |
| Domain messages | `app/src/main/java/com/openautolink/app/transport/ControlMessage.kt` | High mixed model | Replace/split | Create `NavigationSnapshot`, `MediaSnapshot`, `CallSnapshot`, `VehicleContext`, `InputEvents` outside transport package | No privilege issue |
| Session orchestration | `app/src/main/java/com/openautolink/app/session/SessionManager.kt` | Very high | Refactor | Shrink into backend-neutral coordinator; move AA watchdog/SDR/WPP into `AaBackend` | Normal app |
| AA Kotlin bridge | `app/src/main/java/com/openautolink/app/transport/aasdk/` | Hard | Keep only optional AA plugin | Wrap behind `AaBackend`; remove from AA-independent build flavor if desired | No extra privilege, but Google AA dependency |
| Native AA stack | `app/src/main/cpp/`; `external/opencardev-aasdk` | Hard | Replace for successor; retain plugin | No native dependency for native-handoff/API backend | Build complexity only |
| AA protobuf | `app/src/main/proto/oal/` | Hard | Archive/plugin | Keep exact wire schemas only in AA backend; design OAL-owned protocol separately | Private protocol maintenance risk |
| Generic byte stream | `app/src/main/java/com/openautolink/app/transport/aasdk/AasdkTransportPipe.kt`; `app/src/main/cpp/jni_transport.cpp`; `app/src/main/cpp/jni_transport.h` | Low at stream layer | Reuse/rename | `DuplexBytePipe`; custom protocol may stay Kotlin-only | Normal sockets/USB |
| TCP discovery | `app/src/main/java/com/openautolink/app/transport/hotspot/TcpConnector.kt`; `app/src/main/java/com/openautolink/app/transport/PhoneDiscovery.kt`; `app/src/main/java/com/openautolink/app/transport/OalProtocol.kt` | Medium | Adapt | Keep identity/versioning; remove WPP/gearhead assumptions | Normal network permissions |
| WPP/BT startup | `app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt`; `app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtServer.kt`; `app/src/main/java/com/openautolink/app/transport/hotspot/WppTcpServer.kt` | Hard | Remove from AA-independent backend | Retain only for `AaBackend` | Cannot switch active BT devices; privileged APIs closed |
| USB | `app/src/main/java/com/openautolink/app/transport/usb/` | High AOA coupling | Partial | Keep pipe mechanics; replace AOA identity/handshake for any custom accessory protocol | Per-device USB permission prompt remains |
| Companion service | `companion/src/main/java/com/openautolink/companion/service/CompanionService.kt`; `companion/src/main/java/com/openautolink/companion/autostart/`; `companion/src/main/java/com/openautolink/companion/wifi/` | Medium | Adapt | Generic OAL companion lifecycle and discovery | Background/FGS restrictions remain |
| AA proxy/launch | `companion/src/main/java/com/openautolink/companion/connection/AaProxy.kt`; `companion/src/main/java/com/openautolink/companion/service/TcpAdvertiser.kt` | Hard | Replace | Custom protocol server/renderer; no gearhead intents | Official-app capture/control constraints |
| Diagnostics/log upload | `app/src/main/java/com/openautolink/app/diagnostics/`; `companion/src/main/java/com/openautolink/companion/diagnostics/` | Low | Reuse | Add backend identity and privacy redaction | Normal app |
| Navigation normalization | `app/src/main/java/com/openautolink/app/navigation/NavigationDisplayImpl.kt`; `app/src/main/java/com/openautolink/app/navigation/ManeuverMapper.kt`; `app/src/main/java/com/openautolink/app/transport/ControlMessage.kt` | Medium | Adapt | Backend-neutral nav snapshots | Data source is the hard part, not rendering |
| Cluster host | `app/src/main/java/com/openautolink/app/cluster/`; `app/src/main/AndroidManifest.xml`; `app/src/main/res/xml/automotive_app_desc.xml` | Low once nav normalized | Reuse | Consume any owned nav backend | Templates only; no arbitrary cluster drawing |
| MediaSession | `app/src/main/java/com/openautolink/app/media/OalMediaSessionManager.kt`; `app/src/main/java/com/openautolink/app/media/OalMediaBrowserService.kt` | Medium data-source coupling | Reuse | Publish successor media source; native-app control optional | Other apps' sessions may require user/system mediation |
| Build/distribution | `app/build.gradle.kts`; `companion/build.gradle.kts`; `.github/workflows/`; `scripts/linux/bundle-release.sh` | Medium native coupling | Adapt | Add product flavors/backends; keep Play AAB car delivery | Play does not grant system privileges |
| Tests | `app/src/test/java/`; `.github/workflows/ci.yml` | Mixed | Extend | Add fake backend contract tests, companion tests, current instrumentation, and optional native compile gate | Real-car acceptance still required |

## 14. Recommended migration sequence

### Phase 0 — lock the feasibility contract

Document and accept one of these product definitions before coding:

- **A:** “official native apps, separate full-screen handoff”; or
- **B:** “OAL-owned dashboard using public service APIs”; or
- **C:** pursue an OEM partner for true embedded official apps.

Do not describe A or B as “official apps inside our shell.”

### Phase 1 — create the backend seam without behavior change

1. Extract backend-neutral navigation/media/call/video/audio/input/vehicle models from `ControlMessage.kt`.
2. Define `OalContentBackend` and a fake backend.
3. Move current AasdkSession/JNI/WPP/watchdog wiring behind `AaBackend`.
4. Keep current AA behavior and release path unchanged.
5. Add contract tests proving the shell survives backend start/stop/reconnect and surface recreation.

This is the highest-leverage work: it preserves today's functioning product while making every successor experiment isolated and reversible.

### Phase 2 — ship the native-handoff dashboard

1. Convert `ProjectionScreen` into a dashboard with an optional backend surface region.
2. Add explicit intents/availability checks for installed Maps and Spotify.
3. Add VHAL/charging/diagnostic cards from existing vehicle data.
4. Let official apps run as their own tasks; provide an obvious return path.
5. Keep AA as an optional compatibility tile during transition.

This is achievable under current privileges and immediately removes AA as a requirement for users who accept task handoff.

### Phase 3 — add API-composed features selectively

1. Choose navigation/media providers only after licensing/API feasibility is confirmed.
2. Feed normalized navigation into the existing Templates Host cluster path.
3. Feed normalized media into the process-wide MediaSession.
4. Keep native-app launch as fallback for unsupported actions.

### Phase 4 — custom companion only for owned content

If phone-hosted content is still needed, design a new versioned OAL protocol around owned UI/video/audio and semantic input. Do not start by attempting arbitrary official-app mirroring. Reuse discovery, logging, `MediaCodecDecoder`, audio, and touch only after a narrow prototype proves capture, input, and distribution assumptions on the target phone.

### Phase 5 — OEM track, if available

Prepare a separate system integration proposal rather than contaminating the normal-app architecture with hidden API assumptions. Required deliverables should explicitly list platform signing, task embedding, CarSystemUI/system-bar policy, cluster, VHAL writes, HFP, SELinux, and update ownership.

## 15. Decision

For the current Play-installed target deployment, the recommended architecture is:

```text
OpenAutoLink Shell (normal Play AAOS app)
  ├─ Dashboard: VHAL cards, charging, diagnostics, shortcuts
  ├─ Native handoff: official AAOS Maps / Spotify tasks
  ├─ Optional API cards: normalized nav/media where licensed
  ├─ Templates Host: OAL-owned navigation metadata only
  └─ Backend slot:
       ├─ AaBackend (existing compatibility, transitional)
       ├─ ApiDashboardBackend
       └─ RemoteRendererBackend (owned content only, later)
```

This gives a credible AA-independent path without pretending Android's app sandbox can be bypassed. If the non-negotiable requirement is simultaneous official Maps/Spotify UI embedded inside an OAL-controlled layout, stop app-only implementation work and pursue OEM/system access; that requirement is outside the capability envelope of the current Play-installed package.
