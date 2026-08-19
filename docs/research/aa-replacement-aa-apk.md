# Android Auto 17.4 teardown: implications for an AA-independent OpenAutoLink

**Build analyzed:** `com.google.android.projection.gearhead` 17.4.663004-release (`versionCode=174663004`)

**Base APK SHA-256:** `28c0810a538001251f4707424216d1bbfc2628d1e522efb95eaac1c951e8e388`

**Current-version check:** `apkeep -l -d apk-pure` on 2026-08-09 still ended at 17.4.663004-release.

**Method:** a preserved signal inventory plus a fresh low-RAM decode. Apktool produced 27,854 smali files/resources under a 6 GB virtual-memory ceiling; JADX was restricted to 24 targeted classes. No full JADX retry was used. The decisive findings and artifact identity are summarized below so this document does not depend on an unpublished decode tree.

## Executive result

Android Auto on the phone is not merely the producer of one projected framebuffer. It is all of the following at once:

1. a privileged package/app discovery and validation authority;
2. a host for legacy Google car-projection services and Android for Cars App Library services;
3. a compositor that owns multiple app/system windows and map/dashboard/rail/notification/assistant regions;
4. a virtual/trusted-display manager;
5. the central policy engine for distraction limits, template permissions, notification access, audio focus, calls, input, lifecycle, and assistant behavior;
6. the AA protocol endpoint that converts the composed phone UI to the video/audio/input/sensor channels OAL already consumes.

A clean-room OAL replacement can reproduce the shell and host **apps designed to trust it**, but a normally signed phone app cannot transparently host official projected Google Maps. The current Maps service separately confirms that its presentation path accepts only Google-signed callers or callers with signature-level `CAPTURE_VIDEO_OUTPUT`.

## 1. The shipped package is a privileged multi-app host

The preserved 17.4 signal snapshot found:

- 1,136 feature flags;
- 87 services;
- 57 activities;
- 86 permissions;
- 26,074 Java files in the prior successful complete decode;
- 762 layouts.

The manifest/services include separate hosts for:

- `TemplateService`
- `TemplateNavigationService`
- `TemplatePartialImmersiveService`
- `TemplateFullScreenImmersiveService`
- `TemplateAuxiliaryDisplayService`
- `TemplateClusterService`
- `MediaService`
- `GhAppLauncherService`
- `TelecomService`
- `PhoneCarAppService`
- `MessagingCarAppService`
- `LauncherCarAppService`
- `CarLocalMediaBrowserService`
- rotary and touch input services
- projection lifecycle/recovery services

The APK's `automotive_app_desc.xml` declares these automotive roles:

```xml
<automotiveApp>
    <uses name="service" />
    <uses name="projection" />
    <uses name="media" />
    <uses name="template" />
</automotiveApp>
```

This is a host runtime, not a single “Maps mirror.”

## 2. App discovery is package- and certificate-controlled

The targeted caller-validator class (`jjt` in this obfuscated build) does the following:

1. gets `Binder.getCallingUid()`;
2. enumerates every package for that UID;
3. checks whether each package is Google-signed;
4. checks whether the package is in an allowed-package set;
5. accepts only when **both** are true;
6. caches accepted UIDs.

The surviving log text is explicit:

```text
Package %s for uid %d: isGoogleSigned = %b, isPackageAllowed = %b
Call from UID %d is not allowed.
```

The semantic snapshot also contains:

- `AppValidation__allowed_3p_installers`
- `AppValidation__allowed_package_list`
- `AppValidation__blocked_packages_by_installer`
- `AppValidation__dhu_bypass_validation`
- `AppValidation__should_bypass_validation`

**Implication:** implementing Binder protocols is necessary but not sufficient. The host and/or app caller identity is a first-class security boundary. An OAL package cannot become Google-signed, and spoofing package/signature identity is not a distributable architecture.

**Confidence:** high; current Java control flow and stable flags agree.

## 3. Official Maps is a special, private projected service

The targeted startup-map class (`ima` in this obfuscated build) hardcodes the official Maps primary component:

```text
com.google.android.apps.maps/
  com.google.android.apps.gmm.car.GmmCarProjectionService
```

It places that service beside gearhead-owned components for launcher, media, telecom, templates, and assistant in the cold/warm/hot startup maps. Maps is therefore not “just another generic app Activity” inside AA.

The service gate was separately reproduced in Google Maps 26.32.06.958047303 (`versionCode=1068717991`; XAPK SHA-256 `67b4f29201baa43aa04061d952ee6c3b3ecd5d1b92ab82bdece1c00b1cacbec3`; base APK SHA-256 `3d40ac68458ae0c573ce4d99e5082159d3d5369feb091b11700ba8064e535e0a`). The targeted inspection covered the manifest, projection-client gate, Google certificate verifier, and `ICarProjection` Binder implementation.

