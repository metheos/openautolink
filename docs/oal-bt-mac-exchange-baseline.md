# OpenAutoLink — Bluetooth-MAC exchange in wireless Android Auto

This document records the OpenAutoLink (OAL) behavior implemented by the current
source tree. It is a baseline for comparing future phone-side Android Auto
changes, not a proposal for new behavior.

A phone-side audit that motivated this document found the flag
`WirelessProjectionInGearhead__enable_wireless_bt_mac_fix` disabled in the build
that was inspected. That is a **build-specific observation**, not a claim about
later Android Auto releases or rollout state.

---

## 0. TL;DR

OAL has three distinct Bluetooth/MAC-related paths that should not be conflated:

1. **AA session SDR:** the car app may put the **car/head unit's Bluetooth MAC**
   in
   `ServiceDiscoveryResponse.channels[BLUETOOTH].bluetooth_service.car_address`.
   This is sent car → phone inside the already-established Android Auto session.
2. **WPP bootstrap:** when the optional `wpp` transport is selected,
   `AaWirelessBtServer` publishes the Android Auto Wireless RFCOMM service and
   exchanges the projection endpoint and Wi-Fi configuration before TCP starts.
   Its protobuf payloads do **not** carry a separate car-MAC field. The accepted
   RFCOMM socket identifies the **phone's** Bluetooth address, which OAL uses to
   keep per-phone endpoint selection from crossing between phones.
3. **Companion autostart:** `AUTO_START_BT_MACS` is a local set of paired-device
   addresses used to start the companion after an ACL connection. Those values
   are not sent to the car or to Android Auto.

The SDR Bluetooth block is optional: an empty car MAC causes OAL to omit the
block. OAL's AA-session Bluetooth channel does not create an Android bond or
perform pairing. WPP instead assumes the phone is already paired to the head
unit and uses that existing Bluetooth relationship for its pre-session RFCOMM
exchange.

---

## 1. Car MAC in the AA Service Discovery Response

### 1.1 Source resolution — `SessionManager.startSession`

File:
`app/src/main/java/com/openautolink/app/session/SessionManager.kt`

`SessionManager.startSession` resolves `btMac` in this order:

1. **User override:** `AppPreferences.btMacOverride` (DataStore key
   `bt_mac_override`). `AppPreferences.setBtMacOverride` trims the value,
   uppercases it, and converts hyphens to colons.
2. **Secure setting:** `Settings.Secure["bluetooth_address"]`.
3. **Adapter API:** `BluetoothAdapter.getDefaultAdapter().address`.

At each applicable stage, an empty value, `02:00:00:00:00:00`, or `none`
(case-insensitive) is treated as unavailable. The all-zero-looking privacy value
is expected from `BluetoothAdapter.getAddress()` on many Android versions, and
some AAOS builds expose `None` for a missing property.

Only the user override is normalized. Values returned by the secure setting or
adapter are otherwise passed through as returned. OAL does not perform a general
hex/length validation before putting the value into the SDR.

The selected value is logged as `BT MAC for SDR: ...` (or `(none)`) and passed to
`AasdkSdrConfig(btMacAddress = btMac, ...)`.

Override entry points:

- `SettingsScreen` exposes the `btMacOverride` field and calls
  `SettingsViewModel.updateBtMacOverride`.
- `AppPreferences.setBtMacOverride` persists the normalized value; blank clears
  it.
- `SettingsReceiver` accepts the diagnostic key `bt_mac_override`.

Relevant files:

- `app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt`
- `app/src/main/java/com/openautolink/app/ui/settings/SettingsViewModel.kt`
- `app/src/main/java/com/openautolink/app/data/AppPreferences.kt`
- `app/src/main/java/com/openautolink/app/diagnostics/SettingsReceiver.kt`

### 1.2 Kotlin/JNI config contract

`AasdkSdrConfig` declares:

```kotlin
/** Bluetooth MAC address of the car (for BT pairing service). */
@JvmField val btMacAddress: String = "",
```

File:
`app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSdrConfig.kt`

`JniSession::start` reads `btMacAddress` into `sdrConfig_.btMac`. During pipeline
construction it creates `aasdk::channel::bluetooth::BluetoothService` only when
that string is non-empty.

Files and symbols:

- `app/src/main/cpp/jni_session.cpp` — `JniSession::start`
- `app/src/main/cpp/jni_session.h` — `JniSession::SdrConfig::btMac`

### 1.3 Wire representation — `JniSession::buildServiceDiscoveryResponse`

The only place this car MAC is written to the Android Auto application protocol
is the Bluetooth channel entry in the SDR:

```cpp
if (!sdrConfig_.btMac.empty()) {
    auto* svc = response.add_channels();
    svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::BLUETOOTH));
    auto* bs = svc->mutable_bluetooth_service();
    bs->set_car_address(sdrConfig_.btMac);
    bs->add_supported_pairing_methods(BLUETOOTH_PAIRING_PIN);
    bs->add_supported_pairing_methods(BLUETOOTH_PAIRING_NUMERIC_COMPARISON);
}
```

