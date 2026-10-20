# Hanako telemetry (optional, owner only)

Hanako can send anonymous usage counts and crash/error reports to a server **you** run, so you
can see which features are used and which are dead weight. It is **off by default** and does
nothing until it is switched on *and* an upload address is set
(Settings → Hanako → Device and data → Usage statistics).

## What the app collects (only while it is on)

- Per UTC day, how many times each named feature was used. The names are fixed constants in
  `HanakoTelemetry.java`: `ghost_toggle`, `ghost_option`, `ghost_exception`, `backup_create`,
  `backup_restore`, `settings_export`, `settings_import`, `settings_undo`, `chat_export`,
  `settings_search_open`, `streamer_toggle`, `proxy_autoswitch`, `proxy_autoswitch_toggle`,
  `tor_toggle`, `activity_log_toggle`, `chat_lock_set`, `chat_lock_unlock_prompt`,
  `scheduled_send`, `peek`, `message_shot`, `deleted_media_export`, `message_filter_add`,
  `message_filter_user_hide`, `openpgp_decrypt`, `hub_open`, `page_<settings page>`.
- Crashes (uncaught) and caught errors (everything passed to `FileLog.e`): exception class, a
  scrubbed message, up to 25 stack lines (`class.method(File.java:line)`), app version, Android
  version, device model. Identical errors on one day are counted, not repeated.

Never collected: message text, chat or user IDs, usernames, phone numbers, names, titles, file
names, contacts, tokens. Every message and stack line is scrubbed: URLs/URIs, paths, file names,
e-mail addresses, `@usernames`, phone-like numbers, digit runs longer than 5 and quoted text are
replaced with `<uri>`, `<path>`, `<file>`, `<email>`, `<user>`, `<phone>`, `<n>`, `<text>`.

The batch lives in the app's private `files/hanako_telemetry/pending.json`. It is not part of
settings exports or backups. "View what would be sent" shows the exact next upload.

## Upload

- Plain JSON `POST` over **HTTPS only** to the configured address, header `X-Hanako-Key` with the
  receiver's key, `Content-Type: application/json`, body at most 256 KiB.
- At most once a day (days before today; today's counts go tomorrow), plus once at the next start
  after a crash. "Send now" in the settings ignores the daily limit.
- It follows the app's network settings: through the SOCKS5 proxy when one is on (Tor's local
  proxy included); **not at all** with an MTProto proxy, a SOCKS proxy with a password, or Tor
  switched on but not running (going direct would reveal the phone's IP).
- The install ID is 128 random bits and can be reset. Turning telemetry off deletes unsent data.

Example body:

```json
{
  "schema": 1,
  "install_id": "3f9a0c4e8b2d4f61a7c95e0d1b2a3c4d",
  "app_version": "12.1.1.90.57",
  "android": "15 (API 35)",
  "device": "Google Pixel 7",
  "sent_day": "2026-10-10",
  "days": [
    {"date": "2026-10-09", "counts": {"ghost_toggle": 3, "page_ghost": 5, "backup_create": 1}}
  ],
  "errors": [
    {"date": "2026-10-09", "kind": "caught", "class": "java.io.IOException",
     "message": "open failed: <path>: ENOENT",
     "stack": ["it.belloworld.mercurygram.HanakoChatExport.subdir(HanakoChatExport.java:431)"],
     "app_version": "12.1.1.90.57", "android": "15 (API 35)", "device": "Google Pixel 7",
     "count": 2}
  ]
}
```

## Receiver (`receiver.py`, Python 3 stdlib only)

Appends each accepted body as one line to `~/.local/share/hanako-telemetry/YYYY-MM-DD.jsonl`
(UTC day of receipt), with only the known fields and a `received` timestamp. It refuses a wrong
or missing `X-Hanako-Key` (401), bodies over 256 KiB (413) and anything that is not a schema 1
payload (400). A day file stops at 20 MiB (429), the directory at 200 MiB (507). Client IPs are
never logged or stored.

Install as a user service (the unit sets `MemoryMax=64M` and listens on `127.0.0.1:2590`):

```sh
mkdir -p ~/.local/lib/hanako-telemetry ~/.config/systemd/user ~/.local/bin
cp receiver.py summary.py ~/.local/lib/hanako-telemetry/
ln -sf ~/.local/lib/hanako-telemetry/summary.py ~/.local/bin/hanako-telemetry-summary
mkdir -m 700 -p ~/.config/hanako-telemetry
(umask 077; python3 -c 'import secrets; print(secrets.token_urlsafe(32))' > ~/.config/hanako-telemetry/secret)
cp hanako-telemetry.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now hanako-telemetry
```

Copy the key into the app (Usage statistics → Upload key) by hand; don't paste it anywhere else.

The receiver speaks plain HTTP on loopback, so it needs TLS in front of it before a phone can
reach it (the app only uploads to `https://`). See "Exposing it" below.

## Summary

```sh
hanako-telemetry-summary 30
```

prints every known feature with its use count and the number of days it was used over the last
30 days, fewest first (`<- unused` marks zeros), then the most frequent errors.
