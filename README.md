# Moonbeam

A personal fork of [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android),
based on upstream **12.2**. Not affiliated with the Moonlight project.

| Area | What this fork adds |
|---|---|
| Identity | Package `com.limelight.moonbeam`, own icon set (adaptive + monochrome layer), semantic `major.minor.patch` versioning — currently **0.4.0** |
| Performance overlay | Three levels: off / numbers / HUD. The HUD shows FPS, frame time, 1% low, network latency, packet loss, decode time and a rolling frame-time curve |
| Session telemetry | One sample per second, POSTed to the host on port 8792 when a session ends, with a local JSON fallback |
| Adaptive bitrate | Client-side controller (drop fast, recover slowly) driving a runtime `GET /bitrate` channel on the host |
| Settings | Six categories — picture / audio / latency / input / network / debug — each with the common entries on top and an "Advanced" sub-screen |
| Migration | Imports V+ configuration backups, plain or PBKDF2/AES-GCM encrypted |

Build is unchanged from upstream:

```
./gradlew :app:assembleNonRootDebug
# app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
```

After touching the settings screen, run `python3 tools/check-settings-layout.py --show`.
It cross-checks the preference keys in `app/src/main/res/xml/preferences.xml` against the
`PREF_LAYOUT` table in `StreamSettings.java`; a key missing from the table would silently
drop that setting from the UI.

The adaptive-bitrate channel needs the matching Sunshine fork (`GET /bitrate` endpoint);
against stock Sunshine the controller's requests are received but do nothing, so the
overlay reports what was requested rather than claiming a change took effect.

Licensed under the GPLv3, same as upstream — see [LICENSE.txt](LICENSE.txt).

---

# Moonlight Android (upstream)

[![AppVeyor Build Status](https://ci.appveyor.com/api/projects/status/232a8tadrrn8jv0k/branch/master?svg=true)](https://ci.appveyor.com/project/cgutman/moonlight-android/branch/master)
[![Translation Status](https://hosted.weblate.org/widgets/moonlight/-/moonlight-android/svg-badge.svg)](https://hosted.weblate.org/projects/moonlight/moonlight-android/)

[Moonlight for Android](https://moonlight-stream.org) is an open source client for NVIDIA GameStream and [Sunshine](https://github.com/LizardByte/Sunshine).

Moonlight for Android will allow you to stream your full collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

Moonlight also has a [PC client](https://github.com/moonlight-stream/moonlight-qt) and [iOS/tvOS client](https://github.com/moonlight-stream/moonlight-ios).

You can follow development on our [Discord server](https://moonlight-stream.org/discord) and help translate Moonlight into your language on [Weblate](https://hosted.weblate.org/projects/moonlight/moonlight-android/).

## Downloads
* [Google Play Store](https://play.google.com/store/apps/details?id=com.limelight)
* [Amazon App Store](https://www.amazon.com/gp/product/B00JK4MFN2)
* [F-Droid](https://f-droid.org/packages/com.limelight)
* [APK](https://github.com/moonlight-stream/moonlight-android/releases)

## Building
* Install Android Studio and the Android NDK
* Run ‘git submodule update --init --recursive’ from within moonlight-android/
* In moonlight-android/, create a file called ‘local.properties’. Add an ‘ndk.dir=’ property to the local.properties file and set it equal to your NDK directory.
* Build the APK using Android Studio or gradle

## Authors

* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).
