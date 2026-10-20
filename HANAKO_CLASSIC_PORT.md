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

## Post-merge compile fixes (2026-10-20)

Branch `hanako-classic-wip` was rebased onto `hanako-classic` @ 558451ec (keystore fix) and
fixed until javac and the full arm64 build passed:
- PasscodeView: duplicate `skipFingerprintOnce` field (MG and Forkgram both added it).
- SettingsActivity: two `onResume()`; kept the one that also calls `setInfo()`.
- EditTextCaption: dropped `TranslateAlert3.setSecret(...)` (MG secret-chat translate, dropped).
- ChatActivity: `isDialogTranslatableMg` -> classic's `isDialogTranslatable`; removed the MG
  secret-chat translate menu entry (`passesSecretTranslateGate`); removed the MG
  "go to first message" menu entry (Forkgram's `goToFirstMessage` is already in the menu; the
  MG id/handler stay, unreachable).
- ChatActivityEnterView: MgCharCounter call used MG's `isLiveComment` (not in classic): limit
  test is classic's `<= 100`. MgCharCounter: `ChatActivityEnterView.DEFAULT_HEIGHT` -> 44
  (MG's value; classic has no constant).
- PasscodeActivity: removed `HiddenAccountHelper.onAppPasscodeChanged()` calls (MG helper;
  Forkgram's Kotlin HiddenAccountHelper is kept and has no such hook).
- tgnet: `rotateTempAuthKeys` / `setReducedTempKeyMode` natives are registered in
  TgNetWrapper.cpp and implemented in ConnectionsManager.cpp; they built in the full CI build.
Not runtime-tested on a device yet.

## CI

- `.github/workflows/hanako-classic.yml`: full arm64 build (native deps from source, cached),
  artifact `hanako-classic-arm64`. Gradle signs with a throwaway CI key (Forkgram's in-repo
  release.keystore does not open with gradle.properties' passwords); on `hanako-classic` and
  tags the APK is then re-signed with the private Hanako key (secrets HANAKO_KEYSTORE_*),
  elsewhere it ships as `-UNOFFICIAL-debugkey`.
- `.github/workflows/hanako-classic-check.yml`: fast javac-only check on branch
  `hanako-classic-wip` (no native build), uploads `compile-log`.
- CI-only tweaks (not in tree): Play Services deps stripped like prebuild_fdroid.sh, native deps
  arm64 only, Goregram api_id 105810.

## Open gaps / TODO

- Device testing of the port (nothing has been run on a phone yet).
- Hanako strings for dropped features are still in plus_*_strings.xml / smug.xml (harmless).
- SharedConfig still carries dead MG translate/Tor fields and setters (no callers into dropped
  classes).
- Chat export: see below for what still differs from Telegram Desktop.

## Chat export vs Telegram Desktop (phase 2)

Reference, read from source (not memory): tdesktop dev @ d5b208cd46960c626a53eeb0a26f49044a815372,
`Telegram/SourceFiles/export/` (data/export_data_types.cpp, export_api_wrap.cpp,
output/export_output_html.cpp, output/export_output_json.cpp, output/export_output_abstract.cpp,
output/export_output_file.cpp, export_settings.h, view/export_view_settings.cpp).

Now matching (HanakoChatExport / HanakoChatExportActivity):
- Media types: tdesktop's seven toggles Photos, Videos, Voice messages, Video messages,
  Stickers, GIFs, Files (`Options.types`, MediaSettings::Type bits). Default photos only and
  8 MB, as tdesktop (Hanako used to default to everything).
- Classification follows DocumentMediaType (sticker > video message > voice > animated = GIF >
  video = Video > File) and folders follow DocumentFolder (video attribute first, so most GIFs,
  which carry both animated and video attributes, land in `video_files/` in tdesktop too;
  `animations/` gets animated documents without a video attribute).
- File names: ComputeDocumentName. Own document name kept; else `audio_N@DD-MM-YYYY_HH-MM-SS.ogg`
  (`.mp3` for audio/mp3), `video_N@….<ext>` (`.mov` fallback), `file_N@….<ext>` (`.unknown`
  fallback); photos `photo_N@….jpg`; separate counters per kind, counted at parse time (skipped
  files still take a number). Clashes become `name (1).ext` split at the first dot.
- Document thumbnails `<file>_thumb.jpg` (largest thumb), skipped together with the file when the
  type or size excludes it; JSON `thumbnail` + `thumbnail_file_size`.
- Contacts: vCard to `contacts/contact_N.vcard`; JSON `contact_vcard` + `contact_vcard_file_size`.
- JSON media keys/order as the JSON writer (media_type sticker/video_message/voice_message/
  animation/video_file/audio_file, skip-reason strings verbatim).
- HTML: `messages.html`, `messages2.html`, … with 1000 messages per file and
  Previous/Next pagination links; tdesktop's `css/style.css`, `js/script.js`, `images/*.png`
  (+@2x) are bundled in `assets/hanako/export_html/` (GPLv3 + OpenSSL exception, see README there)
  and copied into the export; markup uses tdesktop's classes (page_wrap/page_header/history,
  message default clearfix [joined], userpic initials with PeerColorIndex, date details title
  with UTC offset, forwarded body, reply_to with cross-page links, media_wrap generic blocks,
  photo/sticker previews written as `…_thumb` like WriteImageThumb, video/GIF thumbs,
  text entities as FormatText incl. spoilers).
- Folder: `ChatExport_YYYY-MM-DD`, ` (N)` appended while taken (NormalizePath).
- Date range: from inclusive (tdesktop `singlePeerFrom <= date`); "to" includes the whole
  picked day (tdesktop's `date < singlePeerTill` is exclusive; its UI's choice of till was not
  checked).

Still different:
- Service messages are a plain "actor action «title»" line, not tdesktop's per-action sentences.
- Size limit is a fixed list (1/8/32/100/500/2000 MB, none) instead of tdesktop's 100-step slider
  (1 MB … 4000 MB); "no limit" has no tdesktop equivalent.
- No userpic images, reactions, inline bot buttons, polls/todo/giveaways, venues/games/invoices,
  rich messages, custom emoji images, call entries in HTML; forwarded messages are never joined.
- Hanako always creates the ChatExport subfolder (tdesktop writes into an empty chosen folder
  directly); output goes through SAF, so file names may still be changed by the provider.
- Not tested on a device: needs a real export compared side by side with a tdesktop export.