Maps exports `GmmCarProjectionService`, and it returns an `ICarProjection` Binder. However, presentation calls enumerate caller packages and require either:

- a positive GMS `GoogleCertificatesLookupQuery` result; or
- `android.permission.CAPTURE_VIDEO_OUTPUT`.

Otherwise Maps throws:

```text
projection client manager does not have permission:
android.permission.CAPTURE_VIDEO_OUTPUT pid:<...> uid:<...>
```

`CAPTURE_VIDEO_OUTPUT` is not available to a normal Play-installed companion. This closes the apparent loophole created by the exported service.

**Conclusion:** binding is possible; controlling/rendering the official projected Maps presentation is not possible for normally signed OAL on a stock phone.

**Confidence:** very high; reproduced in the newest advertised Maps binary, not inferred from an old build.

## 4. Car App Library hosting is real but does not unlock arbitrary official apps

The 17.4 APK contains the Android for Cars App Library Binder/model stack and enforces template permissions. The targeted permission-check class (`ghd` in this obfuscated build) checks:

- `androidx.car.app.MAP_TEMPLATES`
- `androidx.car.app.NAVIGATION_TEMPLATES`
- `androidx.car.app.MEDIA_TEMPLATES`
- location permissions for map templates

It handles templates including:

- `MapWithContentTemplate`
- `PlaceListMapTemplate`
- `PlaceListNavigationTemplate`
- `RoutePreviewNavigationTemplate`
- `GridTemplate`
- `ListTemplate`
- `PaneTemplate`
- `TabTemplate`
- `SignInTemplate`

The feature surface is extensive: 53 current `CarAppLibrary__*` flags cover package exceptions, refresh throttling, speedbumps, map-template input, theming, list limits, media style, and navigation behavior.

A clean-room host can theoretically implement the public Binder/template model and host:

- OAL-owned apps;
- sample apps configured with `ALLOW_ALL_HOSTS_VALIDATOR`;
- apps whose `HostValidator` explicitly includes OAL's package and signing digest.

It cannot assume that arbitrary Play apps will trust an unknown host. Car App Library's own host validation accepts a caller only when one of these is true:

- app allowlists the host package + signing digest;
- caller is system UID;
- caller holds `android.car.permission.TEMPLATE_RENDERER`;
- app deliberately allows all hosts.

OAL cannot obtain `TEMPLATE_RENDERER` as a normal phone or car app.

**Conclusion:** a clean-room CAL host is a credible ecosystem experiment, not a route to official Maps parity.

**Confidence:** high for the protocol/validator; app-by-app compatibility requires runtime tests.

## 5. The shell is a compositor of named regions and windows

The decoded `ProjectionRootActivity` creates a root `FrameLayout`, consumes negotiated usable width/height/margins/pixel-aspect geometry, and maintains a map of projected windows. Its diagnostics enumerate each window's:

- ID/name;
- bounds;
- Z position;
- composition order;
- alpha;
- attached package.

The current `sys_ui_cielo_layout_narrow_portrait.xml` contains distinct regions for:

- `map` and `map_compat`
- `dashboard` and `cielo_dashboard`
- `activity` and `activity_compat`
- immersive and immersive toolbar
- notification center and heads-up notification
- demand surface
- assistant partial/fullscreen surfaces
- rail and demand rail
- map IME and general IME
- integrated overlay
- fullscreen and rounded-corner mask

The map is full-parent in the Cielo narrow-portrait layout, with dashboard/rail/activity regions layered above or constrained around it. This is a scene compositor, not a monolithic app screen.

### New-layout evidence

The current resource/flag set includes:

- `SystemUi__narrow_portrait_enabled_kill_switch`
- `SystemUi__wide_portrait_as_widescreen_enabled_kill_switch`
- `SystemUi__hero_large_canonical_layout_enabled_kill_switch`
- `SystemUi__vertical_rail_widget_enabled`
- `SystemUi__use_compose_rail`
- `CieloFeature__default_widgets_config`
- `CieloFeature__earth_enabled`
- `CieloFeature__earth_mini_dashboard_card_enabled`
- `CieloFeature__earth_dashboard_widget_combo_enabled`
- `CieloFeature__earth_tilt`
- `ApolloFeature__expressive_enabled`

Preserved layouts include narrow portrait, hero SOIP, projected-app, map-template, and media-playback structures.

**What OAL may safely reproduce:** geometry classes, responsive behavior, rail/dashboard/widget concepts, drive-side adaptation, window insets, focus model, and original visual design inspired by observed behavior.

**What OAL should not copy:** Google's source, exact assets, logos, strings, or a pixel-identical branded trade dress. Use clean-room Compose code and OAL design tokens.

## 6. Virtual-display and protected-output dependence

The targeted virtual-display class (`rab` in this obfuscated build) creates or attaches a `VirtualDisplay`, owns its `Surface`, swaps surfaces, and fails hard on `SecurityException`.

