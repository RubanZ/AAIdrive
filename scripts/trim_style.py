#!/usr/bin/env python3
"""Trim a Yandex vector map style.json for car-nav use.

- Drops source-layer groups that aren't useful for driving.
- GCs library.images and library.colors against what layers still reference.
"""
import json
import sys
from pathlib import Path

SRC = Path("/Users/ruban/Development/mini/AAIdrive/style.json")
DST = Path("/Users/ruban/Development/mini/AAIdrive/style.min.json")

# (source, source-layer) pairs to drop. "*" matches any source-layer for that source.
DROP_SOURCE_LAYERS = {
    ("csmap", "indoor_a"),
    ("csmap", "indoor_poi"),
    ("csmap", "indoor_infra"),
    ("csmap", "indoor_l"),
    ("csmap", "hd_road_surface"),
    ("csmap", "hd_road_surface_border"),
    ("csmap", "hd_road_marking_l"),
    ("csmap", "hd_road_marking_a"),
    ("csmap", "poi"),
    ("csmap", "poi_navi"),
    ("csmap", "poi_ads"),
    ("csmap", "poi_ads_navi"),
    ("csmap", "transport_p"),
    ("csmap", "transport_way"),
    ("csmap", "transport_schema"),
    ("csmap", "sport_track"),
}
DROP_SOURCES = {"stv", "pht", "mrce", "mrcpe"}  # keep "trf" for traffic


def main() -> int:
    style = json.loads(SRC.read_text())
    orig_size = len(SRC.read_bytes())

    layers = style["layers"]
    kept = []
    dropped_counts = {}
    for L in layers:
        src = L.get("source")
        sl = L.get("source-layer")
        key = (src, sl)
        if src in DROP_SOURCES or key in DROP_SOURCE_LAYERS:
            dropped_counts[key] = dropped_counts.get(key, 0) + 1
            continue
        kept.append(L)
    style["layers"] = kept

    # Drop dead sources from the sources dict too.
    for dead in DROP_SOURCES:
        style.get("sources", {}).pop(dead, None)

    # GC library.images and library.colors: keep only ids still referenced
    # somewhere in the remaining layers (substring match — the style uses
    # direct id strings in expressions).
    layers_text = json.dumps(style["layers"], ensure_ascii=False)

    lib = style.get("library", {})
    imgs = lib.get("images", [])
    colors = lib.get("colors", [])

    kept_imgs = [i for i in imgs if i["id"] in layers_text]
    kept_colors = [c for c in colors if c["id"] in layers_text]
    lib["images"] = kept_imgs
    lib["colors"] = kept_colors

    DST.write_text(json.dumps(style, ensure_ascii=False, separators=(",", ":")))
    new_size = len(DST.read_bytes())

    print(f"layers: {len(layers)} -> {len(kept)}")
    print(f"images: {len(imgs)} -> {len(kept_imgs)}")
    print(f"colors: {len(colors)} -> {len(kept_colors)}")
    print(f"size:   {orig_size:,} B -> {new_size:,} B "
          f"({100 * (orig_size - new_size) / orig_size:.1f}% saved)")
    print("\nDropped layer groups:")
    for k, v in sorted(dropped_counts.items(), key=lambda x: -x[1]):
        print(f"  {k[0]}/{k[1]}: {v}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
