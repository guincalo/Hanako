<div align="center">

<img src=".github/assets/hanako.png" alt="Hanako icon" width="160" height="160">

# Hanako

**An unofficial Telegram client for Android, forked from [Mercurygram](https://github.com/Mercurygram/Mercurygram).**

[![Latest release](https://img.shields.io/github/v/release/guincalo/Hanako?label=release)](https://github.com/guincalo/Hanako/releases/latest)
[![Build](https://github.com/guincalo/Hanako/actions/workflows/hanako.yml/badge.svg?branch=hanako)](https://github.com/guincalo/Hanako/actions/workflows/hanako.yml)
[![License: GPL v2](https://img.shields.io/badge/license-GPL--2.0-blue.svg)](LICENSE)

</div>

> [!WARNING]
> **This is a fully vibecoded fork.** Every change Hanako adds on top of Mercurygram was written by AI
> coding agents, and most of it was never compiled or tested by a human before it was published.
> Expect bugs. **Use it at your own risk**, and keep backups of anything you care about.
>
> Hanako is **not affiliated with, endorsed by, or supported by Telegram** or by the upstream
> **Mercurygram** project. Please do not report Hanako bugs to either of them.

Hanako is Mercurygram (a de-Googled fork of Telegram for Android) plus a set of privacy, anti-recall and
quality-of-life features: ghost mode, per-chat locks, saved deleted messages and media, streamer mode,
plugins, message filters, OpenPGP and more. Everything Mercurygram already does is kept.

## Install

1. Open the [latest release](https://github.com/guincalo/Hanako/releases/latest).
2. Download `Hanako-<version>-arm64-v8a.apk` and install it. Builds are **arm64-v8a only**.

Notes:

- The package id is `moe.hanako.chan`. It replaced the old `it.belloworld.mercurygram` (used by
  Hanako v1.0.x), so current Hanako installs as a **separate app** next to an old Hanako v1.0.x or
  upstream Mercurygram install instead of replacing it: log in again, then uninstall the old app
  if you no longer need it. Hanako release APKs are signed with the key in this repository
  (`TMessagesProj/config/release.keystore`), not with Mercurygram's.
- The in-app updater is switched off, so Hanako never offers you an upstream Mercurygram update.
  Check the Releases page (or point [Obtainium](https://obtainium.imranr.dev/) at this repository)
  for new versions.
- The version shown in the app follows the Telegram base it is built on (for example
  `12.10.6.90.N`); the Hanako release number is the Git tag (`v1.0.0`).

## Features

Everything below is on top of upstream Mercurygram. Unless noted, new options are **off by default**
and live under **Settings → Hanako**.

### Ghost mode & privacy

- **Ghost mode, per account** (on by default): don't send read receipts, typing status, online status ("stay
  offline") or story views. Optional: read a chat when you reply in it, and push yourself back
  offline when the server shows you online while you use this device.
- **Ghost exceptions**: keep ghost mode on everywhere except chosen chats, where read receipts,
  typing status and (optionally) online-while-the-chat-is-open are sent normally.
- **Quick controls**: a ghost-mode toggle in the chat list menu, a ghost indicator in the chat list
  header while it is on, and *Send read receipt* in a chat's menu to mark just that chat as read.
- **Send as scheduled in ghost mode**: messages go out as server-side scheduled messages a few seconds
  later, so sending never puts you online.
- **Send without sound in ghost mode**: every message you send while ghost mode is on is silent.
- **Story ghost guard**: warns before opening a story when your view would be seen, offers *open
  without being seen*, and asks before reacting to or replying to a story (which marks it seen).
- **Peek a chat**: the long-press preview never sends read receipts, reaction/poll "seen" marks or
  channel view counts. Optionally, long-press anywhere on a chat row to peek.
- **No emoji interactions / "choosing a sticker"**: stop sending animated-emoji taps, "watching
  your animation" and the "choosing a sticker" status.
- **Ask before sending**: optional confirmations before voice messages, round videos, stickers and
  GIFs go out, and before voice or video calls start.
- **Screenshots allowed** everywhere, with no screenshot notifications in secret chats, and
  **content protection ignored**, so messages in protected chats can be saved, copied and forwarded.

### Chat lock & hidden chats

- **Lock individual chats** with the system biometric prompt; optionally lock all secret chats and the
  archive. Unlocks are forgotten after 60 s in the background.
- **Hide locked chats** from the chat list, folders, pickers and search; long-press the app title to
  reveal them.
- **Hide message text in notifications** from locked chats (on by default).

### Deleted & edited messages

- **Saved message history** (on by default): deleted messages stay in the chat, greyed out; edited
  messages keep their earlier versions. Photos, videos, voice messages and files are copied to private
  storage, including self-destructing and view-once media. Secret chats are never saved.
- **Reactions on deleted messages** are kept as they were at deletion (on by default).
- **Vanished chats log** (on by default): records chats the server removed from your list (you were kicked or banned,
  a channel was deleted, the other side wiped the chat) with title, username and last message.
- **Deleted media browser**: a grid of all saved media from deleted messages and expired view-once
  media, per account or per chat, with filters, save, share and delete.

### Activity log

- **Local last-seen / read log**: records contacts' online/offline transitions and when they read your
  messages, from updates the app already receives (no extra requests). Per-contact view in the
  profile menu; retention from 7 days to 1 year.

### Streamer mode

- One switch that makes the app safe to show on a stream: other people's names become pseudonyms,
  chat titles and avatars are masked, phone numbers and IDs are dotted out, notifications are
  anonymised. Optional FLAG_SECURE blocks screenshots and screen recording of the app entirely.

### Messages & chats

- **Message filters**: hide or collapse incoming messages by regular expression (globally or per
  chat), shadow-ban senders, and optionally hide blocked users' messages in groups.
- **Message shot**: turn selected messages into an image with the chat's own theme and wallpaper, then
  save or share it.
- **OpenPGP**: sign and/or encrypt messages per chat and decrypt/verify PGP blocks in received
  messages, through an installed OpenPGP app such as OpenKeychain.
- **Profile ID / DC / registration date**: the profile ID row also shows the data centre and an
  estimated registration date; long-press for a copy menu.

### Plugins

- **Plugin engine**: import plugins from a file. Rule plugins (JSON) can rewrite or cancel outgoing
  messages, change how messages are displayed, or cancel requests by name. Code plugins (dex/jar/apk)
  run Java inside the app with full access and are disabled unless you explicitly allow them.

### Network

- **Custom DNS-over-HTTPS** resolver (any RFC 8484 server).
- **Proxy auto-switch**: when the current proxy can't connect, check the other saved proxies and
  switch to one that answers.
- **Disable proxy while a VPN is on**.
- **Transfer boost**: Normal / Fast / Extreme presets for download and upload speed.

### Fun

- **Smug language**: pick **Smug** in Settings → Language and the app's most visible text (about 500
  strings: chat list, chats, settings, menus, dialogs, errors, Hanako settings) teases you in a bratty,
  smug, tsundere voice. Anything it doesn't cover stays in English.

### No upsells

- Telegram Premium upsells are hidden for accounts without Premium, and the Premium promo rows and
  banners are hidden by default.

### Limits worth knowing

The in-app descriptions are kept short; these details didn't fit there:

- **Ghost mode** never sends "online" and sends nothing on app start. *Stay offline* only sends one
  "offline" right after your own sends, reactions, votes, edits or calls (or on the next start if the
  app was closed within 5 minutes of one). Channel views are not counted. Not covered: reacting or
  replying to a story marks it seen, and Premium voice-to-text marks a voice message as listened.
  Listening to a voice or round message in a ghost-exception chat still doesn't mark it as listened.
- **Send as scheduled** is not used for secret chats, Saved Messages, comment threads, paid messages
  or dice. Recipients may see a "scheduled" marker in some apps, and on a slow connection messages
  can go out late or out of order.
- **Peek** can't be used on secret chats or forums.
- **Profile info**: the data centre comes from the profile or chat photo (unknown without one).
- **Streamer mode** shows notifications as "New message" with no sender. *Block screen capture*
  makes the whole app black in screenshots, recordings and screen shares.
- **OpenPGP** covers typed text only: captions, media, stickers and forwards are sent as usual, and
  formatting is not kept.
- **Transfer boost** is not applied to streaming playback, and uploads ignore it on a slow network.
- **Custom DoH** is not used while Tor is on; servers that only speak HTTP/2 are not supported.
- **Tor** carries Telegram's messaging traffic (MTProto) only; media downloads and other HTTP
  traffic stay direct, calls may suffer, and Telegram still sees the Tor exit.

## Building

Building on Windows is not supported; use Linux (or a Linux VM).

**Prerequisites:** Android SDK with the NDK version pinned by `ndkVersion` in
`TMessagesProj/build.gradle`, JDK 17, `git`, and the native toolchain: Ninja, Meson, `nasm`, `make`,
`cmake`, `pkg-config`, `gperf`, `python3` and `curl`. The CI build action,
[`.github/actions/build-mg/action.yml`](.github/actions/build-mg/action.yml), shows a working setup step
by step.

```bash
git clone https://github.com/guincalo/Hanako.git
cd Hanako
# your own Telegram API credentials: https://core.telegram.org/api/obtaining_api_id
printf 'APP_ID = 12345\nAPP_HASH = aaaaaaaabbbbbbccccccfffffff001122\n' > API_KEYS
# arm64-v8a release APK; MG_BUILD_TAG must be numeric X.Y.Z.M[.K]
./gradlew assembleAfatFdArm64Release -PMG_BUILD_TAG=12.10.6.90.1
```

Native libraries (BoringSSL, openh264, opus, libvpx, dav1d, FFmpeg, tlottie, TDLib) are built from
source on the first build and cached afterwards.

**CI:** `.github/workflows/hanako.yml` builds a signed arm64 APK on every push to `hanako` (kept as a
workflow artifact for 14 days). Pushing a release tag `vX.Y.Z` validates it, builds once and publishes a
GitHub Release with the APK, its SHA-256 and that version's section of [CHANGELOG.md](CHANGELOG.md); see
[RELEASING.md](RELEASING.md). A branch push of an already-tagged commit is skipped, and docs-only changes
don't trigger a build.

## Contributing

Bug reports and feature requests are welcome via [Issues](https://github.com/guincalo/Hanako/issues/new/choose).
Please include the Hanako version, your Android version and steps to reproduce. Bugs that also happen
in upstream Mercurygram or Telegram belong to those projects. See [CONTRIBUTING.md](CONTRIBUTING.md)
before opening a pull request, [SECURITY.md](SECURITY.md) for security reports and
[RELEASING.md](RELEASING.md) for how releases are made.

## Credits

Hanako stands on the work of others:

- **[Mercurygram](https://github.com/Mercurygram/Mercurygram)** by [drizzt](https://github.com/drizzt)
  and contributors, which Hanako is forked from.
- **[Telegram-FOSS](https://github.com/Telegram-FOSS-Team/Telegram-FOSS)** and its contributors, whose
  de-Googling work Mercurygram builds on.
- **[Telegram for Android](https://github.com/DrKLO/Telegram)** by Telegram FZ-LLC.
- Ideas for several features came from other Telegram forks, notably AyuGram, Nagram, NekoX, Octogram
  and FAgram; Hanako's code for them was written fresh.

Telegram is a trademark of Telegram FZ-LLC. Hanako is an independent, unofficial project.

## License

Hanako is free software under the **GNU General Public License v2.0**, the same license as
Telegram for Android and Mercurygram. See [LICENSE](LICENSE). If you distribute a modified build,
you must publish its source code too.