The gearhead manifest declares privileges unavailable to OAL, including:

- `android.permission.CREATE_VIRTUAL_DEVICE`
- `android.permission.ADD_TRUSTED_DISPLAY`
- `android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY`
- `android.permission.CAPTURE_SECURE_VIDEO_OUTPUT`
- `android.permission.ACTIVITY_EMBEDDING`
- `android.permission.QUERY_ALL_PACKAGES`
- `android.permission.SYSTEM_ALERT_WINDOW`
- automotive projection-control permissions

Projection-window flags include secure-video detection/capture behavior.

**Implication:** OAL can create ordinary virtual displays/surfaces that it owns, but it cannot reproduce gearhead's trusted/always-unlocked/secure-output environment as a stock app. This affects arbitrary Activity hosting, protected content, lock state, and secure surfaces.

## 7. Media, Spotify, calls, notifications, and assistant are separate host subsystems

AA does not obtain all app content through the AA wire protocol. It hosts local phone services and then projects the composed result.

### Spotify/media

The current Spotify 9.1.72.1891 targeted teardown shows:

- enabled exported `SpotifyMediaBrowserService`;
- disabled Car App Library `AndroidAutoService`;
- enabled exported `AppProtocolRemoteService`.

Spotify's MediaBrowser handler validates caller package, UID, signing certificate, and service identity against a baked list. Android Auto/gearhead is listed; OAL is not. Therefore OAL cannot simply connect as a generic media browser and inherit the AA catalog.

Spotify App Remote is the viable supported seam: register OAL's app ID and signing fingerprint, obtain semantic playback/catalog state and controls, and render OAL's own UI. Audio should remain on the native Bluetooth/media route unless a separately licensed/capturable stream exists.

### Calls

AA owns call presentation/control services, but actual voice audio can remain on the vehicle's native HFP/SCO endpoint. An AA-free OAL shell should preserve that separation and not plan to capture telephony PCM.

### Notifications

Gearhead owns notification listener/filter/action services. A replacement needs its own user-granted `NotificationListenerService`, safety filtering, action allowlist, privacy behavior, and Play-policy review.

### Assistant

Gearhead integrates Google Assistant/Gemini through private services and callbacks. OAL cannot assume access to that stack. A successor needs either public assistant intents, `SpeechRecognizer`, or an OAL-owned voice-command system.

## 8. Protocol channels remain useful only as an optional AA backend

OAL already speaks the AA wire protocol and should retain that working backend during migration. The AA protocol delivers:

- composed encoded video;
- purpose-separated audio;
- microphone requests;
- input/touch;
- sensor/GNSS/VHAL data;
- navigation status;
- media playback status;
- phone status;
- focus/lifecycle/ping/ByeBye.

Removing gearhead removes the producer of those streams. A successor must define new OAL-owned semantic or media channels rather than pretending the existing AA protobufs are generic.

Recommended split:

```text
OalContentBackend
  ├─ AaBackend               current AASDK/JNI/WPP path
  ├─ NativeHandoffBackend    launch installed AAOS apps as separate tasks
  ├─ ApiDashboardBackend     public nav/media/notification integrations
  └─ RemoteRendererBackend   OAL-owned phone renderer and protocol
```

## 9. Feasibility verdict by goal

| Goal | Stock phone + Play-installed OAL | Why |
|---|---:|---|
| Keep current AA projection | Proven | Existing OAL/AASDK backend |
| Build an OAL-owned responsive AA-like shell | Feasible | OAL owns Compose/layout/surfaces |
| Host OAL-owned CAL apps | Feasible | App can trust OAL host |
| Host arbitrary third-party CAL apps | Conditional | Each app's HostValidator decides |
| Show official Spotify semantics/controls in OAL UI | Plausible | Supported App Remote route, registration required |
| Embed official Spotify Activity UI | Not supported | Cross-app Activity/task embedding privilege wall |
| Bind official projected Maps service | Yes | Service exported |
| Render/control official projected Maps | No | Google certificate or `CAPTURE_VIDEO_OUTPUT` required |
| Mirror arbitrary official apps | Demo-only at best | consent, secure surface, input, audio, policy limits |
| Recreate gearhead's trusted display environment | No | signature/system privileges |
| Do all of the above with OEM/platform signing | Technically plausible | OEM can grant task/display/SystemUI privileges |

## 10. What should be built

The app-only product should be framed honestly as:

> **An OAL-owned automotive shell with native-app handoff, supported semantic integrations, optional OAL-owned remote rendering, and the existing Android Auto backend retained until AA-free parity is proven.**

It should not be framed as “official Maps and Spotify embedded inside OAL,” because current binaries prove that requirement crosses certificate and platform privilege boundaries.

The practical next step is the backend split above: preserve AA as a compatibility backend, validate native handoff and supported API integrations independently, and stop any experiment when its certificate, permission, policy, or licensing kill gate is reached.
