# Hanako Classic port log

Hanako (Mercurygram + Hanako patches, last on `feature/telemetry-snapshot` @ 3a4b12fc, tag v1.31d)
rebased onto **Forkgram Classic** (`forkgram/forkgram-classic`, branch `classic`,
@ caac615000213fb47ab4a24469d77b2a707ab21f, APP_VERSION_NAME 13.0.2 / code 7176), which keeps
the pre-Liquid-Glass Telegram 12.1.1 design (see `classic/PLAN.md`, `classic/DESIGN_FROZEN.txt`).

Dev build, LRS numbering: nothing tagged.

## How the port was done

- Branch `hanako-classic` starts with an **orphan import commit** of forkgram-classic's tree
  (no upstream history, so it could be pushed to guincalo/Hanako without the whole Telegram
  history). Future forkgram-classic updates: fetch `forkgram-classic/classic` and apply its diff
  since caac6150 (or `git merge --allow-unrelated-histories` once, then normal merges).
- Remotes in `~/repos/hanako-classic`: `origin` = guincalo/Hanako (partial, blob:none),
  `forkgram-classic`, `telesnap` = ~/repos/hanako-telesnap (read only).
- **Layer choice.** Mercurygram = 9 `[TF]` (Telegram-FOSS infra) + 91 `[MG]` commits on upstream
  f2908b14 (12.10.6). The `[TF]` layer was **not** ported: Forkgram Classic already is a FOSS
  build (UnifiedPush, osmdroid maps, native libs from source through `jni/prepare.py`). So the
  port base is 50c843a5 (last `[TF]` commit).
- **Squashed 3-way merge instead of 193 cherry-picks.** One synthetic commit
  `50c843a5 → 3a4b12fc` (all `[MG]` + all 93 Hanako commits) was cherry-picked onto the classic
  tree with merge-ort (diff3). Result: 95 conflicted paths (67 content, 20 modify/delete,
  8 add/add), resolved by hand; the original per-commit history stays in hanako-telesnap /
  origin `feature/telemetry-snapshot`. Reason: most of the 193 commits touch the same core files,
  and replaying them one by one onto a 13.0.2 + Forkgram + 12.1.1-frozen tree would have meant
  resolving the same hunks dozens of times.
- **Design-frozen files** (ChatActivity, ChatActivityEnterView, ChatAttachAlert, EmojiView,
  ProfileActivity, BaseFragment, ActionBarLayout, DialogCell ...) were never unfrozen: classic's
  version was kept and MG/Hanako hunks were hand-ported onto it (see Conflicts).

## Ported (kept)

