# Lapit — Bluetooth tools that work with no signal

Made by Mark Dave. Android app (APK built by GitHub Actions).

| Screen | What it does |
|---|---|
| 📷 Be the Camera | This phone takes the photos (full size, saved to Gallery ▸ Pictures/Lapit). Shows a 4-digit code so only the phone you allow can control it. "Dark" mode turns the screen black while it keeps shooting. |
| 🎮 Remote Shutter | Live view from the camera phone, shutter, 3s/10s timer, flash (off/on/auto), front/back flip, zoom, volume-button shutter. A preview of each shot is saved on the remote too. |
| 💬 Crowd Chat | Group chat with no internet. Phones link to the phones near them and pass messages along (up to 6 hops), so messages reach people beyond Bluetooth range. Room code = your group; messages are encrypted with it, so strangers' phones relay but can't read. Quick replies + SOS. |
| 📡 Sonar Finder | Green radar of Bluetooth things around you (earbuds, watches, speakers, trackers, phones). Distance from signal strength; direction by turning slowly in a circle. Tap a dot to lock on: faster pings = closer, "Hotter/Colder" hints. Save things with a name (⭐). Turn on "Be findable" so friends with Lapit appear by callsign (🙋). |
| 🍃 Battery saver | Slower live view, camera phone screen goes dark, Bluetooth scans in bursts, chat stops searching once linked, slower radar animation. |

## Build it (first time)

1. On GitHub (account `gnotesamsung7-hue`) create a new **public** repo named **`lapit`**.
2. Upload everything in this folder, keeping the folders as they are.
   - The `.github/workflows/build.yml` file sometimes gets skipped by drag-and-drop because the folder starts with a dot. If it doesn't show up, use **Add file ▸ Create new file**, type `.github/workflows/build.yml` as the name, and paste the file's contents.
3. Go to the **Actions** tab. "Build Lapit APK" runs on every upload (or press **Run workflow**). Takes about 5–8 minutes.
4. When it's green, open **Releases** → download **Lapit.apk** on your phone and install it.
   - Once that's done, the app's "Share Lapit" QR code points to this file, so friends can scan and install.

If you use a different repo name, change `GITHUB_REPO` in `app/src/main/java/com/markdave/lapit/Core.kt`.

Every new build has a higher version number and the same signing key, so it installs **over** the old one (no uninstall needed).

## Notes and limits

- Needs Google Play Services (normal on most Android phones). Both phones need Lapit.
- First time on each screen, allow **Nearby devices**, **Location** and **Camera**. On Android 11 and older, Location must be ON for Bluetooth scanning.
- Range is roughly 10–30 m phone-to-phone, less through walls and crowds. Chat hops extend it.
- Sonar distance is an estimate. Bodies, pockets and walls make it jump. Direction works best holding the phone flat and turning slowly; your body blocks the signal, so it's strongest when you face the thing.
- Many earbuds and phones change their Bluetooth address over time for privacy, so a saved "My Thing" may show up as a new device later. Trackers, watches and speakers usually keep theirs.
- Chat and Sonar run while the screen is open. Background mode is a possible next step.
