# PhoneTV

A companion receiver app that lets your TV play videos cast from [PhoneTube](https://github.com/RoundSalmon4/PhoneTube).

PhoneTube on your phone pairs with PhoneTV over your local network and hands off playback to the big screen. No Chromecast, no Google account, and no Google Play Services required. The pair runs on plain TCP (WebSocket), so it works reliably on hardened Android builds like GrapheneOS where Chromecast discovery is blocked.

> **Note:** This app only works together with PhoneTube. It is not a standalone video app.

## How it works

| Piece | Device | Role |
|-------|--------|------|
| PhoneTube | Phone | Finds the playable stream URL and sends it to the TV |
| PhoneTV | Android TV / Fire TV | Plays the stream and reports playback state back |

The TV shows an address like `192.168.1.50 : 8484` on screen. You add that address to PhoneTube once and the two stay paired for future casts.

## Requirements

- An Android TV, Google TV, Fire TV, or Android TV box (Android 7.0 / API 24+)
- PhoneTube installed on your phone
- Both devices on the same network

## Installation

PhoneTV is not on the Google Play Store. Install it by sideloading the APK.

### Option 1: Downloader app (easiest)

1. Install the [Downloader app](https://www.aftvnews.com/downloader/) on your TV (from the Google Play Store or Amazon Appstore)
2. Open Downloader and enter the URL field at the top
3. Type the code **6977947** and press Go
4. The APK downloads and prompts to install
5. If prompted, allow "Install unknown apps" for Downloader in your TV settings

The code always points to the latest release, so it never needs to change. You can also share the short link [aftv.news/6977947](https://aftv.news/6977947) anywhere.

### Option 2: GitHub Releases

1. Open [Releases](https://github.com/RoundSalmon4/PhoneTV/releases/latest) on a device that can share the file
2. Download `phonetv.apk`
3. Install it on the TV (via Downloader, a USB drive, or a file manager)

### Verifying the APK

Verify the APK was signed with the correct certificate before installing:

```bash
# Linux/macOS
apksigner verify --print-certs phonetv.apk | grep SHA-256

# Windows (PowerShell)
apksigner verify --print-certs phonetv.apk | Select-String "SHA-256"
```

Expected SHA-256 certificate fingerprint:

```
38:0B:50:60:DE:B5:B5:0A:37:E6:DC:F0:BE:73:A9:EE:85:06:34:19:19:8B:7C:AF:21:90:D9:84:53:C3:04:79
```

## Getting started

### First-time pair

1. Open **PhoneTV** on your TV. The screen shows the app name, a status line, and the address to use, for example `192.168.1.50 : 8484`
2. On your phone, open **PhoneTube** and go to **Settings** -> **Cast (PhoneTV)** -> **Add Cast Device**
3. Enter the IP address shown on the TV (name is optional, the port is 8484 by default) and tap **Add**
4. The TV now remembers the pairing address for future casts

You can also pair from the player screen: tap the cast icon in the player controls, then **Add device**.

### Casting a video

1. Start playing any video in PhoneTube (YouTube, PeerTube, Streamable, or an IPTV channel)
2. Tap the **cast icon** in the player controls
3. Select your TV from the list (tap **Cast**)
4. The video resumes on the TV from roughly where you were. Your phone pauses as a remote
5. Use the phone controls to play, pause, or seek; the actions are mirrored to the TV
6. Tap the cast icon again and pick **Disconnect** to stop. Your phone resumes playback from the TV position

### TV controls

The TV also responds to its remote while casting:

| Remote button | Action |
|---|---|
| Center / OK | Play or pause |
| Left / Right | Seek back / forward 10 seconds |
| Back | Stop the cast and return to the pairing screen |

### Stopping a cast

A cast runs on the TV until one of these happens:

- Press **Back** on the TV remote (or use **Disconnect** from PhoneTube's cast menu) to stop it yourself
- **Force-stop** PhoneTube on the phone (Settings -> Apps -> PhoneTube -> Force stop), or let the OS kill it — the TV stops itself when the phone's connection closes

Note that simply **swiping PhoneTube away from recents does not stop the cast**: the app keeps running in the background and its connection to the TV stays open, so the TV keeps playing (and will immediately take over whenever you open a new video in PhoneTube). This is intentional and matches how other casting apps behave.

### Casting with a VPN (example: ProtonVPN)

Casting uses a direct connection to the TV on your local network, so the phone's VPN must be told to let local traffic bypass the tunnel. With ProtonVPN on Android:

1. In the phone's **VPN settings** (GrapheneOS / Android): turn **off "Block connections without VPN"** (VPN lockdown). This setting blocks all non-tunnel traffic at the OS level and would otherwise prevent the local cast connection, no matter how the VPN app is configured.
2. In the **ProtonVPN app**: Connection / Advanced -> enable local network access, and choose the **"direct connections"** option for it. This excludes your local network from the tunnel so the phone can reach the TV directly. Other VPN apps offer an equivalent "allow local network / send local network traffic" option.

With that combination the cast works while everything else still goes through the VPN tunnel.

## What happens on each source

PhoneTube resolves the best playable URL before sending it to the TV. All sources work with the same flow:

- YouTube uses the DASH or HLS manifest for adaptive quality
- IPTV uses the provider's HLS stream (HTTP providers are supported)
- PeerTube and Streamable use their HLS or direct file URLs

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

PhoneTV uses [AndroidX Media3 ExoPlayer](https://developer.android.com/media/media3) for playback.