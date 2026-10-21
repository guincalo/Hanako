#!/usr/bin/env python3
"""Fail if code references a string key that LocaleController cannot find.

LocaleController reads strings from localization_*.bin assets, which the
TelegramStrings gradle task builds ONLY from res/values/{strings,mg_strings,
plus_*_strings}.xml (see buildSrc TelegramBuildAppPlugin.kt). A string that
lives in another file still compiles (R.string.X exists) but shows
"LOC_ERR:X" at runtime. This script checks every R.string.X, @string/X and
literal LocaleController key against the files the task actually reads.
"""
import re, sys, glob, os
root = sys.argv[1] if len(sys.argv) > 1 else "."
os.chdir(root)
INC = re.compile(r"^(strings|mg_strings|plus_.*_strings)\.xml$")
RES_DIRS = ["TMessagesProj/src/main/res/values", "TMessagesProj_App/src/main/res/values"]
defined, stray = set(), {}
name_re = re.compile(r'<(?:string|plurals)\s+name="([^"]+)"')
for d in RES_DIRS:
    for f in glob.glob(d + "/*.xml"):
        names = name_re.findall(open(f, encoding="utf-8").read())
        if INC.match(os.path.basename(f)):
            defined.update(names)
        else:
            for n in names: stray[n] = f
# plural roots: Foo_one/Foo_other define Foo
for n in list(defined):
    m = re.match(r"(.+)_(zero|one|two|few|many|other)$", n)
    if m: defined.add(m.group(1))
code = []
for mod in ["TMessagesProj", "TMessagesProj_App"]:
    for ext in ("java", "kt"):
        code += glob.glob(f"{mod}/src/main/java/**/*.{ext}", recursive=True)
layouts = glob.glob("TMessagesProj/src/main/res/layout*/*.xml") + glob.glob("TMessagesProj/src/main/res/xml/*.xml")
pat_r = re.compile(r"(?<![\w.])(?:org\.telegram\.messenger\.)?R\.string\.(\w+)")
pat_lit = re.compile(r"LocaleController\.(getString|formatString|getPluralString|formatPluralString|formatPluralStringComma|formatPluralSpannable)\(\s*\"(\w+)\"\s*[,)]")
other_r = re.compile(r"^import\s+(?!org\.telegram\.messenger\.R;)[\w.]+\.R;", re.M)
def strip_comments(s):
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return re.sub(r"(?m)^\s*//.*$", "", s)
allow = set()
af = "scripts/string_keys_allow.txt"
if os.path.exists(af):
    allow = {l.split("#")[0].strip() for l in open(af)} - {""}
missing = {}
for f in code:
    s = strip_comments(open(f, encoding="utf-8", errors="replace").read())
    if other_r.search(s) and "org.telegram.messenger.R" not in s:
        continue  # file uses some library's R
    for k in pat_r.findall(s):
        if k not in defined: missing.setdefault(k, set()).add(f)
    for fn, k in pat_lit.findall(s):
        if k not in defined: missing.setdefault(k, set()).add(f)
for f in layouts:
    for k in re.findall(r"@string/(\w+)", open(f, encoding="utf-8").read()):
        if k not in defined: missing.setdefault(k, set()).add(f)
for k in allow: missing.pop(k, None)
if missing:
    print(f"{len(missing)} string key(s) referenced but not in the localization source files:")
    for k in sorted(missing):
        where = stray.get(k)
        print(f"  {k}" + (f"  (defined in {where}, which the strings task does not read)" if where else "") + "  <- " + ", ".join(sorted(missing[k]))[:300])
    sys.exit(1)
print(f"ok: {len(defined)} keys defined, all referenced keys present")
