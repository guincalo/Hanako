# Deleted messages and media: UX (Hanako 2.02)

What the user can do with deleted and edited messages, and where. One model everywhere:
**defaults** for everything, plus **keep rules** for one chat or one person.

## Model

- **Defaults** (Settings → Hanako → History → Deleted and edited messages)
  - *Save deleted & edited messages* (master switch, per account).
  - *Keep saved messages*: how long, per chat type (private chats, groups, channels),
    Telegram "Keep media" style, with per-chat period exceptions.
  - *Keep media*: size limit for one file (Never / up to 5, 20, 100 MB / any size).
  - *Storage limit*: 250 MB / 1 GB / 5 GB / no limit. Over it, the oldest copies go first;
    the messages stay, marked "removed for space".
- **Keep rules**: `(chat, person) → what to keep`.
  - chat = one chat or *All chats*; person = one person or *Everyone*.
  - what = *Text*, *Photos*, *Videos and GIFs*, *Stickers*, *Voice and round videos*,
    *Files* (six switches).
  - Most specific wins: chat + person → person in all chats → chat → defaults (keep all).
  - A message is kept when its text switch is on, or its media type switch is on.
    If the message is kept but its media type is off, the media is skipped with the
    reason "left out by a keep rule".
  - No rule = everything is kept. "Remove rule" returns to the defaults.
  - 2.01's per-chat "Stop keeping media here" migrates once into a chat rule that keeps
    text only.

## Entry points

1. **Message long-press → "Keep deleted…"** (any chat where saving is on, not secret
   chats). Opens the rule editor pre-filled with the message's chat and sender (no person
   for channel posts and own messages). Name chosen over "keeping options": it says what
   it is about; the ellipsis says a screen opens. No chevron: chevrons in Telegram's
   message menu mean an inline submenu, and this opens a full screen.
2. **Chat ⋮ → Deleted messages** (existing popup) → *Keep rule for this chat…* (chat,
   Everyone), next to the period choices, *Deleted media* and *Clear*.
3. **Settings → Deleted and edited messages → Rules**: every rule as
   "Person · Chat — Text, Photos…", tap to edit, *Add rule* for an empty one.
4. **Deleted media screen of a chat → ⋮ → Keep rule for this chat…**, and the
   "Rules: …" line at the top of that screen.

## Rule editor

```
Applies to
  Chat     >  Hanako dev group        (tap: Telegram's chat picker: chats, groups, channels)
  Person   >  Rin                     (tap: people of that chat, search box; hidden for channels)
  info: A rule for one person beats a rule for the whole chat. Without any rule, everything is kept.
When deleted or edited, keep
  [x] Text
  [x] Photos
  [x] Videos and GIFs
  [ ] Stickers
  [x] Voice and round videos
  [x] Files
  info: Media is only kept if it was already on this phone. The size and storage limits still apply.
Remove rule (red, only when the rule exists)
```

Changes save on every switch (Telegram settings behaviour, no Save button). Picking a
channel clears the person (posts have no person); picking a private chat keeps the person
only if it is one of its two people.

Person list: for a private chat, its two people; for a group, the members Telegram has
loaded (small groups: all; big groups: only those loaded, plus people that already have a
rule and the pre-filled sender); for *All chats*, contacts and private chats. Search
filters by name and @username.

## Deleted media screen

- All chats: grouped by chat, then day. ⋮ filters by type (All, Photos, Videos and GIFs,
  Files, Stickers) and *Choose chat*.
- Tapping a chat header, or choosing a chat, **opens that chat's deleted media as its own
  screen** with the same type filter. Back returns to all chats with the filter as it was
  (normal back stack; 2.01 filtered in place and back undid the filter, which was
  surprising).
- Long-press an item: Open, Save, Share, Details, **Show in chat**, Delete. *Show in chat*
  opens the chat at that message, where it is shown as a grayed "deleted" bubble: deleted
  messages are always shown in the chat itself, in chat form.
- The top line of a chat's screen says what is not kept and why ("12 never downloaded ·
  3 over the size limit · 2 left out by a keep rule · 1 removed for space") and the chat's
  rule, if any. Tapping it opens the rule.

## Kept from 2.01

No silent skipping (every not-kept media has a reason), stickers kept and shown whole and
animated, one copy per file id (×N tiles), storage cap with oldest-first pruning.

## Not done (yet)

- A dedicated "deleted only" chat view (bubbles of just the deleted messages): the chat
  itself already shows them in place; *Show in chat* jumps there.
- Person list for big groups is limited to loaded members (no network member search).
- Text-off rules leave no trace (the user asked not to keep them); they are not counted.
