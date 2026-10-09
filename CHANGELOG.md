# Changelog

All notable changes to Hanako are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and Hanako is versioned by its Git tags
(`vX.Y.Z`, [Semantic Versioning](https://semver.org/)); the version shown inside the app follows the
Telegram/Mercurygram base it is built on.

## [Unreleased]

### Added
- **Backup & export** (Settings → Mercurygram → Backup & export):
  - Export / import all Hanako and Mercurygram settings as a JSON file. Import validates the file,
    then restarts the app to apply it.
  - Encrypted full backup of every logged-in account (session + settings) to move to a new install,
    package or phone without logging in again. Password-protected (PBKDF2-HMAC-SHA256, AES-256-GCM).
    Restore from the same screen or from the login screen ("Restore from Hanako backup").
  - Chat export like Telegram Desktop: pick a chat (or use the chat's ⋮ menu), a date range, which
    media to include and a size limit, and get messages.html and/or result.json plus media folders
    in a folder you choose.

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

[Unreleased]: https://github.com/guincalo/Hanako/compare/v1.1.0...hanako
[1.1.0]: https://github.com/guincalo/Hanako/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/guincalo/Hanako/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/guincalo/Hanako/releases/tag/v1.0.0
