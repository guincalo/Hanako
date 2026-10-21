<img src=".github/assets/hanako.png" alt="" width="96" height="96" align="right">

# Hanako

An unofficial Telegram client for Android. Hanako is built on [Forkgram Classic](https://github.com/forkgram/forkgram-classic),
which keeps Telegram's classic interface from before the "Liquid Glass" redesign, and adds privacy,
anti-delete and quality-of-life features on top. It is not affiliated with Telegram, Forkgram or Mercurygram.

Most of Hanako's own code was written with AI coding agents and has had limited testing. Keep a backup
of anything you care about.

## Features

All Hanako options are off by default unless noted, and live in one settings screen, **Settings > Hanako**,
together with Forkgram's own options. Settings search finds them.

- **Ghost mode** (per account): no read receipts, typing, online status or story views; per-chat
  exceptions, a quick toggle in the chat list, silent sending, a story guard, and peeking at chats
  without marking them read.
- **Chat lock**: lock chats with biometrics, optionally hide them from the chat list and notifications.
- **Deleted and edited messages**: deleted messages stay in the chat, edits keep their history (with a
  word diff), media from deleted and view-once messages is kept and can be browsed. Secret chats are never saved.
- **Activity log**: a local log of when contacts were online and read your messages, built only from
  updates the app already receives.
- **Streamer mode**: pseudonyms, masked avatars and titles, hidden numbers, optional screen-capture block.
- **Message filters**: hide or collapse messages by regular expression, per chat or globally.
- **Chat export** in Telegram Desktop's HTML and JSON formats, with media types, date range and size limit.
- **Keep rules**: choose per chat or per person which deleted messages and media types are kept.
- **Themes**: Rin (dark, the theme from Hanako 1.3) and Hanako (light) as the default look, and a
  **Pitch black (OLED)** switch that turns any dark theme true black.
- **Other**: message shots, session backup, choice of app API ID, profile DC and registration date,
  transfer speed presets, confirmations before sending voice/stickers/GIFs or calling, hiding the
  "Send as" button.
- **From Forkgram**: QR login, hidden accounts, UnifiedPush notifications, Monet themes, voice-to-text,
  and Forkgram's chat and privacy tweaks.

Usage statistics are opt-in (off by default): daily feature counts and scrubbed crash reports, never
message content, names, numbers or IDs. You can view exactly what would be sent before it is sent.

### Changes from Hanako 1.x

Hanako 1.x was based on Mercurygram. The 2.x line moves to Forkgram Classic and drops: OpenPGP, the Tor
plugin, custom DNS-over-HTTPS, proxy auto-switch, disabling the proxy while a VPN is on, the extra
translation engines, on-device Whisper and Parakeet transcription, the plugin engine, and sending as
scheduled in ghost mode, the Smug language and Peek. Where Mercurygram and Forkgram both had a feature,
Forkgram's version is used. Hanako 1.x's in-app updater looks at Mercurygram's releases, so update to 2.x
by installing the APK yourself (it installs over 1.2 and later).

## Download

The current stable version is **2.04** ([release notes](CHANGELOG.md#204---2026-10-21)):
[Hanako-v2.04-arm64-v8a.apk](https://github.com/guincalo/Hanako/releases/latest). All APKs are on the
[Releases](https://github.com/guincalo/Hanako/releases) page (arm64-v8a only).
Development builds are marked as pre-releases. You can also track releases with Obtainium:
[stable](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%20%22moe.hanako.chan%22%2C%20%22url%22%3A%20%22https%3A%2F%2Fgithub.com%2Fguincalo%2FHanako%22%2C%20%22author%22%3A%20%22guincalo%22%2C%20%22name%22%3A%20%22Hanako%22%2C%20%22additionalSettings%22%3A%20%22%7B%5C%22apkFilterRegEx%5C%22%3A%20%5C%22%5EHanako-v%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%28%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%29%3F-arm64-v8a%5C%5C%5C%5C.apk%24%5C%22%7D%22%7D) or [stable and dev builds](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%20%22moe.hanako.chan%22%2C%20%22url%22%3A%20%22https%3A%2F%2Fgithub.com%2Fguincalo%2FHanako%22%2C%20%22author%22%3A%20%22guincalo%22%2C%20%22name%22%3A%20%22Hanako%20%28with%20dev%20builds%29%22%2C%20%22additionalSettings%22%3A%20%22%7B%5C%22includePrereleases%5C%22%3A%20true%2C%20%5C%22apkFilterRegEx%5C%22%3A%20%5C%22%5EHanako-v%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%28%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%29%3Fd%3F-arm64-v8a%5C%5C%5C%5C.apk%24%5C%22%7D%22%7D).

The package ID is `moe.hanako.chan`, and releases are signed with Hanako's own key (certificate SHA-256
`EE:38:A0:6C:C9:9F:C8:BB:9E:5B:5A:83:74:88:5C:E4:69:0A:89:D2:76:0B:8A:A4:7C:56:1B:4D:F9:64:65:60`), so a
2.x build installs as an update over Hanako 1.2 and later. Make a session backup first.

## Building

Linux only. See [BUILDING.md](BUILDING.md) for the toolchain and native libraries, and
[`.github/workflows/hanako-classic.yml`](.github/workflows/hanako-classic.yml) for the exact CI setup.
Use your own [API ID](https://core.telegram.org/api/obtaining_api_id) in `gradle.properties`, then:

```
./gradlew assembleAfatRelease
```

## Reporting issues

Use [Issues](https://github.com/guincalo/Hanako/issues). Include the Hanako version, Android version and
steps to reproduce. See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).

## Credits and license

- [Telegram for Android](https://github.com/DrKLO/Telegram) by Telegram FZ-LLC
- [Forkgram](https://github.com/forkgram/TelegramAndroid) and [Forkgram Classic](https://github.com/forkgram/forkgram-classic)
- [Mercurygram](https://github.com/Mercurygram/Mercurygram) by drizzt and contributors, which Hanako 1.x was
  forked from and whose features Hanako still carries
- [Telegram-FOSS](https://github.com/Telegram-FOSS-Team/Telegram-FOSS)
- The chat export bundles style sheets, scripts and icons from
  [Telegram Desktop](https://github.com/telegramdesktop/tdesktop) (GPLv3 with OpenSSL exception)
- Ideas for several features came from AyuGram, Nagram, NekoX, Octogram and FAgram

Hanako is free software under the [GNU General Public License v2.0](LICENSE), like Telegram for Android.
Telegram is a trademark of Telegram FZ-LLC.
