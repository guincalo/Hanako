# Changelog

All notable changes to Hanako are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and Hanako is versioned by its Git tags
(`vX.Y.Z`, [Semantic Versioning](https://semver.org/)); the version shown inside the app follows the
Telegram/Mercurygram base it is built on.

## [Unreleased]

### Security
- Release APKs are now signed with a private Hanako key instead of Telegram's public in-repo key,
  so nobody else can build an APK that installs as a Hanako update. **The signing key changed:**
  uninstall the old Hanako and install the new APK (make a full backup first, restore it after).

### Added
- **Backup & export** (Settings → Mercurygram → Backup & export):
  - Export / import all Hanako and Mercurygram settings as a JSON file. Import validates the file,
    then restarts the app to apply it.
  - Encrypted full backup of every logged-in account (session + settings) to move to a new install,
    package or phone without logging in again. Password-protected (PBKDF2-HMAC-SHA256, AES-256-GCM).
    Restore from the same screen or from the login screen ("Restore from Hanako backup").
  - Chat export like Telegram Desktop: pick a chat (or use the chat's ⋮ menu), a date range, which
    media to include and a size limit, and get messages.html and/or result.json plus media folders
    in a folder you choose. Runs in the background with a progress notification.
  - Backup, settings import and chat export ask you to confirm it's you first; hidden accounts are
    left out unless you add them while signed in to one.
- **Hanako settings** are now a short list of sections with their current state, and their pages
  can be found from Settings search (page level).
- A one-time notice after login explains ghost mode and the other privacy defaults.
- A streamer-mode indicator in the chat list.

- Optional **usage statistics** for whoever runs a build (off by default, Settings → Hanako →
  Device and data → Usage statistics): daily feature counts and scrubbed crash/error reports, sent
  once a day over HTTPS to an address you set, through your proxy or Tor. Shows exactly what would
  be sent. Receiver and summary script in `Tools/hanako-telemetry/`.

### Changed
- Hanako's proxy auto-switch is now "Switch to the fastest working proxy" and says when it switches.
- Ghost mode and activity log settings now belong to the Telegram account, not the account slot,
  so they stay with the account after a restore into another slot.
- Restoring a backup changes each account only once its new session is safely in place; a restore
  that isn't finished within 30 minutes is dropped instead of applied later in the background.
- A full backup needs a screen lock or a Telegram passcode; the passcode check waits longer after
  wrong tries, like Telegram's own lock screen. A locked chat always asks Chat lock itself.
- Backup passwords need at least 12 characters.
- Shareable settings exports only carry switches and numbers (no Tor bridges, URLs or keys).
- Chat export: fixes for Android 15's time limit, slow networks and long exports (expired file
  references are refreshed), no chat title in the notification for hidden accounts, locked chats
  or streamer mode, and a notification permission request.
- New launcher icon, readable at small sizes.
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

[Unreleased]: https://github.com/guincalo/Hanako/compare/v1.1.0...hanako
[1.1.0]: https://github.com/guincalo/Hanako/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/guincalo/Hanako/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/guincalo/Hanako/releases/tag/v1.0.0
