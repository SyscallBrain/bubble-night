#!/usr/bin/env python3
"""Converts content/templates/orbit-01-maps.txt into orbit-01-blueprints.json.

Offline authoring only: it neither simulates physics nor decides solvability. It checks
shape (width by row parity, connection to row 0, color count and depth per difficulty)
so typing mistakes never reach the solver. Proof of a solution always comes from the
:leveltool solver and replay.

Usage: python3 tools/author_orbit01.py [--check]
"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAPS = ROOT / "content/templates/orbit-01-maps.txt"
SLICE = ROOT / "content/templates/opening-slice.json"
OUT = ROOT / "content/templates/orbit-01-blueprints.json"

COLORS = {"b": "blue", "p": "purple", "g": "green", "o": "orange", "k": "pink", "c": "cyan"}
COLOR_RANGE = {"easy": (3, 4), "medium": (4, 5), "hard": (5, 6)}
DEPTH = {"easy": 7, "medium": 8, "hard": 9}
DIFFICULTIES = ("easy", "medium", "hard")


def width(row):
    return 9 if row % 2 == 0 else 8


def neighbors(r, c):
    out = [(r, c - 1), (r, c + 1)]
    if r % 2 == 0:
        out += [(r - 1, c - 1), (r - 1, c), (r + 1, c - 1), (r + 1, c)]
    else:
        out += [(r - 1, c), (r - 1, c + 1), (r + 1, c), (r + 1, c + 1)]
    return out


def parse_maps(text):
    levels = []
    current = None
    difficulty = None
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.rstrip()
        if not line or line.startswith("# "):
            continue
        if line.startswith("## "):
            parts = [p.strip() for p in line[3:].split("|")]
            if len(parts) != 5:
                raise SystemExit(f"line {number}: header must have 5 fields")
            current = {
                "ordinal": int(parts[0]), "familyId": parts[1], "titlePt": parts[2],
                "learningTags": [parts[3]], "minBounces": int(parts[4]), "maps": {},
            }
            levels.append(current)
            difficulty = None
        elif line.startswith("### "):
            difficulty = line[4:].strip()
            if difficulty not in DIFFICULTIES or current is None:
                raise SystemExit(f"line {number}: invalid difficulty {difficulty!r}")
            current["maps"][difficulty] = {}
        elif line.startswith("r") and ":" in line:
            index_text, cells = line.split(":", 1)
            row = int(index_text[1:])
            cells = cells.strip()
            if difficulty is None or current is None:
                raise SystemExit(f"line {number}: map row without a difficulty")
            current["maps"][difficulty][row] = cells
        else:
            raise SystemExit(f"line {number}: unknown format: {line!r}")
    return levels


def build_board(level_id, difficulty, rows, problems):
    cells = []
    for row, text in sorted(rows.items()):
        if len(text) > width(row):
            problems.append(f"{level_id}/{difficulty} r{row}: {len(text)} cells, maximum {width(row)}")
            continue
        padded = text.ljust(width(row), ".")
        for column, symbol in enumerate(padded):
            if symbol == ".":
                continue
            if symbol not in COLORS:
                problems.append(f"{level_id}/{difficulty} r{row} c{column}: unknown symbol {symbol!r}")
                continue
            cells.append({"r": row, "c": column, "kind": "color", "color": COLORS[symbol]})
    return cells


def check_board(level_id, difficulty, cells, problems):
    if not cells:
        problems.append(f"{level_id}/{difficulty}: tabuleiro vazio")
        return
    occupied = {(cell["r"], cell["c"]) for cell in cells}
    if len(occupied) != len(cells):
        problems.append(f"{level_id}/{difficulty}: duplicate cell")
    seen = set()
    stack = [cell for cell in occupied if cell[0] == 0]
    if not stack:
        problems.append(f"{level_id}/{difficulty}: no cells on row 0")
    while stack:
        cell = stack.pop()
        if cell in seen:
            continue
        seen.add(cell)
        for n in neighbors(*cell):
            if n in occupied and n not in seen:
                stack.append(n)
    if seen != occupied:
        problems.append(f"{level_id}/{difficulty}: {len(occupied - seen)} cells not connected to the ceiling")
    colors = {cell["color"] for cell in cells}
    low, high = COLOR_RANGE[difficulty]
    if not low <= len(colors) <= high:
        problems.append(f"{level_id}/{difficulty}: {len(colors)} cores, esperado {low}–{high}")
    deepest = max(cell["r"] for cell in cells)
    if deepest > DEPTH[difficulty]:
        problems.append(f"{level_id}/{difficulty}: profundidade {deepest} excede {DEPTH[difficulty]}")


def main():
    check_only = "--check" in sys.argv
    problems = []
    parsed = parse_maps(MAPS.read_text(encoding="utf-8"))
    if sorted(level["ordinal"] for level in parsed) != list(range(11, 51)):
        raise SystemExit("the maps must cover exactly levels 11–50")

    slice_set = json.loads(SLICE.read_text(encoding="utf-8"))
    blueprints = [level for level in slice_set["levels"] if level["ordinal"] <= 10]

    for level in parsed:
        level_id = f"level-{level['ordinal']:04d}"
        boards = {}
        for difficulty in DIFFICULTIES:
            if difficulty not in level["maps"]:
                problems.append(f"{level_id}: falta {difficulty}")
                continue
            cells = build_board(level_id, difficulty, level["maps"][difficulty], problems)
            check_board(level_id, difficulty, cells, problems)
            boards[difficulty] = cells
        if len(boards) != 3:
            continue
        blueprints.append({
            "ordinal": level["ordinal"],
            "titlePt": level["titlePt"],
            "familyId": level["familyId"],
            "learningTags": level["learningTags"],
            "objective": {"type": "clear_colors"},
            "minBounces": level["minBounces"],
            "board": boards["medium"],
            "difficultyBoards": boards,
        })

    if problems:
        print("\n".join(problems))
        print(f"{len(problems)} problem(s); nothing was written.", file=sys.stderr)
        return 1

    blueprints.sort(key=lambda item: item["ordinal"])
    document = {
        "templateSetVersion": 2,
        "engineVersion": 1,
        "status": "candidate-blueprints-require-core-replay",
        "levels": blueprints,
    }
    if check_only:
        print(f"OK: {len(blueprints)} valid blueprints (shape check only, nothing written).")
        return 0
    OUT.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(blueprints)} blueprints to {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
