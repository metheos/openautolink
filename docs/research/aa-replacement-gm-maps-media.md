# GM/AAOS, Google Maps, and Spotify teardown findings for an AA-independent OAL

**Vehicle corpus:** GM Android 12 / SDK 32, build family `VCUSR5-163/239`

**Load-bearing GM/AAOS artifacts:**

| Package | Version | SHA-256 |
|---|---|---|
| `com.google.android.apps.automotive.templates.host` | `1.007.893018827.00-arm64-v8a` (`versionCode=18034`) | `0da77a8b895494839cb636c05f0557d15deb4b69d54c122c271f6d6be2fa31e8` |
| `com.gm.homescreen` | `12` (`versionCode=32`) | `4845b12e4c0e8eb0dc332ae8ff5055cc5952034e95d5cb3709f2a1c79477ec4b` |
| `com.android.systemui` | `12` (`versionCode=32`) | `61e1fe5cc6641e4fe4dc708aac9084eecab541ce7002982560a68357baee0b21` |
| `com.gm.hmi.androidauto` | `1.0` (`versionCode=1`) | `d53e783bef22423c2160a216bd7cefa1b8cf15ca6106137197af4d3a0021401c` |

**Phone Maps refreshed:** 26.32.06.958047303 (`versionCode=1068717991`; XAPK SHA-256 `67b4f29201baa43aa04061d952ee6c3b3ecd5d1b92ab82bdece1c00b1cacbec3`; base APK SHA-256 `3d40ac68458ae0c573ce4d99e5082159d3d5369feb091b11700ba8064e535e0a`)

**Phone Spotify refreshed:** 9.1.72.1891 (`versionCode=144716725`; XAPK SHA-256 `db0cee9975c9599535dd3b5520e0e69e42284e12b9f7049d60e80e8f74db27dd`; base APK SHA-256 `946aa2b57107f1c9fd387512b8c80951a5f8af9843d86107babb635fcf67cc91`)

**Method:** targeted inspection of the identified GM/AAOS packages, current phone
APK manifests, the current Maps presentation gate, and selected Spotify classes.
A legacy in-car Maps Java corpus was used only as contextual corroboration; its
original APK identity was not retained, so no load-bearing feasibility conclusion
rests solely on it. The conclusions below summarize the decisive evidence and do
not require access to local decompile output. Heavy decompilation was avoided.

## Executive conclusion

There are three distinct things called “Maps in the car,” and they cannot be interchanged:

1. **phone Maps projected through Android Auto** — private `GmmCarProjectionService` hosted by gearhead;
2. **native AAOS Maps** — a separate app installed and run by the head unit;
3. **a map rendered by OAL through a public SDK/provider** — OAL-owned UI/content, not the official Maps app.

Under OAL's current Play-only, non-platform-signed deployment:

- native AAOS Maps/Spotify can be launched as separate tasks if installed and visible;
- OAL cannot embed those native tasks inside its own Compose layout;
- OAL cannot control the official phone Maps projected surface because Maps requires a Google-signed caller or `CAPTURE_VIDEO_OUTPUT`;
- OAL cannot use Spotify's direct MediaBrowser catalog unless Spotify has baked OAL's package/certificate into its caller allowlist;
- OAL can plausibly use supported Spotify App Remote/App Protocol with a registered app ID/signing fingerprint and render its own media UI;
- true official-app embedding is an OEM/platform integration project.

## 1. GM's architecture separates privileged host, app, and projection roles

### Google Automotive Templates Host

The analyzed Templates Host APK declares:

- `android.car.permission.TEMPLATE_RENDERER`
- `android.permission.QUERY_ALL_PACKAGES`
- `android.car.permission.CAR_NAVIGATION_MANAGER`
- `android.car.permission.CAR_DISPLAY_IN_CLUSTER`
- `android.permission.CONTROL_INCALL_EXPERIENCE`
- automotive/templates-host system features

Its `RendererService` is the system host that binds app `CarAppService`s, receives their structured templates, validates template/API rules, and renders host-owned UI.

Its `SurfaceControlViewHostController` creates a `SurfaceControlViewHost`, sets host-owned views, and returns a `SurfacePackage` to `CarAppActivity`. This does **not** mean arbitrary applications can borrow Templates Host to embed another app's Activity. The host renders approved template models and app-owned map surfaces through its protocol.

### App-side HostValidator

`.../androidx/car/app/validation/HostValidator.java` accepts a host only when:

- host package + signing digest is in the app's allowlist;
- caller is system UID;
- caller has `android.car.permission.TEMPLATE_RENDERER`; or
- the app deliberately uses `ALLOW_ALL_HOSTS_VALIDATOR`.

OAL's current cluster service uses `ALLOW_ALL_HOSTS_VALIDATOR`, which is why Google's privileged Templates Host can bind to OAL. The reverse does not follow: OAL does not gain Templates Host's permissions and cannot force other apps to trust OAL.

### GM Android Auto HMI

