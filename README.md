# Trillboards CTV Measurement SDK for Android

Passive sensing SDK for Android devices that monetize Trillboards programmatic
ads without on-device camera/microphone hardware — vending machines, ATMs,
EV charging stations, self-checkout kiosks, smart lockers, transit shelters.

Captures BLE / WiFi / mDNS / SSDP / HTTP-probe / native-sensor signals and
posts them to the Trillboards audience-resolution endpoint on a 30-second
cadence. All aggregation and enrichment happens server-side — your app doesn't
run any ML or write any client-side resolution code.

Apache 2.0. Public. Anonymous-readable via JitPack.

## Is this SDK for you?

If you operate Android devices **without cameras or microphones** and want
passive BLE / WiFi / IP / sensor capture without writing client-side
aggregation, yes. See `agent-core` for the camera/mic-equipped retail-screen
variant.

| Capability | This SDK | `agent-core` (full) |
|---|---|---|
| BLE / WiFi / mDNS / SSDP / HTTP-probe / native sensors | ✓ | ✓ |
| Face detection / Audio classification / Speech ASR / Pose / VLM | ✗ | ✓ |
| Min AGP | 4.1 | 8.0 |
| Min JDK toolchain | 8 | 17 |
| Min Android (`minSdk`) | 24 (Android 7.0) | 26 (Android 8.0) |
| Jetifier-compatible | ✓ | ✗ |
| Bundle size (AAR) | ~3 MB | ~180 MB |

## Install

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.trillboards:ctv-measurement-sdk:1.0.2")
}
```

## Permissions (`AndroidManifest.xml`)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />

<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="30" />

<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
```

## Initialize

Call once, typically from `Application.onCreate()`:

```kotlin
import com.trillboards.measurement.*

TrillboardsMeasurement.initialize(
    context = applicationContext,
    config = MeasurementConfig.Builder(
            apiKey = BuildConfig.TRILLBOARDS_PARTNER_API_KEY,   // same key as your ads integration
            deviceId = yourStableDeviceId,                       // your existing per-device fingerprint (IMEI / serial / UUID)
        )
        .scanIntervalMs(30_000L)
        .enabledSources(setOf(
            SignalSource.BLE, SignalSource.WIFI, SignalSource.MDNS,
            SignalSource.SSDP, SignalSource.HTTP_PROBE, SignalSource.NATIVE_SENSOR,
        ))
        .build(),
)
TrillboardsMeasurement.setConsentStatus(true)
TrillboardsMeasurement.startScheduledScans()
```

The SDK posts to `https://api.trillboards.com/openrtb/v1/heartbeat` on its
own schedule, signed with your existing partner API key. Trillboards' audience
resolver consumes the BLE / WiFi proximity and mDNS / SSDP / HTTP-probe device
fingerprints to derive venue intelligence, co-viewing inference, and
on-network device class — these are attached to your bid requests
automatically. You do not run any aggregation client-side.

## What's in this AAR

- `com.trillboards.measurement.TrillboardsMeasurement` — entry point
- `com.trillboards.measurement.MeasurementConfig` — init configuration
- `com.trillboards.measurement.MeasurementSnapshot` — cached scan results
- BLE / WiFi / mDNS / SSDP / HTTP-probe / native-sensor scanners (internal)
- Rust-backed mDNS discovery via UniFFI bindings (bundled `.so` for
  `arm64-v8a` + `armeabi-v7a`)

## Support

Issues: https://github.com/trillboards/ctv-measurement-sdk/issues
Email:  engineering@trillboards.com
