# Devialet Expert Pro Remote (Android App)

A bare-bones Android app to control a Devialet Expert / Expert Pro over the
local network: volume, mute, power, and source selection. Built because the
official "Devialet Expert Remote" app doesn't support recent Android versions.

This talks to the amp directly using its local UDP control protocol
(ports 45454/45455) - the same one used by community projects like
`jprouty/devialet_expert` (Home Assistant integration) and
`gnulabis/devimote`. No cloud, no login, LAN only.

## What it does

- Manual IP entry for the amp (saved locally)
- Volume slider (-60 dB to 0 dB) + step buttons
- Mute / unmute toggle
- Power on/off toggle
- Source buttons, auto-populated once the amp's status broadcast is received
  (arrives ~once per second on port 45454)

## Finding your amp's IP

Check your router's DHCP client list / connected devices page, or the amp's
own network settings menu on its front display. Consider setting a DHCP
reservation for it so the IP doesn't change later.

## Building it

1. Install **Android Studio** (free, from developer.android.com) if you don't
   have it.
2. Open this folder (`DevialetRemote/`) as a project - "Open" not "Import",
   point it at the folder containing this README.
3. Let Gradle sync (first time will download the Android Gradle Plugin,
   Kotlin, and a couple of small AndroidX libraries - needs internet).
4. Either:
   - **Run directly on your phone:** enable Developer Options on your phone
     (Settings → About phone → tap "Build number" 7 times), enable USB
     debugging, plug the phone in via USB, select it as the target device in
     Android Studio, and hit the green Run ▶ button. This builds, installs,
     and launches it in one step.
   - **Build an APK to sideload manually:** Build → Build App Bundle(s) /
     APK(s) → Build APK(s). The APK lands in
     `app/build/outputs/apk/debug/app-debug.apk`. Copy it to your phone
     (USB, email to yourself, Google Drive, whatever's easiest), open it with
     a file manager, and tap to install. Android will ask to allow installs
     from that app - grant it just for that one source.

Neither path requires Google Play or any store listing.

## Adjusting it to taste

- **Volume range:** the slider currently covers -60 dB to 0 dB
  (`progressToDb`/`dbToProgress` in `MainActivity.kt`). Devialet's protocol
  supports up to +30 dB; `setVolumeDb`'s `maxDb` parameter in
  `DevialetController.kt` is a safety clamp, currently 0 dB.
- **Source list:** pulled live from the amp's status broadcast, so it'll
  automatically reflect whatever inputs you've named/enabled in the amp's own
  configuration menu.
- **Discovery:** this version requires typing the IP manually rather than
  auto-discovering. If you'd like auto-discovery added (listen for the first
  status broadcast and offer to use its sender IP), that's a small addition
  to `DevialetStatusListener`.

## Known limitations

- No authentication on the protocol itself (same as the official app) -
  anyone on your LAN could send it commands. Fine for a home network.
- Status updates land about once a second, so the UI isn't instantaneous if
  you change something from the amp's physical remote at the same time.