`com.gm.hmi.androidauto` holds privileges such as:

- `android.permission.INTERNAL_SYSTEM_WINDOW`
- `android.permission.SYSTEM_ALERT_WINDOW`
- `android.permission.MEDIA_CONTENT_CONTROL`
- cross-user permissions
- GM projection-info permissions

This is the OEM presentation layer, not a model a normal Play app can duplicate.

## 2. Native task embedding is a system privilege

The GM homescreen corpus includes Android WM Shell `TaskView` and task-organizer infrastructure. The enforcement path calls:

```text
android.permission.MANAGE_ACTIVITY_TASKS
```

before controller operations. The system launcher/WM Shell can therefore host or organize other apps' tasks inside surfaces. OAL cannot obtain this permission from Play.

This produces a hard distinction:

| Operation | Normal OAL | GM/SystemUI/OEM host |
|---|---:|---:|
| `startActivity()` native Maps/Spotify | Yes | Yes |
| Show native app as separate foreground task | Yes | Yes |
| Embed native app task into an OAL panel | No | Yes, with TaskView/task organizer |
| Keep OAL chrome over arbitrary app | Not reliably/safely | Yes |
| Reparent/crop other app surfaces | No | Yes |

Activity Embedding's public rules do not provide a bypass: cross-app embedding depends on the target app opting in and platform rules; it is not a general-purpose task host.

## 3. CarSystemUI controls other apps' full-screen policy

The GM SystemUI teardown shows per-display bar visibility is reimposed on every focus change using `BarControlPolicy`. The controlling setting is:

```text
android.car.SYSTEM_BAR_VISIBILITY_OVERRIDE
```

A package not listed is forced back to the OEM bar policy. OAL can hide bars for its own Activity because it owns its theme/window. It cannot force native Maps to become OAL-style immersive.

Changing another package's policy requires shell/system access. That is unavailable under the stated no-ADB/no-sideload, Play-installed model.

## 4. Current phone Maps: exported Binder, closed presentation gate

The targeted inspection of the Maps artifact identified above covered its manifest and the three classes implementing the projection-client gate, Google certificate verification, and `ICarProjection` Binder. The current manifest still exports:

- `GmmCarProjectionService`
- limited/secondary/auxiliary projected services
- `CarNavigationProviderService`

It also exports `GhostActivity` only behind:

```text
com.google.android.projection.gearhead.permission.START_PROJECTED_ACTIVITY
```

The main projected service returns `com.google.android.gms.car.ICarProjection`. An unknown app can bind, but the presentation path checks each package for the Binder caller UID using Google's certificate verifier. If no package passes, it requires:

```text
android.permission.CAPTURE_VIDEO_OUTPUT
```

and otherwise throws `SecurityException`.

The verifier calls GMS `GoogleCertificatesLookupQuery`/`GoogleCertificatesLookupResponse`; this is not a configurable OAL allowlist.

**Result:** a clean-room OAL host can learn the Binder shape but cannot use official projected Maps on a stock phone. Package-name spoofing does not solve certificate verification and is not a legitimate product plan.

## 5. Native AAOS Maps is a separate product

A legacy in-car Maps Java corpus—retained without its original APK identity—shows
Android for Cars App Library models plus EV-energy/navigation logic of the same
kind used by OAL's battery/range integration. Treat that corpus as contextual
corroboration only. The load-bearing product boundary here comes from the
identified GM homescreen/SystemUI artifacts: native apps run as separate tasks,
while embedding/reparenting another app requires system task-organizer authority.

Native AAOS Maps can be:

- launched by intent as an installed car app;
- hosted by the system Templates Host where its own declared services/models permit;
- integrated with GM's privileged navigation/cluster services.

A normal OAL app cannot:

- inject itself as Maps' renderer host;
- read Maps' private active-route state;
- reparent Maps' Activity into OAL;
- force Maps' system bars/layout;
- copy Maps' map renderer or offline/account state.

The useful product route is **handoff**: an OAL dashboard launches native Maps and accepts that Maps owns the full foreground task until the user returns.

## 6. Current Spotify: supported semantic integration, not UI embedding

### Manifest surfaces

Spotify 9.1.72.1891 contains:

- enabled/exported `SpotifyMediaBrowserService`;
- disabled `SpotifyMediaLibraryService`;
- disabled Car App Library `AndroidAutoService`;
- enabled/exported `AppProtocolRemoteService`;
- exported main Activity and media-button receiver.

### Direct MediaBrowser path is allowlisted

`java/p_n2z0.java` validates package + UID before returning a browser root. `java/p_mb3.java` verifies a single signing certificate and matches package, certificate, and service identity against a baked list.

That list explicitly includes several Android Auto/gearhead certificate variants and named Google/OEM/partner integrations. It does not include `com.openautolink.companion`.

Therefore a generic OAL `MediaBrowserCompat` client should be expected to receive no root unless Spotify formally adds OAL.

### App Remote path remains viable

