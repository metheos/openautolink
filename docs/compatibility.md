# Compatibility and evidence

OpenAutoLink compatibility depends on the exact head unit, AAOS build, permissions, networking, audio policy, physical display zoning, and Google Play installation path. Sharing a vehicle brand or AAOS version is not enough to call a vehicle supported.

## Evidence levels

| Level | Meaning |
|---|---|
| **Maintainer validated** | Repeated real-vehicle use with direct logs and visual confirmation |
| **Owner demonstrated working** | Independent owner supplied a visible successful result |
| **Owner reported working** | Independent owner described a successful install/session, but maintainer reproduction is unavailable |
| **Testing / unresolved** | Installation or connection attempted; material failures remain |
| **Experimental** | Architecture appears possible, but there is no successful real-vehicle evidence |
| **Unknown** | No credible test evidence |

A build, Play installation, `CONNECTED` status, or process log is not proof of successful projection. A positive result requires a rendered frame and continued video/input behavior; feature-specific support requires observing that feature.

## Vehicle matrix

| Vehicle | Platform information | Status | Evidence and limits |
|---|---|---|---|
| **2024 Chevrolet Blazer EV** | GM AAOS 12L; maintainer vehicle | **Maintainer validated** | Wireless WPP, sustained video, touch, audio, calls, EV data, cluster integration, ignition reconnect, USB, and H.265 startup have been exercised on real hardware. |
| **2025 Chevrolet Equinox EV** | GM AAOS 14 reported by testers | **Owner reported working** | [Owner reported installation and a working session](https://xdaforums.com/t/open-source-openautolink-wireless-android-auto-bridge-for-aaos-gm-evs.4785192/post-90708465). An initial audio stop cleared after reconnecting; broader feature parity is not established. |
| **2026 Chevrolet Equinox EV** | GM AAOS 14 reported by testers | **Testing / unresolved** | Testers reported a [startup/logo stall](https://xdaforums.com/t/open-source-openautolink-wireless-android-auto-bridge-for-aaos-gm-evs.4785192/post-90620330) and [USB freezes](https://xdaforums.com/t/open-source-openautolink-wireless-android-auto-bridge-for-aaos-gm-evs.4785192/post-90686188). Do not present this model year as supported until a sustained rendered session is demonstrated. |
| **2027 Chevrolet Bolt** | GM AAOS; exact build not captured in the public report | **Owner demonstrated working** | An owner posted [a running OpenAutoLink video](https://www.youtube.com/watch?v=xEYOxJroplQ) and an [installation photo/report]((https://www.chevybolt.org/threads/openautolink-the-cheapest-way-to-get-andriod-auto-on-the-27-bolt.63027/)(https://www.chevybolt.org/threads/openautolink-the-cheapest-way-to-get-andriod-auto-on-the-27-bolt.63027/)). Full feature coverage has not been audited. |
| **Other GM AAOS vehicles** | AAOS version, SoC, radio, and permissions vary | **Unknown** | Similar infotainment appearance does not prove equivalent networking, USB, VHAL, audio, or display behavior. |
| **Non-GM AAOS vehicles** | Vendor-specific | **Experimental** | The app may install, but Play policy, sideloading, Bluetooth, Wi-Fi topology, VHAL permissions, cluster APIs, and display zones differ. |

## Polestar and other AAOS testers wanted

If your Polestar or another vehicle runs Android Automotive OS but does not include Android Auto, the project wants to hear from you. Contact the maintainer through the [OpenAutoLink XDA thread](https://xdaforums.com/t/open-source-openautolink-wireless-android-auto-bridge-for-aaos-gm-evs.4785192/) or [Reddit](https://www.reddit.com/user/IPickedThisUserID/).

The maintainer can work with prospective testers on installation and evidence collection. If a Google Play invitation is appropriate, send the Google-account email used by the vehicle **privately**, never in a public comment. A place in the test group does not imply that an untested vehicle is compatible.

## Phone compatibility

The phone requires:

- Android with Android Auto;
- Bluetooth and Wi-Fi;
- the OpenAutoLink Companion for wireless operation;
- permission to run the companion in the background;
- a usable route to the vehicle access point.

Android Auto 17.4 and newer requires [Wireless WPP](wireless-wpp.md). Phone vendor Wi-Fi and battery-management policies can change connection behavior even when the vehicle is unchanged.

## Feature matrix

A vehicle-level successful stream does not automatically prove every integration:

| Feature | What proves it |
|---|---|
| Video | First frame visibly renders and frames continue |
| Touch | Screen input causes the intended phone-side Android Auto action |
| Audio | Media and guidance are current, audible, and do not accumulate delay |
| Calls | Projected answer/end controls work and both directions of voice audio are confirmed |
| EV data | Android Auto/Maps displays live vehicle battery or energy-model results |
| Cluster navigation | Visible turn guidance updates in the instrument cluster |
| Steering-wheel controls | Mapped button produces the correct current-session action |
| Reconnect | A new ignition/sleep-wake cycle resumes a sustained session without manual repair |
| USB | AOA negotiation completes and a sustained session renders after permission is granted |

## Reporting a new vehicle

Include:

- make, model, model year, market, and trim;
- AAOS version and available radio/head-unit information;
- Android Auto version;
- phone make, model, and Android version;
- OpenAutoLink car and companion versions;
- selected transport;
- whether a frame rendered and for how long;
- touch, audio, microphone/call, cluster, EV-data, and reconnect results separately;
- car and companion logs from the same attempt when possible.

Do not post Wi-Fi passwords, invitation tokens, Play credentials, or signing material.

## Current support language

Use these exact distinctions in public discussion:

- **Validated:** 2024 Blazer EV.
- **Demonstrated working:** 2027 Bolt.
- **Owner reported working:** 2025 Equinox EV.
- **Testing, not yet supported:** 2026 Equinox EV.
- **Experimental/unknown:** every untested vehicle.
