#!/usr/bin/env python3
"""Download freely licensed bird photos for testing phoneAF's pipeline.

Sources: iNaturalist (research-grade, CC0 / CC-BY / CC-BY-SA) and Wikimedia Commons categories
for flight, flocks and distant birds. Writes images + attribution.json into the output folder.
"""
import json, random, sys, time, urllib.parse, urllib.request
from pathlib import Path

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "testdata/birds")
OUT.mkdir(parents=True, exist_ok=True)
UA = {"User-Agent": "phoneAF-test-fetch/0.1 (open-source bird AF research)"}
meta = []

def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
        return r.read()

def save(img_url, name, info):
    p = OUT / name
    if p.exists():
        return
    try:
        p.write_bytes(get(img_url))
        meta.append({"file": name, **info})
    except Exception as e:
        print("skip", name, e)

# 1) iNaturalist: research-grade birds, redistributable licences, several random pages.
random.seed(7)
for page in random.sample(range(1, 200), 4):
    q = urllib.parse.urlencode({
        "taxon_id": 3, "quality_grade": "research", "photo_license": "cc0,cc-by,cc-by-sa",
        "per_page": 30, "page": page, "photos": "true",
    })
    data = json.loads(get(f"https://api.inaturalist.org/v1/observations?{q}"))
    for obs in data["results"]:
        ph = obs["photos"][0]
        if ph.get("license_code") not in ("cc0", "cc-by", "cc-by-sa"):
            continue
        url = ph["url"].replace("square", "large")
        save(url, f"inat_{obs['id']}.jpg", {
            "category": "inat", "source": f"https://www.inaturalist.org/observations/{obs['id']}",
            "license": ph["license_code"], "author": ph.get("attribution", ""),
            "species": (obs.get("taxon") or {}).get("name", ""),
        })
    time.sleep(1)

# 2) Wikimedia Commons categories for the hard cases.
CATS = {
    "flight": "Category:Birds in flight",
    "flock": "Category:Flocks of birds",
    "far": "Category:Birds in landscapes",
}
for cat, title in CATS.items():
    q = urllib.parse.urlencode({
        "action": "query", "format": "json", "generator": "categorymembers", "gcmtitle": title,
        "gcmtype": "file", "gcmlimit": 40, "prop": "imageinfo", "iiprop": "url|extmetadata",
        "iiurlwidth": 1280,
    })
    data = json.loads(get(f"https://commons.wikimedia.org/w/api.php?{q}"))
    for page in (data.get("query") or {}).get("pages", {}).values():
        ii = (page.get("imageinfo") or [{}])[0]
        url = ii.get("thumburl")
        if not url or not url.lower().split("?")[0].endswith((".jpg", ".jpeg", ".png")):
            continue
        em = ii.get("extmetadata", {})
        lic = em.get("LicenseShortName", {}).get("value", "")
        if not any(k in lic.lower() for k in ("cc0", "cc by", "public domain", "cc-by")) or "nc" in lic.lower():
            continue
        name = f"{cat}_{page['pageid']}.jpg"
        save(url, name, {
            "category": cat, "source": ii.get("descriptionurl", ""), "license": lic,
            "author": em.get("Artist", {}).get("value", ""),
        })
        time.sleep(0.2)

(OUT / "attribution.json").write_text(json.dumps(meta, indent=1))
print("downloaded", len(meta), "images into", OUT)
