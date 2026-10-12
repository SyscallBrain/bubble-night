# Level content

Bubble Night ships 600 base levels in 12 orbits of 50. Each level has three variants (Easy, Medium and Hard), which makes 1,800 playable boards. Every variant has a **certificate**: a recorded three-star solution that the build replays with the game engine.

## Layout

```
content/
├── catalog.json              Index: chapters (orbits) and one entry per level with path, revision and SHA-256
├── levels/orbit-NN/          One JSON file per base level (level-0001.json … level-0600.json)
├── certificates/level-NNNN/  easy.json, medium.json, hard.json
├── schema/                   JSON Schemas for the catalog, levels, certificates and packaged assets
└── templates/                Authoring sources: orbit 1 blueprints and the structural families
```

`content/candidates/` holds work in progress from the generators and is ignored by git.

## Level format

```jsonc
{
  "schemaVersion": 1,
  "engineVersion": 1,
  "id": "level-0001",
  "ordinal": 1,
  "orbitId": "orbit-01",
  "titlePt": "Primeiro disparo",       // Portuguese authoring title; the app localizes titles itself
  "familyId": "open-corridor",         // structural family, used for variety limits
  "learningTags": ["direct-aim"],
  "revision": 2,                       // raise whenever gameplay changes
  "authorNotes": "…",
  "variants": {
    "easy": {
      "seedHex": "de0798c2e2b49078",   // SplitMix64 seed for this variant's ammunition
      "board": [
        { "r": 0, "c": 2, "kind": "color", "color": "blue" },
        { "r": 1, "c": 3, "kind": "rock" },
        { "r": 2, "c": 4, "kind": "star" }
      ],
      "objective": { "type": "clear_colors" },
      "maxShots": 10,
      "twoStarMaxShots": 7,
      "threeStarMaxShots": 5,
      "expectedMetrics": { "familyId": "open-corridor", "minBounces": 0 }
    },
    "medium": { … },
    "hard": { … }
  }
}
```

The colors are `blue`, `purple`, `green`, `orange`, `pink` and `cyan`. Each one also has a symbol in the UI.

| Objective | Meaning |
|---|---|
| `clear_colors` | Remove every color bubble. Rocks may stay. No stars allowed. |
| `rescue_stars` (`total`) | Make every star fall. `total` must match the board. |
| `clear_color_set` (`colors`) | Remove the listed colors completely. Other pieces may stay. No stars allowed. |

## Validation rules

`leveltool validate` enforces, among others:

- Ids, ordinals, orbit ids and chapter ranges are consistent, and the ordinals cover exactly `1..N`.
- Every file matches its SHA-256 and revision in `catalog.json`.
- Boards contain only legal positions, no duplicates, and are fully connected to row 0. Stars are never on row 0.
- The deepest starting row is 7 on Easy, 8 on Medium and 9 on Hard.
- Color count per profile: levels 1–10 use 2–3 colors; after that Easy uses 3–4, Medium 4–5 and Hard 5–6.
- Pieces are introduced gradually: rocks from orbit 2, stars from orbit 3, color-set objectives from orbit 5.
- Shot limits are ordered: `threeStarMaxShots ≤ twoStarMaxShots ≤ maxShots`.
- No semantic duplicates per difficulty. A structural fingerprint may repeat in at most 3 bases, and no family exceeds 15% of the catalog. A full catalog uses at least 12 structural families.

## Certificates

A certificate records the solver's winning line for one variant:

- the level revision, its semantic hash, the engine version and the seed;
- the initial state hash;
- each step: whether to swap first, the angle in millidegrees, the resulting state hash and shot metrics (target cell, bounces, popped and dropped cells, tested aim window);
- the final state hash, shots used, score, rescued stars and `outcome: "WON"`.

`verify-certificates` replays every step from the current level definition. It fails if a command is refused, a hash differs, the game ends early or late, or the solution needs more shots than the three-star limit. Changing a level's gameplay or the engine invalidates its certificates until they are solved again.

The **semantic hash** covers only what affects gameplay (board, objective, seed, budgets and versions). Titles and notes can therefore change without re-certifying.

## Solver

`solve` runs a single-threaded beam search per variant over the real engine state (board, ammunition, RNG, objective). From each state it tries shooting with and without a swap, across the aim range. Angles are deduplicated by logical outcome and refined near target changes. The defaults are a beam width of 128, 100,000 expanded states and 30 s per variant; `--jobs` runs variants in parallel.

When no solution is found within the limits, the variant stays **uncertified**. That does not prove it is impossible, but uncertified content is never published.

After solving, `rebalance` sets each variant's budgets from the certified solution length *S*:

| Difficulty | 3 stars | 2 stars | Max shots | Minimum max shots |
|---|---|---|---|---|
| Easy | S + 3 | S + 5 | S + 8 | 9 |
| Medium | S + 2 | S + 3 | S + 5 | 7 |
| Hard | S + 1 | S + 2 | S + 3 | 5 |

## Commands

All commands run from the repository root:

```bash
./gradlew :leveltool:run --args='<command> [options]'
```

| Command | Example |
|---|---|
| Validate | `validate --catalog content/catalog.json --strict` |
| Verify certificates | `verify-certificates --catalog content/catalog.json` |
| Report | `report --catalog content/catalog.json --output report.json` |
| Solve | `solve --catalog <catalog> --output <certificates dir> --resume --jobs 4` |
| Rebalance | `rebalance --catalog <catalog> --orbit 3-12` |
| Generate orbit 1 | `generate --orbit 1 --count 50 --seed <n> --output <dir>` |
| Extend with a new orbit | `extend --base content/catalog.json --orbit <2..12> --count 50 --seed <n> --output <dir>` |
| Regenerate orbits | `regenerate --catalog content/catalog.json --orbit 3,5 --seed <n> --output <dir>` |
| Promote | `promote --candidates <dir> --catalog content/catalog.json --write` |
| Export assets | `export-assets --catalog content/catalog.json --output app/build/generated/bubbleAssets` |

Orbit lists accept `3`, `3,5` or `3-12`. `promote` runs as a dry run unless `--write` is given, and it refuses to remove or silently change published levels.

## Pipelines

**Hand-authored orbit 1.** Edit `content/templates/orbit-01-maps.txt`, where each level is a header line and a small ASCII map per difficulty. Then run:

```bash
python3 tools/author_orbit01.py --check   # shape check only
python3 tools/author_orbit01.py           # writes orbit-01-blueprints.json
```

Then `generate`, `solve`, `verify-certificates` and `promote`.

**Procedural orbits 2–12.** The scripts generate candidates from the structural families, solve them, and redraw only the levels the solver could not prove, for a few rounds:

```bash
tools/extend_orbit.sh <orbit> <seed> [rounds]
tools/regenerate_orbit.sh <orbits> <seed> [rounds] [start-round] [jobs]
```

Each base has its own seed derived from the catalog seed and its ordinal, so redrawing one level never moves the others. When a pack is fully certified, promote it with `promote --write` (and `--replace-orbit` for regenerated orbits).

## Packaging

`:app` never reads `content/` directly. On every build, `:leveltool:exportGameAssets` validates the catalog, replays the certificates and writes the following to `app/build/generated/bubbleAssets`:

- `catalog.json`: runtime index (orbits, level metadata, variant paths);
- `levels/level-NNNN/{easy,medium,hard}.json`: each variant as a core `LevelDefinition`;
- `manifest.json`: SHA-256 of the catalog and of every level file.

The app checks these hashes on load. Certificates, templates and authoring notes are not packaged.