The enum names above are namespace-qualified in source. If the MAC is empty, OAL
adds no Bluetooth channel to the SDR.

`JniSession::onServiceDiscoveryRequest` builds and returns this SDR after the
version exchange and SSL setup. Therefore `car_address` is a car → phone
application-layer protobuf field inside an established Android Auto transport;
it is not itself an RFCOMM message, SDP property, or Bluetooth link-layer event.

File:
`app/src/main/cpp/jni_session.cpp`

### 1.4 AA-session Bluetooth handler

`JniBluetoothHandler`:

- accepts the channel-open request with `STATUS_SUCCESS`;
- logs `BluetoothPairingRequest` and resumes the receive loop without creating a
  bond or executing a pairing method;
- logs `BluetoothAuthenticationResult` and resumes the receive loop.

Thus OAL advertises pairing methods and a car address, but the AA-session channel
is not an implementation of Android Bluetooth pairing.

File and symbol:
`app/src/main/cpp/jni_channel_handlers.cpp` — `JniBluetoothHandler`

### 1.5 Separate HFP-presence advertisement

`HfpPresenceServer` publishes an RFCOMM service record on the Hands-Free Profile
UUID `0000111e-0000-1000-8000-00805f9b34fb`. It accepts and closes an inbound
connection but does not implement AT commands, SCO, or call audio.

This is a presence hint for phones that gate wireless-AA discovery on seeing an
HFP-capable device. It does not carry `car_address`. The broader WPP advertiser
also has a presence-only HFP listener for environments where the platform does
not already hold the Bluetooth link.

Files and symbols:

- `app/src/main/java/com/openautolink/app/transport/bluetooth/HfpPresenceServer.kt`
  — `HfpPresenceServer`
- `app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtServer.kt`
  — `AaWirelessBtServer.hfpPresenceLoop`

---

## 2. WPP Bluetooth bootstrap is a different exchange

The optional `wpp` transport follows the OEM-style pre-session direction: the
car publishes the Android Auto Wireless SDP UUID, the paired phone dials the car
over RFCOMM, and the car supplies enough information for the phone to open the
projection TCP path.

`AaWirelessBtServer.handleHandshake` exchanges:

1. `WifiVersionRequest`, including `WifiProjectionProtocolInfo { ip_address,
   port }`;
2. the phone's `WifiVersionResponse`;
3. `WifiStartRequest`, including the endpoint and `AccessPointInfo` (network
   identifier, key, BSSID, security mode, and supported channels);
4. the phone's `WifiInfoRequest`;
5. `WifiInfoResponse` with the network security fields.

No message in this implementation adds a separate car Bluetooth-MAC field. The
car's Bluetooth identity is the bonded device/service the phone dialed. OAL does
read `BluetoothSocket.remoteDevice.address`, which is the **phone's** address,
and passes it to `EndpointResolver.currentEndpoint(phoneBtAddress)` so concurrent
phones cannot receive each other's loopback endpoint.

Two endpoint shapes are implemented:

- `Endpoint.CarDirect`: the car listens and the phone opens TCP to the car.
- `Endpoint.PhoneLoopback`: Android Auto connects to a companion-owned loopback
  proxy on the same phone, while the car uses the companion-facing TCP path.

The selected endpoint and Wi-Fi details are independent of the later SDR
`bluetooth_service.car_address`. Once TCP is connected, native session setup and
the SDR path in §1 proceed normally.

Files and symbols:

- `app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtServer.kt`
  — `AaWirelessBtServer`, `handleHandshake`, `Endpoint`, `EndpointResolver`
- `app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt`
  — `startAdvertising` and its per-phone endpoint resolver
- `app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt`
  — `startWpp`, `dialCompanion`
- `app/src/main/java/com/openautolink/app/transport/hotspot/WppTcpServer.kt`
  — inbound TCP listener for `CarDirect`

---

## 3. Companion behavior outside WPP

In the companion-assisted TCP path, the companion can launch Android Auto toward
a local proxy by supplying loopback as the host and the proxy's local port.
`TcpAdvertiser.fireAaLaunchIntent` writes `127.0.0.1` to the host-address extras
and the proxy port to the corresponding port extras.

Car/companion discovery uses:

- TCP stream port `5277`;
- identity probe port `5278`;
- UDP discovery port `5279`;
- mDNS service type `_openautolink._tcp`.

That companion launch path does not add a car Bluetooth MAC. The companion's
`AUTO_START_BT_MACS` preference is only an ACL-connect autostart filter:
`AutoStartReceiver` compares the connected `BluetoothDevice.address` with the
locally stored set and starts the companion when it matches.

Files and symbols:

