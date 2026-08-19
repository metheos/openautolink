# WPP phone-hotspot feasibility on Android Auto 17.4

**Build analyzed:** `com.google.android.projection.gearhead` 17.4.663004-release (`versionCode=174663004`)

**Base APK SHA-256:** `28c0810a538001251f4707424216d1bbfc2628d1e522efb95eaac1c951e8e388`

**Method:** targeted decode of the WPP TCP configuration and Wi-Fi network-selection classes. The decisive control flow is summarized below so the conclusion does not depend on a local decompile tree.

**Verdict: INVALIDATED for the projecting phone hosting the hotspot.**

OpenAutoLink can reach the companion over a phone-hosted hotspot, but Android
Auto 17.4 will not start WPP projection over that topology. The blocker is not
IP reachability or the OAL proxy. Gearhead requires a station-mode Wi-Fi
`Network` matching the SSID/BSSID supplied by the head unit before it opens the
WPP-over-TCP endpoint. A phone's own SoftAP is not a station association and is
not returned as the requested network.

## Topology under consideration

```text
projecting phone
  ├─ hosts SoftAP / hotspot
  ├─ companion proxy listens on 127.0.0.1:<ephemeral-port>
  └─ Android Auto / gearhead
             ↑
             │ phone-hotspot Wi-Fi
             ↓
AAOS head unit wlan0 (client)
  └─ OAL car app dials companion on the phone
```

The OAL half of this topology is valid:

1. The head unit can reach the companion over the phone hotspot.
2. The car can ask the companion for its current local AA proxy port.
3. Bluetooth WPP can advertise `127.0.0.1:<proxy-port>` to gearhead.
4. Gearhead would connect to its own loopback; the companion would splice that
   socket to the car's outbound socket.

That is already how current WPP avoids the GM telematics AP's inbound firewall.
See:

- `app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtServer.kt`,
  `Endpoint.PhoneLoopback`
- `app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt`,
  per-handshake endpoint resolver
- `companion/src/main/java/com/openautolink/companion/service/TcpAdvertiser.kt`,
  `wpp=<port>` identity response
- `companion/src/main/java/com/openautolink/companion/connection/AaProxy.kt`,
  loopback-to-car splice

## The Android Auto gate

The AA 17.4 teardown shows that WPP-over-TCP still has a Wi-Fi configuration as a
hard dependency.

### 1. TCP configuration includes Wi-Fi configuration

The decompiled type `olt` renders itself as:

```text
WifiProjectionProtocolOnTcpConfiguration(
    wifiConfiguration=...,
    ipAddress=...,
    port=...
)
```

In the analyzed build this string and model occur in obfuscated class `olt`.

### 2. Missing Wi-Fi credentials are fatal even with the TCP manager

`osj.m30126N(...)` throws:

```text
No credentials available when connecting to the WiFi network despite TCP manager.
```

In the analyzed build this exception occurs in obfuscated class `osj`.

### 3. The network request is not a generic "any path to this IP" request

The modern network manager builds:

```java
new NetworkRequest.Builder()
    .addTransportType(TRANSPORT_WIFI)
    .removeCapability(NET_CAPABILITY_INTERNET)
    .setNetworkSpecifier(
        new WifiNetworkSpecifier.Builder()
            .setSsid(advertisedSsid)
            .setBssid(advertisedBssid)
            .setWpa2Passphrase(advertisedPassword)
            .build()
    )
    .build();
```

In the analyzed build this request is constructed by obfuscated class `opa` (method `m29884f`).

The WPP TCP manager waits for that Wi-Fi `Network`, then creates its socket using
the resulting network. The endpoint may be `127.0.0.1`, but network acquisition
still happens first.

### 4. Gearhead explicitly notices that the phone is hosting a hotspot

On Android 14+, the same manager checks `WifiManager.isWifiApEnabled()` and logs:

```text
Dual STA is not enabled: hotspot
```

It then continues with the SSID/BSSID `WifiNetworkSpecifier`; it does not switch
to the SoftAP network or bypass association.

In the analyzed build these checks occur in obfuscated class `opa` (methods `m29876p` and `m29884f`).

### 5. "Already connected" means station association only

The short circuit checks `WifiManager.getConnectionInfo()`, a valid network ID,
and equality with the advertised SSID before starting projection:

```text
already connected to desired network: %s, starting
```

A phone hosting a SoftAP is not associated to that SoftAP through its station
interface, so this condition does not represent phone-hotspot mode.

In the analyzed build this short circuit occurs in obfuscated class `osj`.

## Why the companion cannot patch over this

The companion can prepare both sockets, but it cannot make gearhead consume them.
AA 17.4 disabled the public wireless-start broadcast/intent path. The surviving
OEM path is WPP over Bluetooth, and gearhead owns its Wi-Fi `NetworkRequest`.
A normal companion app cannot:

- provide a SoftAP as a station-mode `Network`;
- manufacture a `Network` with `TRANSPORT_WIFI` capabilities;
- inject a socket into gearhead;
- bypass gearhead's SSID/BSSID validation; or
- call its non-exported/private startup internals.

A VPN/TUN does not help because it is not the requested Wi-Fi transport and still
does not satisfy the `WifiNetworkSpecifier`.

## Constrained case that can appear to work but is not a vehicle solution

If the phone simultaneously remains associated as a client to a third Wi-Fi
network (for example home Wi-Fi) while hosting its hotspot, OAL could advertise
that *station* network to satisfy gearhead while the car reaches the companion
through the hotspot. The loopback endpoint makes the two data paths independent.

This is not a realistic driving mode:

- it requires valid credentials for the other Wi-Fi network;
- it depends on STA+AP concurrency;
- WPP loses its required network as soon as the vehicle leaves that Wi-Fi; and
- it therefore fails precisely when the car is driven.

Do not productize this as Phone Hotspot mode.

## Viable wireless topologies

| Topology | WPP result | Notes |
|---|---|---|
| Car/telematics AP; phone joins | Works | Current default. Companion loopback avoids the AP's inbound firewall. |
| Shared third-party AP; car and phone both clients | Works | Proven architecture; requires AP credentials. |
| Projecting phone hosts AP; car joins | **Cannot work on stock AA 17.4** | Gearhead cannot acquire its own SoftAP as the required station Wi-Fi network. |
| Second phone/travel router hosts AP; projecting phone and car join | Works in principle | This is a shared third-party AP, not phone-hotspot mode on the projecting phone. |
| USB | Works | No WPP/Wi-Fi acquisition involved. |

## What would have to change

At least one external constraint must change:

1. Google adds a WPP mode that accepts an already-usable endpoint without a
   station Wi-Fi `NetworkRequest`;
2. gearhead is patched/privileged to accept its SoftAP network or skip Wi-Fi
   acquisition for loopback endpoints;
3. the head unit gains an AP that the projecting phone can join; or
4. a separate device supplies the shared AP.

None of these can be implemented by an ordinary OAL car or companion app today.

## Product consequence

Do not add a WPP `Phone Hotspot` submode or hide the failure behind empty Wi-Fi
fields. The existing `connectionMode=phone_hotspot` setting belongs to the legacy
OAL TCP transport; it must not be presented as a valid WPP topology. If the UI is
reworked, WPP should describe only:

- **Car AP** (normal current mode), and
- **Shared AP** (advanced, credentials required).
