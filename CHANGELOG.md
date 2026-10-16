# Changelog

All notable changes to Hanako are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and Hanako is versioned by its Git tags
(`vX.Y.Z`, [Semantic Versioning](https://semver.org/)); the version shown inside the app follows the
Telegram/Mercurygram base it is built on.

## [Unreleased]

## [1.3.1] - 2026-10-16

### Added
- **Your own profile tells the truth in ghost mode.** Instead of a hard-coded "online", it shows what
  Telegram last saw of you: "online" only when the server really shows you online (and "online on
  another device" when this phone did nothing, e.g. Telegram Desktop is open), otherwise "last seen
  at …" from the server or from the last offline this phone sent, and "offline" when nothing is known.
  A small ghost icon next to it opens the ghost mode settings. With ghost mode off it's the usual
  "online". Nothing extra is sent.
- **Saved last seen for people who hide theirs**: with the activity log on, a contact whose last seen
  is "recently" / "within a week" / "within a month" shows the last exact time Hanako saw them on
  their profile, e.g. "last seen recently · last seen today at 14:02 (saved)".

### Changed
- Read on send now also reads the chat after a Stars (paid) reaction, like it already did after
  messages, reactions and poll votes.

## [1.3.0] - 2026-10-13

### Added
- On-device voice transcription with NVIDIA Parakeet TDT 0.6B v3 (int8, via sherpa-onnx 1.13.8), now
  the recommended model: 25 European languages including Russian, Ukrainian and English, with
  punctuation and automatic language detection. The ~670 MB model is downloaded on demand (resumable,
  checksum-verified) and freed from memory after two idle minutes. Languages it doesn't cover fall back
  to an installed Whisper model. arm64 only.
- **Deleted & edited messages**: a redesigned screen (also in each chat's ⋮ menu), retention like
  Telegram's Keep media (per message type, with per-chat exceptions; "Don't save" never deletes copies
  you already have), and an edit history with clear version labels and a word-level diff.

### Fixed
- Deleted-message ghosts showed a blank or wrong avatar and no name for rare posters or people who
  left the chat.

## [1.2.1] - 2026-10-12

### Fixed
- Release builds now include Hanako's default address for usage statistics, so the opt-in switch
  actually reports. It is still off until you turn it on, and the address can be changed in
  developer options.

## [1.2.0] - 2026-10-12

### Security
- Release APKs are now signed with a private Hanako key instead of Telegram's public in-repo key,
  so nobody else can build an APK that installs as a Hanako update. **The signing key changed:**
  uninstall the old Hanako and install the new APK (back up your sessions first, restore them after).

### Added
- **Backup & export** (Settings → Hanako → Backup & export):
  - Encrypted backup of the sessions of every logged-in account, to move them to a new install,
    package or phone without logging in again. Only the logins: no settings or messages. Password-
    protected (PBKDF2-HMAC-SHA256, AES-256-GCM). Restore from the same screen or from the login
    screen ("Restore from Hanako backup"); accounts already signed in are left as they are.
  - Chat export like Telegram Desktop: pick a chat (or use the chat's ⋮ menu), a date range, which
    media to include and a size limit, and get messages.html and/or result.json plus media folders
    in a folder you choose. Runs in the background with a progress notification.
  - Backup, restore and chat export ask you to confirm it's you first; hidden accounts are never
    backed up.
- **Hanako settings** are now a short list of sections with their current state, and their pages
  can be found from Settings search (page level).
- A one-time notice after login explains ghost mode and the other privacy defaults.
- A streamer-mode indicator in the chat list.
- **Rin**, a built-in dark theme next to Day and Night under Color theme, also offered for auto
  night mode.
- Translate: offline language detection, so "Do not translate" and same-language hiding work
  without Google services. The Translate button is now on by default.
- **App API ID** (Settings → Hanako → App API ID, also on the login screen): choose the api_id /
  api_hash Hanako connects as. Presets: the build default, Telegram Android, iOS, macOS, Desktop
  (Snap), Telegram X, Web K and Web legacy, the Goregram, Mercurygram, OctoGram and OwlGram forks,
  or a custom pair. Shared by all accounts, applied after a restart; signed-in accounts stay signed in.
- Optional **usage statistics** (off by default, Settings → Hanako → Usage statistics): one opt-in
  switch and **View what would be sent**. Daily feature counts and scrubbed crash/error reports,
  sent once a day over HTTPS through your proxy or Tor. A build can carry a built-in receiver
  address; without one nothing is sent. Upload address, key and manual send are developer options
  (long-press the switch). Receiver and summary script in `Tools/hanako-telemetry/`.

### Changed
- Hanako's proxy auto-switch is now "Switch to the fastest working proxy" and says when it switches.
- Ghost mode and activity log settings now belong to the Telegram account, not the account slot,
  so they stay with the account after a restore into another slot.
- Restoring a backup changes each account only once its new session is safely in place; a restore
  that isn't finished within 30 minutes is dropped instead of applied later in the background.
- A session backup needs a screen lock or a Telegram passcode; the passcode check waits longer after
  wrong tries, like Telegram's own lock screen. A locked chat always asks Chat lock itself.
- Backup passwords need at least 12 characters.
- Chat export: fixes for Android 15's time limit, slow networks and long exports (expired file
  references are refreshed), no chat title in the notification for hidden accounts, locked chats
  or streamer mode, and a notification permission request.
- New launcher icon, readable at small sizes, with a monochrome layer for themed icons.
- **Deleted media** looks like a stock Telegram media screen: grouped by chat and day under a
  sticky header, photos and videos as a thumbnail grid with sender, time, duration and view-once
  badges, files and voice messages as rows with sender, time and size, total size in the title,
  and a proper empty state. Tap a chat's header to see only that chat.
- Tor settings are hidden: Mercurygram's Tor plugin only works with apps signed by Mercurygram.

## [1.1.0] - 2026-10-09

### Changed
- **New package ID `moe.hanako.chan`** (was `it.belloworld.mercurygram`). Hanako now installs as a
  separate app next to v1.0.x (and next to upstream Mercurygram) instead of replacing it, so you
  have to log in again. Uninstall the old v1.0.x app once you have moved over.
- Hanako settings texts are much shorter: descriptions are one or two short sentences and toggle
  titles fit on one line, in English and Smug. The ghost mode options can now be translated.
  Longer explanations moved to the README ("Limits worth knowing"). Other languages show the new
  English text until their translations catch up.
- The Smug language was rewritten for variety: fewer "label, tease" strings, more labels that are
  jokes on their own, plain buttons where clarity matters, and clear errors and delete prompts.

### Added
- Russian translation of all Hanako settings, dialogs and messages.
- A tiny easter egg behind `tg://hanako`.

## [1.0.1] - 2026-10-09

### Fixed
- Hanako feature settings showed `LOC_ERR:null` instead of their text: the feature strings were
  never compiled into the app's language files. They now are, in English and in Smug.
- The Hanako entry in Settings now uses a Hanako glyph instead of Mercurygram's.

## [1.0.0] - 2026-10-08

First Hanako release, built on Mercurygram (Telegram for Android 12.10.6 base).

> Fully vibecoded: all Hanako changes were written by AI coding agents. Use at your own risk. Not
> affiliated with Telegram or upstream Mercurygram.

### Branding
- App renamed to **Hanako** (launcher label, settings entry, version footer and in-app text, in all
  bundled languages). The package id stays `it.belloworld.mercurygram`.
- New **Hanako launcher icon** (adaptive icon with a themed-icon monochrome layer, plus legacy
  round/square icons).
- New **Smug** language (Settings → Language): the most visible UI strings rewritten in a bratty,
  smug, tsundere voice; everything it doesn't cover stays in English.

### Ghost mode & privacy
- Per-account ghost mode: no read receipts, typing status, online status or story views; optional
  read-on-reply and forced offline.
- Ghost exceptions: send read receipts, typing and online-while-open in chosen chats only.
- Ghost quick toggle in the chat list menu, ghost indicator, and *Send read receipt* in chats.
- Ghost mode can send messages as short server-side scheduled messages so sending never shows you
  online, and can send every message silently.
- Story ghost guard: warning before a story view would be seen, open-without-being-seen, confirm
  before reacting/replying to stories.
- Peeking a chat (long-press preview) never sends read receipts, reaction/poll seen marks or view
  counts; optional long-press-anywhere-to-peek.
- Options to stop sending emoji interactions and the "choosing a sticker" status.
- Optional confirmations before sending voice messages, round videos, stickers and GIFs, and before
  calls.
- Screenshots allowed (no screenshot notifications) and content protection ignored.

### Chat lock
- Biometric lock for individual chats, all secret chats and the archive; hidden locked chats;
  message text hidden in notifications from locked chats.

### Deleted & edited messages
- Saved history of deleted and edited messages, including media and view-once media (on by default).
- Reactions kept on deleted messages.
- Log of chats the server removed from your list.
- Browser for all saved deleted media.

### Activity log
- Local log of contacts' online/offline transitions and read events, built only from received
  updates.

### Streamer mode
- Masks names, chat titles, avatars, phone numbers and IDs, anonymises notifications; optional
  screenshot/recording block.

### Messages, plugins & network
- Regex message filters, sender shadow-ban, hide blocked users in groups.
- Message shot: export selected messages as a themed image.
- OpenPGP sign/encrypt/decrypt via an OpenPGP provider app (e.g. OpenKeychain).
- Profile ID row with data centre and estimated registration date.
- Plugin engine with rule (JSON) and opt-in code plugins.
- Custom DNS-over-HTTPS, proxy auto-switch, disable proxy while a VPN is on.
- Upload/download transfer boost.

### Other
- Telegram Premium upsells and promo rows hidden; upstream Mercurygram update checks disabled.

[Unreleased]: https://github.com/guincalo/Hanako/compare/v1.3.1...hanako
[1.3.1]: https://github.com/guincalo/Hanako/compare/v1.3.0...v1.3.1
[1.3.0]: https://github.com/guincalo/Hanako/compare/v1.2.1...v1.3.0
[1.2.1]: https://github.com/guincalo/Hanako/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/guincalo/Hanako/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/guincalo/Hanako/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/guincalo/Hanako/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/guincalo/Hanako/releases/tag/v1.0.0
