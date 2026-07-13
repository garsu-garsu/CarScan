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
| [OBDb/Kia-EV6](https://github.com/OBDb/Kia-EV6) | `78819b208a425633dc67bb1de23a7f937f9038f2` | signalset + all 39 command fixtures (2022–2024) |
| [OBDb/Hyundai-Ioniq-5](https://github.com/OBDb/Hyundai-Ioniq-5) | `51827076c422cfb4927695d81fe19fa66e877742` | signalset + the 2022 and 2026 fixtures (61 files) |
| [OBDb/Hyundai-Elantra](https://github.com/OBDb/Hyundai-Elantra) | `5c48140f9ef9f82d842ccd98001f79ee77251ff4` | signalset + all 574 command fixtures (2009–2025) |
| [OBDb/Ford-F-150](https://github.com/OBDb/Ford-F-150) | `f31fc31da4a9bf58f967cde69f051ac9d30eb059` | signalset + all 2141 command fixtures (2004–2026) |

2815 fixture files, 12,959 test cases, ~57,000 expected values.

Why these five: SAEJ1979 is the standard mode-01 baseline every car must satisfy;
Kia-EV6 and Hyundai-Ioniq-5 exercise mode 22, flow control and EV battery signals;
Hyundai-Elantra is the ICE case and the only source of **mode 21** commands; Ford-F-150
brings 116 mode-22 commands, year-filtered commands, and the inverted year ranges.

Hyundai-Ioniq-5 is the one repo not taken whole: its fixtures are 5.6 MB across five model
years, and 2022 + 2026 already cover every command it defines.

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

3. **No receive-address filtering.** When several ECUs answer one PID, OBDb lets the last one
   on the bus overwrite the others, so some `expected_values` come from a module the request
   was never addressed to (Elantra `CLR_DIST`, where `rax` is `7E8` but the recorded answer
   is `7EF`'s). The captures were evidently taken with the adapter's filter off.

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
