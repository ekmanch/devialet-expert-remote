# Devialet Expert Pro Remote (Android App)

A bare-bones Android app to control a Devialet Expert / Expert Pro over the
local network: volume, mute, power, and source selection. Built because the
official "Devialet Expert Remote" app doesn't support recent Android versions.

This talks to the amp directly using its local UDP control protocol
(ports 45454/45455) - the same one used by community projects like
`jprouty/devialet_expert` (Home Assistant integration) and
`gnulabis/devimote`. No cloud, no login, LAN only.

## What it does

- Auto-discovers amps on the LAN (tap the device card, pick from the list -
  no typing an IP) with a manual-entry fallback for edge cases
- Volume slider (-60 dB to -15 dB) + step buttons
- Mute / unmute toggle
- Power on/off toggle
- Source buttons, auto-populated once the amp's status broadcast is received
  (arrives ~once per second on port 45454)

## Choosing your amp

Tap the device card at the top of the app to open "Choose Amplifier" - it
lists every amp the app has heard broadcasting on the LAN in the last few
seconds (name + IP), refreshing live while the list is open. Pick one; it's
remembered for next time.

If your amp doesn't show up (different subnet/VLAN, or it just hasn't sent a
broadcast yet (they go out ~once per second), use "Enter IP Manually" in the
same dialog. You can find the IP via your router's DHCP client list /
connected devices page, or the amp's own network settings menu on its front
display. Consider setting a DHCP reservation for it so the IP doesn't change
later.

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

- **Volume range:** the slider currently covers -60 dB to -15 dB
  (`progressToDb`/`dbToProgress` in `MainActivity.kt`). Devialet's protocol
  supports up to +30 dB; `setVolumeDb`'s `maxDb` parameter in
  `DevialetController.kt` is a safety clamp, currently -15 dB.
- **Source list:** pulled live from the amp's status broadcast, so it'll
  automatically reflect whatever inputs you've named/enabled in the amp's own
  configuration menu.
- **Discovery staleness window:** an amp not heard from in 8 seconds drops
  out of the picker and the device card flips to "Not responding"
  (`ampStaleTimeoutMs` in `MainActivity.kt`). Broadcasts land about once a
  second, so this is a couple of missed packets' worth of slack - shorten it
  if you want the UI to react faster to an amp going offline, lengthen it if
  you're on a flaky network and it's dropping amps that are still there.

## Known limitation(s)

- No authentication on the protocol itself (same as the official app) -
  anyone on your LAN could send it commands. Fine for a home network.
