# Vendored OBDb test fixtures and signalsets

Everything under this directory is **third-party data copied from the [OBDb] project**. It
is test input only: none of it is compiled, code-generated into Kotlin, or shipped inside
the app binary.

[OBDb]: https://github.com/OBDb

## License — attribution is required

OBDb data is licensed **[CC BY-SA 4.0]**. Two obligations follow from keeping these files
here, and they are obligations, not courtesies:

1. **Attribution.** The app's About/Licenses screen must credit OBDb, link to the project,
   link to the license, and state that the data was modified (see "Modifications" below).
   This is a hard gate before any store upload.
2. **ShareAlike.** Any *adaptation* of this data must be released under CC BY-SA 4.0. This
   is why the signalset JSON is treated as an opaque asset everywhere in this codebase and
   is never code-generated into `.kt` source: a generated Kotlin file containing the signal
   tables would be an adaptation, and ShareAlike would then reach into our own source.

[CC BY-SA 4.0]: https://creativecommons.org/licenses/by-sa/4.0/

## What was copied, and from where

Pinned to these commits — re-vendoring from `main` without updating this table makes the
record false.

| Repo | Commit | What |
|---|---|---|
| [OBDb/SAEJ1979](https://github.com/OBDb/SAEJ1979) | `d3259214a9e0340c4a6cff9ec5f8ff5953eee6f2` | `signalsets/v3/default.json` only — the repo has no `tests/` |
| [OBDb/Kia](https://github.com/OBDb/Kia) | `c4738a321128625c36ccc72d5a8029ba51ea344a` | `signalsets/v3/default.json` only — the make fallback, see below |
| [OBDb/Kia-EV6](https://github.com/OBDb/Kia-EV6) | `78819b208a425633dc67bb1de23a7f937f9038f2` | signalset + all 39 command fixtures (2022–2024) |
| [OBDb/Hyundai-Ioniq-5](https://github.com/OBDb/Hyundai-Ioniq-5) | `51827076c422cfb4927695d81fe19fa66e877742` | signalset + the 2022 and 2026 fixtures (61 files) |
| [OBDb/Hyundai-Elantra](https://github.com/OBDb/Hyundai-Elantra) | `5c48140f9ef9f82d842ccd98001f79ee77251ff4` | signalset + all 574 command fixtures (2009–2025) |
| [OBDb/Ford-F-150](https://github.com/OBDb/Ford-F-150) | `f31fc31da4a9bf58f967cde69f051ac9d30eb059` | signalset + all 2141 command fixtures (2004–2026) |
| [OBDb/Hyundai-Sonata](https://github.com/OBDb/Hyundai-Sonata) | `3f5b687be4707b704b22e842b217b3b243bf754a` | signalset + all 383 command fixtures (2009–2025) |
| [OBDb/Hyundai-Kona](https://github.com/OBDb/Hyundai-Kona) | `7cd2c8c80468e7ff547905fdfc79488931ca7caa` | signalset + all 190 command fixtures (2018–2026) |
| [OBDb/Kia-Sorento](https://github.com/OBDb/Kia-Sorento) | `76f29709820731bda82e8952681ced8866f14852` | signalset (empty) + all 301 command fixtures (2011–2026) |
| [OBDb/Kia-Niro](https://github.com/OBDb/Kia-Niro) | `bdbe8443e37001386b0051e61bc1193db1fc566f` | signalset (empty) + all 210 command fixtures (2018–2024) |
| [OBDb/Kia-EV3](https://github.com/OBDb/Kia-EV3) | `ef1d1be0d3579ce564dcd536ac6bc7fde51e4c60` | signalset (empty) + all 101 command fixtures (2024–2026) |
| [OBDb/Toyota-Prius](https://github.com/OBDb/Toyota-Prius) | `0a8c4ec72be860861548a3aeb2be007eecd83941` | signalset + all 319 command fixtures (2005–2025) |
| [OBDb/Ram-1500](https://github.com/OBDb/Ram-1500) | `1f28263b9d868b35ae21373ed278aa1b556fba4d` | signalset + all 529 command fixtures (2012–2026) |
| [OBDb/Nissan-Leaf](https://github.com/OBDb/Nissan-Leaf) | `28438d2d826d9ba149684447dccef9db22061cf9` | signalset + all 187 command fixtures (2012–2024) |
| [OBDb/Audi-A6](https://github.com/OBDb/Audi-A6) | `61cd64e777fd15d73d1338675004a97b288852b6` | signalset + all 312 command fixtures (2012–2026) |
| [OBDb/BMW-3-Series](https://github.com/OBDb/BMW-3-Series) | `7c9f3b238b2e37d2a10aa6031acfbfcca63f261a` | signalset + all 326 command fixtures (2007–2025) |

5673 fixture files, 27,845 test cases, 161,612 expected values, across 14 vehicles.

Why these: SAEJ1979 is the standard mode-01 baseline every car must satisfy. Kia-EV6 and
Hyundai-Ioniq-5 exercise mode 22, flow control and EV battery signals; Hyundai-Elantra is
the ICE case and the only source of **mode 21** commands; Ford-F-150 brings 116 mode-22
commands, year-filtered commands, and the inverted year ranges.

The ten added afterwards buy breadth in two directions. **Korea**, because it is the app's
first market: Hyundai-Sonata and Hyundai-Kona, Kia-Sorento, Kia-Niro and Kia-EV3 — ICE,
hybrid and EV, spanning 2009–2026. **Architecture**, because a decoder that only ever sees
Hyundai/Kia and Ford data is only ever verified against two houses' conventions:
Toyota-Prius (hybrid, and the only user of ISO-TP extended addressing in the corpus),
Ram-1500 and Audi-A6 (the only users of 29-bit functional addressing, `DB33`), Nissan-Leaf,
and BMW-3-Series.

Genesis was wanted and is not here: all three Genesis repos (`Genesis`, `Genesis-G70`,
`Genesis-G80`) publish **zero** test cases. Of OBDb's 740 repos, 388 have any fixtures at
all — see "OBDb coverage" below.

Hyundai-Ioniq-5 is the one repo not taken whole: its fixtures are 5.6 MB across five model
years, and 2022 + 2026 already cover every command it defines.

## The make signalset, and why `OBDb/Kia` is here

`Kia-Sorento`, `Kia-Niro` and `Kia-EV3` publish `signalsets/v3/default.json` containing
literally `{"commands": []}`, while still shipping 612 fixtures whose `expected_values`
name `KIA_*` signals. The definitions live one level up, in the **make** repo `OBDb/Kia`,
and OBDb's own harness falls back to it: `get_model_year_command_registry` in
`.schemas/python/can/command_registry.py` tests `if not signalset.commands:` and re-fetches
`https://raw.githubusercontent.com/OBDb/{make}/refs/heads/main/signalsets/v3/default.json`.

It is a **fallback, never a merge**. The make repo is a superset of its vehicles — all 116
Ford-F-150 commands also appear in `OBDb/Ford` — so unioning the two would make every
vehicle command ambiguous.

Only `OBDb/Kia` is vendored, because it is the only make whose fallback this corpus needs.

## Modifications

1. **Filenames only.** Upstream names commands like `7E4.7EC.220101|fc=1.yaml`. Windows
   forbids `|` in a filename, so it is written as `_`:
   `7E4.7EC.220101_fc=1.yaml`. **No file contents were altered** — the authoritative
   `command_id` is a field *inside* each file and still reads `7E4.7EC.220101|fc=1`, which
   is what the tests match on.
2. Nothing else. No fixture was edited, reformatted, or dropped to make a test pass.

## Known defects in the upstream data

Found by running the gate over all of it. Nothing here was corrected — these are notes about
the data as OBDb publishes it, and `ObdbFixtureGateTest` pins every one of them by name.

1. **Eight Ioniq-5 transcripts lost bytes during capture.** They carry lines like
   `74C25B800B20192074C27000007021C000D` — a frame truncated mid-byte with the next frame
   spliced onto it — and `00D`, the orphaned tail of a frame whose head was never written.
   The same frame appears intact elsewhere in the same file as `74C25B800B201920051`, so the
   bytes are simply gone. OBDb's reassembler appends consecutive frames **without checking
   the ISO-TP sequence number** (`.schemas/python/can/can_frame.py`), so it rebuilds these
   into a message of plausible length and shifted content, and its `expected_values` were
   generated from exactly that. Our reassembler drops the buffer, so these 1,087 values
   decode to nothing rather than to a confident wrong number.

2. **`nullmin` / `nullmax` have never been executed.** OBDb's `signals.py` parses both and
   then never reads them. `ELANTRA_HL_SW` is the proof: it is declared `nullmax: 2` while its
   raw value in normal operation is 4, and OBDb's own name for it is "Headlight switch
   (raw >2)". Treat every `nullmin`/`nullmax` in this data as unvalidated.

3. **No receive-address filtering — by construction.** `_make_signalset_generic` in
   `command_registry.py` runs `command.pop('rax', None)` over every standard command before
   the registry is built, so OBDb *cannot* filter mode-01 replies on receive address. When
   several ECUs answer one PID it lets the last one on the bus overwrite the others, so some
   `expected_values` come from a module the request was never addressed to: Elantra
   `CLR_DIST`, where `rax` is `7E8` but the recorded answer is `7EF`'s, and Ram-1500's
   `LOAD_PCT`/`ECT`/`VSS`/`CLR_DIST`, where the only frame in the transcript is the
   transmission at `7EA` answering a request addressed to the engine at `7E8`.

4. **The SAEJ1979 fuel-trim offsets were wrong for 20 days, and the Toyota-Prius fixtures
   were generated during them.** SAE J1979 defines PID 06/07 as `(A-128) × 100/128`, so raw
   `0x80` must decode to exactly 0 % — no correction. Commit `69b66e4f` (2025-11-09, titled
   "Fix bugs in trim pid offsets") changed `add` from `-100` to `-128` on `SHRTFT1`,
   `LONGFT1`, `SHRTFT2`, `LONGFT2` and `SHRTFT11`; commit `d3259214` (2025-11-29, the commit
   pinned above) put it back. Ten Prius `expected_values` are off by exactly -28.0 and have
   not been regenerated since. Note that OBDb fetches SAEJ1979 **live from `refs/heads/main`
   at test time** (with a 24-hour cache), so every fixture's expected values are only as
   current as the last time that fixture was regenerated.

5. **A repo can publish hundreds of fixtures and no signals.** See "The make signalset"
   above. `Kia-EV3` has exactly one commit for its signalset — "Initial commit" — and it is
   empty; its `tests/` directory contains no test runner at all.

## OBDb coverage, measured

Every one of the 740 repos in the OBDb org was queried via the git-trees API on 2026-08-08
and its `tests/test_cases/{year}/commands/*.yaml` files counted. Not estimated — counted.

| Fixtures in repo | Repos |
|---|---|
| 0 | 352 |
| 1–9 | 24 |
| 10–49 | 93 |
| 50–99 | 65 |
| 100–249 | 105 |
| 250–499 | 55 |
| 500–999 | 34 |
| 1000+ | 12 |

**388 of 740 repos have any test cases**, 83,283 fixture files in total; 730 have a
signalset. The largest are Ford-F-150 (2150), Volkswagen-Passat (1659), Audi-A3 (1381),
Ford-Escape (1264) and Audi-A5 (1230). By make: Ford 13,414 fixtures over 27 repos,
Audi 11,034 over 25, Volkswagen 9,131 over 25, Toyota 8,286 over 38, Hyundai 2,917 over 20,
Kia 2,726 over 22.

The 14 vehicles vendored here are 5,673 of those 83,283 files — under 7 % of what OBDb
publishes, chosen for spread rather than volume. Re-run the survey before assuming any of
these counts still hold; repos gain fixtures continuously (Ford-F-150 was 2141 at the pinned
commit and is 2150 today).

## How they are used

`ObdbFixtureGateTest` walks every file and runs the real pipeline over each `response`
block — which is literally ELM327-with-`ATH1` output, one CAN frame per line:

    response lines -> parseElmLine -> IsoTpReassembler -> stripServiceEcho -> SignalDecoder

and asserts every `expected_values` entry. Mode-01 fixtures resolve against the SAEJ1979
signalset, mode 21/22 against the vehicle's own — the union is the effective signalset.

## Re-vendoring

Fetch `signalsets/v3/default.json` and `tests/test_cases/{year}/commands/*.yaml` from
`https://raw.githubusercontent.com/OBDb/{Repo}/{sha}/...`, replacing `|` with `_` in the
filenames. Update the commit table above, and update the expected fixture counts in
`FixtureYamlTest` and `ObdbFixtureGateTest` — both assert them so a half-finished checkout
cannot quietly turn the gate into a test of nothing.
