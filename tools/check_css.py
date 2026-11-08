#!/usr/bin/env python3
"""Find CSS class-name clashes in the single-file prototype.

The page is one HTML file whose stylesheet is split into sections by banner
comments:  /* ---------- 名称 ---------- */
A clash is a class that one section defines on its own (a bare `.name{…}` rule,
which applies everywhere) while another section uses the same name for its own
element. That is how `.sub`, `.pb` and `.vol` broke unrelated pages.

Classes meant to be shared across sections are listed in SHARED below.
Run:  python3 tools/check_css.py [path/to/index.html]   (exit 1 on a clash)
"""
import re
import sys
from collections import defaultdict

# design-system pieces that are deliberately reused by several sections
SHARED = {
    "btn", "primary", "round", "pill", "k", "switch", "search", "txt-btn", "more", "add-blk",
    "close", "sr", "empty", "recs-t", "sec-h", "want", "on", "is-on", "is-open", "chrome",
    "marked", "selecting", "spin", "gone", "thumb", "acts", "stats", "syn", "v", "big",
    "danger", "sh", "gutter", "front", "back", "leaf", "pg", "flat", "tp", "lbl", "rc",
    "rt", "ra", "rs", "rd", "fi", "feat", "sk", "rec", "recs-row", "lib-tabs", "groups",
    "grab", "row", "by", "ic", "t", "seal", "foot", "endpaper", "exlibris", "n", "tab",
    "word", "eyebrow", "mast", "mast-row", "view", "scroll", "app", "sub", "sub-in", "sub-top",
    "ttl", "dA", "sheet", "cur", "read", "here", "ct", "bm", "chap", "src-empty", "lib-body",
    "dot", "rule", "region", "has-add", "tools", "chip", "icon-btn", "count", "mine", "nm",
    "w2", "w3", "today", "set", "nrow", "val", "seg", "hint", "kick", "ed", "pc", "dflat",
    "dclose", "ddet", "toc-page", "lib-mast", "lib-tt", "imp-btn", "mine-f", "recs",
}

BANNER = re.compile(r"/\*\s*-{4,}\s*(.*?)\s*-{4,}\s*\*/")

def styles(html: str) -> str:
    return "\n".join(re.findall(r"<style[^>]*>(.*?)</style>", html, re.S))

def rules(css: str):
    """Yield (section, selector, in_media) for every rule, top level or inside @media."""
    section, i, depth, media_depth, buf = "top", 0, 0, [], ""
    while i < len(css):
        m = BANNER.match(css, i)
        if m:
            section, i, buf = m.group(1), m.end(), ""
            continue
        if css.startswith("/*", i):
            j = css.find("*/", i + 2); i = len(css) if j < 0 else j + 2
            continue
        ch = css[i]
        if ch == "{":
            sel = buf.strip(); buf = ""
            if sel.startswith("@"):
                media_depth.append(depth)
            else:
                for one in sel.split(","):
                    yield section, one.strip(), bool(media_depth)
            depth += 1
        elif ch == "}":
            depth -= 1
            if media_depth and media_depth[-1] == depth:
                media_depth.pop()
            buf = ""
        elif depth == len(media_depth):        # only collect selector text outside declarations
            buf += ch
        i += 1

# page-level containers: being inside one of these says nothing about which element a class names
GLOBAL = {"app", "views", "view", "scroll"}
STATE = {"on", "is-on", "is-open", "chrome", "marked", "selecting", "spin", "gone", "off", "cur", "read",
         "today", "sk", "feat", "big", "danger", "primary", "round", "fav", "w2", "w3", "ten"}
STATE_SEL = re.compile(r"(\[[^\]]*\]|::?[\w-]+(?:\([^)]*\))?)")

def compounds(sel):
    return [c for c in re.split(r"\s*[ >~+]\s*", sel.strip()) if c]

def main(path: str) -> int:
    css = styles(open(path, encoding="utf-8").read())
    home = defaultdict(set)          # class -> sections holding a bare rule for it
    seen = []                        # (section, class, context) for every use in a subject
    for section, sel, in_media in rules(css):
        if "tablet" in section.lower() or in_media or sel.startswith(":root") or sel.startswith("@"):
            continue
        parts = compounds(STATE_SEL.sub("", sel))
        if not parts:
            continue
        subj = re.findall(r"\.([A-Za-z_][\w-]*)", parts[-1])
        anc = [n for c in parts[:-1] for n in re.findall(r"\.([A-Za-z_][\w-]*)", c)]
        anc = [a for a in anc if a not in GLOBAL]
        own = [n for n in subj if n not in STATE]
        if len(parts) == 1 and len(own) == 1:
            home[own[0]].add(section)
        for n in own:
            ctx = sorted((set(own) - {n}) | {a for a in anc if a not in STATE})
            if ctx:
                seen.append((section, n, ctx))
    clashes, warns = [], []
    for n, secs in sorted(home.items()):
        if len(secs) > 1:
            warns.append(f".{n} has its base rule split over sections {sorted(secs)} (fine if it is one element; a clash if two)")
    for section, n, ctx in seen:
        if n in home and section not in home[n] and n not in SHARED:
            clashes.append(f".{n} (defined in {sorted(home[n])}) is reused for another element in [{section}] with {ctx}")
    for w in warns:
        print("check ", w)
    for c in dict.fromkeys(clashes):
        print("CLASH ", c)
    print(f"{len(set(clashes))} clash(es)" if clashes else "no class clashes")
    return 1 if clashes else 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/index.html"))