Hanako: ghost mode (+ per-chat exceptions, quick toggle/indicator, silent send, read ledger,
story guard), deleted/anti-delete history + edit history (word diff) + deleted media browser +
retention/Keep exceptions + deleted-dialog log/reactions, message shot, streamer mode, chat lock,
session-only backup, activity log (+ saved last seen), peek, Smug language, telemetry
(+ daily feature snapshot), Parakeet TDT transcription (sherpa-onnx), Hanako settings hub
(now an entry "Hanako" in classic's settings screen, next to Forkgram settings) and settings
search entries, message filters, chat export, App API ID, Rin theme, tg://hanako easter egg,
honest own status, send/call confirm prompts, emoji-interaction toggles, transfer boost,
profile DC/reg date.

Mercurygram [MG] features that merged and are not duplicates of Forkgram ones: per-account
MgAccountConfig, message details menu, admin menu item, saved message history
(anti-delete/anti-edit core), hide stories, disable global search, delete-for-all default,
link-preview privacy toggle, lock-screen playback block, secret-by-default, split sticker/GIF
permissions, load all profile photos, disable AI editor/summary toggles (`mg.*`, alongside
Forkgram's own), premium limits/icons, folder reorder, hide premium promo, open links in
browser, strip tracking params (paste + links), keep drafts local, confirm tg links, char
counter, copy chat name, Unicode-fold search, full-res edited photo, crop fixes, album fix,
proximity sensor toggle, save-to-downloads fix, typed in-chat search operators, add chats to
folder from folder, Mercurygram (local) folders + Saved-Messages sync, live location fixes,
pin more chats, GitHub release updater (MgUpdateChecker -> guincalo/Hanako releases, replaces
Forkgram's AppUpdater call), tgnet: reduced auth_key tracking, fake-TLS GREASE fix, legacy empty
TL types fix.

Branding/ids: applicationId `moe.hanako.chan` (no `.beta` suffix), app name "Hanako" in all
locales, Hanako launcher icon (`mipmap-anydpi-v26/ic_launcher*.xml` -> Hanako drawables),
PLAYSTORE_APP_URL -> GitHub releases. Signing: CI re-signs with the private Hanako key
(secrets HANAKO_KEYSTORE_*) on branch `hanako-classic` and tags; versionCode is classic's scheme
((7176*10+0)*10+8 = 717608 for arm64), higher than Hanako 1.31d's 711290, so it installs as an
update.

## Dropped

Per scope: OpenPGP (f18, + org.openintents API, manifest activity/queries), Tor plugin
(TMessagesProj_PluginTor, MgTorClient, settings, manifest permission/queries), proxy
auto-switch / DoH / VPN bypass (f17), all translation engines (MG translate/*, Mozhi, AIDL
offline translator, Hanako offline language detection; classic's own TranslateController,
TranslateAlert2/3, TranslateButton, OfflineLanguageDetector kept), whisper.cpp (jni/whisper
submodule, whisper_jni.cpp, CMake bits; the Java whisper framework classes stay only as
Parakeet's host and are restricted to Parakeet), plugin engine (f11), ghost send-as-scheduled
(f03).

Forkgram duplicates (Forkgram's version kept): hidden accounts (MG HiddenAccountHelper +
HiddenAccountsActivity), QR login (MgQrLogin), UnifiedPush/push stack (MG push/*,
UnifiedPushReceiver, push watchdog, MgUnifiedPushSettingsActivity, NotificationsService
keep-alive notification), Monet themes (MonetHelper, monet atthemes), AI editor button gate,
rear round camera, full recent stickers, go-to-first-message (MG id kept unused), default launch
folder, hide All Chats tab, MAX_ACCOUNT_COUNT (Forkgram 90 vs MG 8), translator AIDL.

Also not ported: MG Play Store build (MgBilling, compat.billing, bundle flavor bits), MG map
snapshot preview provider (needs MapLibre from [TF]), MG instrumentation tests
(TMessagesProj_AppTests changes), MG push gateways, fastlane metadata, MG CI actions.
"Mention" in the text formatting toolbar: classic's frozen ChatActivity menu differs, not
ported (MG's EditTextCaption side is merged but unreachable).

## Conflicts resolved by hand (notable)

- SharedConfig: Forkgram owns `disableUnifiedPush`/`unifiedPushGateway`; MG's push fields and
  setters dropped; MG settings methods inserted before Forkgram's toggleDisableUnifiedPush;
  `mgSaveConfig`/`mgLoadConfig` calls re-added (they came from [TF] in Mercurygram).
- NotificationsController: Forkgram's `isShowContent()` kept; streamer-mode and chat-lock checks
  added onto it.
- ChatActivity (frozen): menu ids, ghost/peek/deleted/export/filters/lock menu items, message
  options (details, edit history, filters), PlusMessageFilters view type, ghost injection and
  peek read-gating hand-ported into classic's messagesDidLoad; translation changes dropped.
- ProfileActivity (frozen): Hanako settings row added after Forkgram settings row; Hanako search
  entries converted to classic's search list (dropped-feature entries removed); hide-premium
  promo gate.
- DialogsActivity: Forkgram's account list / all-chats handling kept; MG folder pin limit and
  ghost quick toggle kept.
- FilterCreateActivity: Forkgram folder-icon row and MG "Mercurygram folder" check both kept
  (VIEW_TYPE_CHECK = 13).
- LaunchActivity / ApplicationLoaderImpl: MgUpdateChecker replaces Forkgram AppUpdater; MG
  update popup/layout used.
- TMessagesProj/build.gradle: classic's build kept; Hanako sherpa-onnx AAR download + jniLibs
  and HANAKO_TELEMETRY_* BuildConfig fields added (read from env or local.properties).

## CI

- `.github/workflows/hanako-classic.yml`: full arm64 build (native deps from source, cached),
  Hanako signing, artifact `hanako-classic-arm64`.
- `.github/workflows/hanako-classic-check.yml`: fast javac-only check on branch
  `hanako-classic-wip` (no native build).
- CI-only tweaks (not in tree): Play Services deps stripped like prebuild_fdroid.sh, native deps
  arm64 only, Goregram api_id 105810.

## Open gaps / TODO

- Chat export vs Telegram Desktop: see "Chat export gap" below (phase 2).
- Hanako strings for dropped features are still in plus_*_strings.xml / smug.xml (harmless).
- SharedConfig still carries dead MG translate/Tor fields and setters (no callers into dropped
  classes).

## Chat export gap vs Telegram Desktop (phase 2)

Reference: tdesktop `Telegram/SourceFiles/export/data/export_data_types.cpp` (dev).
HanakoChatExport already has: HTML and/or JSON (result.json in the import-export schema),
date range, size limit, media toggles photos/videos/voice/files, output folder
`ChatExport_<date>` with photos/, video_files/, voice_messages/, round_video_messages/,
stickers/, files/.
Differences:
1. GIFs go to `video_files/`; tdesktop uses `animations/` and has a separate GIFs toggle.
2. Toggles: tdesktop has Photos, Videos, Voice messages, Video messages, Stickers, GIFs, Files;
   Hanako has 4 (round videos ride on voice/videos, stickers/GIFs not separately switchable).
3. File names: tdesktop numbers per kind (`photo_1@DD-MM-YYYY_HH-MM-SS.jpg`, `audio_N@...ogg`,
   `video_N@...`, `file_N@...` when the document has no name); Hanako uses the message id
   (`photo_<msgId>@...`). Check Hanako's date pattern against `DD-MM-YYYY_HH-MM-SS`.
4. No `_thumb.jpg` thumbnails for documents/videos; no `contacts/contact_N.vcard`.
5. HTML: Hanako writes one self-contained messages.html with inline CSS; tdesktop writes
   messages.html, messages2.html, ... (paginated) plus css/, js/, images/ (from memory of
   export_output_html.cpp, not re-checked in this pass).
6. Folder name: Hanako `ChatExport_yyyy-MM-dd_HH-mm-ss`; tdesktop `ChatExport_YYYY-MM-DD`
   (+ suffix on clash), inside a user-chosen folder (Hanako: SAF tree picker).