- `companion/src/main/java/com/openautolink/companion/service/TcpAdvertiser.kt`
  — `TcpAdvertiser.fireAaLaunchIntent` and port constants
- `companion/src/main/java/com/openautolink/companion/autostart/AutoStartReceiver.kt`
  — `AutoStartReceiver`
- `companion/src/main/java/com/openautolink/companion/MainActivity.kt`
  — `CompanionPrefs.AUTO_START_BT_MACS`
- `companion/src/main/java/com/openautolink/companion/ui/MainScreen.kt`
  — preference UI

---

## 4. Current assumptions and bounded observations

| Item | Current source behavior or observation |
|---|---|
| **Whose SDR MAC** | The car/head unit's Bluetooth MAC, not the phone's. |
| **Normalization** | User override is trimmed, uppercased, and hyphens become colons. Platform-returned values are not generally normalized or validated. |
| **Sentinels** | Empty, `02:00:00:00:00:00`, and `none` mean unavailable. |
| **Optional SDR block** | Empty MAC omits the entire AA Bluetooth service block. Source allows the rest of SDR/session setup to continue; runtime compatibility still depends on phone behavior. |
| **Pairing methods** | SDR advertises PIN and numeric comparison, but `JniBluetoothHandler` does not execute them. |
| **Call audio** | `car_address` does not route calls. HFP presence listeners do not implement an Audio Gateway. |
| **WPP phone identity** | The accepted RFCOMM socket supplies the phone Bluetooth address for per-phone endpoint ownership; this is not the SDR car MAC. |
| **Vehicle-specific evidence** | Comments in the WPP implementation describe behavior measured on a particular GM AAOS setup, including AP direction constraints and Bluetooth-link behavior. Treat those as observations from that setup, not universal properties of every AAOS vehicle or access point. |

---

## 5. Checklist for a future phone-side BT-MAC behavior change

For a fresh phone build and a clearly identified transport mode, compare:

1. Does the phone accept an SDR with no Bluetooth service block?
2. Does it reject malformed or non-canonical `car_address` values that OAL
   currently passes through?
3. Does it require `car_address` to match the Bluetooth device used for WPP or an
   existing bond?
4. Does it begin expecting an actual AA-channel pairing exchange rather than
   tolerating OAL's logging-only `JniBluetoothHandler` path?
5. Does HFP presence still affect admission, and does behavior differ between a
   real AAOS head unit and a non-automotive test device?
6. In WPP mode, does the pre-session RFCOMM exchange complete before TCP/SSL, and
   is the selected phone address correlated with the endpoint it receives?

Useful log anchors:

- `BT MAC for SDR:`
- `BT MAC override in use:`
- `Bluetooth channel open`
- `Bluetooth pairing request`
- `Listening on Android Auto Wireless UUID`
- `Phone dialled back on the AA Wireless UUID`
- `Handshake: sending WifiVersionRequest`
- `Handshake complete`
- `HFP presence`

---

## 6. Source index

| Concern | Current file / symbol |
|---|---|
| MAC resolution and SDR config assembly | `app/src/main/java/com/openautolink/app/session/SessionManager.kt` — `startSession` |
| Override preference and normalization | `app/src/main/java/com/openautolink/app/data/AppPreferences.kt` — `BT_MAC_OVERRIDE`, `btMacOverride`, `setBtMacOverride` |
| Override UI | `app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt` — `btMacOverride`; `SettingsViewModel.updateBtMacOverride` |
| Diagnostic override | `app/src/main/java/com/openautolink/app/diagnostics/SettingsReceiver.kt` — `bt_mac_override` branch |
| SDR config field | `app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSdrConfig.kt` — `btMacAddress` |
| JNI read, channel creation, SDR emission | `app/src/main/cpp/jni_session.cpp` — `JniSession::start`, `buildServiceDiscoveryResponse`, `onServiceDiscoveryRequest` |
| Native config storage | `app/src/main/cpp/jni_session.h` — `JniSession::SdrConfig::btMac` |
| AA-channel no-pairing behavior | `app/src/main/cpp/jni_channel_handlers.cpp` — `JniBluetoothHandler` |
| HFP presence | `app/src/main/java/com/openautolink/app/transport/bluetooth/HfpPresenceServer.kt` — `HfpPresenceServer` |
| WPP RFCOMM exchange | `app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtServer.kt` — `handleHandshake` |
| WPP endpoint ownership/configuration | `app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt` — `startAdvertising` |
| WPP TCP direction | `app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt` — `startWpp`, `dialCompanion` |
| Companion loopback launch | `companion/src/main/java/com/openautolink/companion/service/TcpAdvertiser.kt` — `fireAaLaunchIntent` |
| Companion local BT autostart filter | `companion/src/main/java/com/openautolink/companion/autostart/AutoStartReceiver.kt` — `AutoStartReceiver` |