`AppProtocolRemoteService` is the supported extensibility surface. It identifies the caller package/signature and consumes an `appid` as part of session setup. This aligns with Spotify's registered App Remote integration model.

An OAL implementation should:

1. register the companion package/app ID and release signing fingerprint;
2. authenticate through the supported Spotify flow;
3. receive playback metadata/state and issue supported controls;
4. render an OAL-owned car UI;
5. leave audio on native Bluetooth/A2DP or another supported audio route.

This is **official Spotify integration**, but not the official Spotify Activity/UI embedded inside OAL.

## 7. Maps + Spotify architecture choices

### A. Native AAOS handoff dashboard — feasible now

```text
OAL dashboard
  ├─ Maps tile -> start native AAOS Maps task
  ├─ Spotify tile -> start native AAOS Spotify/media task
  ├─ vehicle/charging/diagnostic cards
  └─ return to OAL through launcher/back affordance
```

Benefits:

- uses official installed apps;
- no phone gearhead dependency for those apps;
- stays within normal app permissions.

Limits:

- no simultaneous official-app UI inside OAL chrome;
- native apps control their own layout and SystemUI policy;
- OAL cannot reliably read their private state.

### B. OAL-owned shell with public provider integrations — recommended long-term

```text
OAL car shell
  ├─ OAL navigation provider (MapLibre/OSM or licensed SDK)
  ├─ Spotify App Remote semantic connector
  ├─ NotificationListener connector
  ├─ native HFP call path
  ├─ VHAL/EV/cluster integration
  └─ optional phone remote renderer for OAL-owned surfaces
```

Benefits:

- OAL owns layout/features;
- Cielo-like responsive behavior is implementable cleanly;
- no dependency on gearhead after parity.

Limits:

- navigation UI is not official Google Maps;
- provider licensing/offline/traffic/routing must be solved;
- voice/notifications require separate permissions and policy work.

### C. OEM/platform integration — only route to true embedding

Requires some combination of:

- platform signing and priv-app placement;
- TaskView/task-organizer access;
- CarSystemUI policy changes;
- direct cluster/navigation permissions;
- package/host partnerships with Google and Spotify;
- SELinux and firmware integration.

This is technically plausible but incompatible with OAL's current Play-only distribution.

## 8. Layout/UI replication

AA 17.4's Cielo/hero resources prove the useful abstraction is a responsive scene with named regions, not a fixed screenshot. OAL should reproduce the behavior in original Compose code:

- geometry tiers: compact, canonical, semi-wide, wide, narrow portrait, wide portrait;
- drive-side-aware rail/dashboard placement;
- map, primary activity, dashboard/widgets, rail, notifications, assistant, IME, and overlays as independent regions;
- focus and distraction rules;
- original OAL design tokens/assets.

Do not copy Google assets, implementation code, logos, or a pixel-identical branded skin. A clean-room behavioral reimplementation also reduces update fragility.

## 9. Bounded experiments and kill criteria

| Experiment | Success signal | Kill signal |
|---|---|---|
| Native Maps/Spotify launch from OAL | resolves and starts on emulator/car | not installed/not distraction-optimized |
| Maps projected Binder probe | presentation call accepted as OAL | expected certificate/`CAPTURE_VIDEO_OUTPUT` SecurityException |
| Spotify MediaBrowser probe | browser root + token returned | package/certificate rejection |
| Spotify App Remote probe | auth + metadata + play/pause/skip | partner/API/policy restriction |
| OAL-owned CAL host | sample app accepts OAL host and renders template | host validator rejects; no app opt-in |
| Cross-app TaskView prototype on stock AAOS | task renders inside OAL | expected `MANAGE_ACTIVITY_TASKS` denial |
| OAL navigation provider spike | route + map + reroute + offline behavior acceptable | licensing, latency, or safety failure |

Do not continue implementation of a route after its kill signal. In particular, the latest Maps certificate gate already kills the stock-phone official projected Maps host route.

## 10. Legal/distribution constraints

Before public release:

- do not redistribute Google Maps or Spotify APKs;
- do not ship copied decompiled code/resources;
- use official SDKs/APIs under their current terms;
- register required app IDs/signing fingerprints;
- review Google Play automotive/distraction, notification, accessibility, background-service, and branding policies;
- get legal review before presenting a reverse-engineered private protocol as a supported app ecosystem.

The artifact identities and evidence summaries above are interoperability evidence only; no APK or decompiled output is a product dependency.

## Verdict

**AA-free OAL is possible. Official-app UI embedded inside OAL is not possible under the current privilege/distribution model.**

The viable product is an OAL-owned shell with:

- native official-app handoff;
- supported semantic integrations (notably Spotify App Remote);
- OAL-owned navigation/rendering;
- current Templates Host cluster integration;
- optional existing AA compatibility backend during migration.

The bounded implementation sequence is: prove native app handoff first, validate supported semantic integrations separately, retain AA compatibility during migration, and reserve true task embedding for an OEM/platform track.
