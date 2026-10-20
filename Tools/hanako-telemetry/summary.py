#!/usr/bin/env python3
"""Usage: hanako-telemetry-summary [DAYS=30]

Feature counts over the last DAYS days, fewest first, so dead features stand out. Features the
app knows but nobody used are listed with 0. Then installs seen and the most frequent errors.
"""
import collections
import datetime
import glob
import json
import os
import sys

DATA = os.environ.get("HANAKO_TELEMETRY_DATA", os.path.expanduser("~/.local/share/hanako-telemetry"))
# keep in sync with HanakoTelemetry.java (counter names and settings pages)
KNOWN = """ghost_toggle ghost_option ghost_exception backup_create backup_restore settings_export
settings_import settings_undo chat_export settings_search_open streamer_toggle proxy_autoswitch
proxy_autoswitch_toggle tor_toggle activity_log_toggle chat_lock_set chat_lock_unlock_prompt
scheduled_send peek message_shot deleted_media_export message_filter_add message_filter_user_hide
openpgp_decrypt hub_open page_ghost page_deleted page_activity_log page_streamer page_chats
page_tracking_ai page_network page_security page_updates""".split()

days = int(sys.argv[1]) if len(sys.argv) > 1 else 30
since = (datetime.datetime.now(datetime.timezone.utc).date() - datetime.timedelta(days=days)).isoformat()
total = collections.Counter({k: 0 for k in KNOWN})
active = collections.defaultdict(set)   # feature -> days with any use
installs = set()
errors = collections.Counter()
seen = set()   # (install, day): a day re-sent after a failed reply is counted once
for path in sorted(glob.glob(os.path.join(DATA, "*.jsonl"))):
    with open(path, encoding="utf-8") as f:
        for line in f:
            try:
                p = json.loads(line)
            except ValueError:
                continue
            iid = p.get("install_id", "")
            for d in p.get("days", []):
                day = d.get("date", "")
                if day < since or (iid, day) in seen:
                    continue
                seen.add((iid, day))
                installs.add(iid)
                for k, v in d.get("counts", {}).items():
                    total[k] += v
                    if v:
                        active[k].add(day)
            for e in p.get("errors", []):
                if e.get("date", "") >= since:
                    top = (e.get("stack") or ["?"])[0]
                    errors[(e.get("kind", ""), e.get("class", ""), top)] += e.get("count", 1)

print("Hanako features, last %d days (since %s), %d install(s)" % (days, since, len(installs)))
print("%8s  %5s  %s" % ("uses", "days", "feature"))
for k, v in sorted(total.items(), key=lambda kv: (kv[1], kv[0])):
    mark = "  <- unused" if v == 0 else ""
    print("%8d  %5d  %s%s" % (v, len(active[k]), k, mark))
if errors:
    print("\nTop errors:")
    for (kind, cls, top), n in errors.most_common(15):
        print("%6d  %-6s %s\n        at %s" % (n, kind, cls, top))
