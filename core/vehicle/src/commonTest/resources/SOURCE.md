# Vendored OBDb test data

The files under `obdb/` are unmodified copies of OBDb vehicle signalsets, vendored so that
`:core:vehicle` can be tested against real data with no network and no hardware.

## Provenance

| Fixture | Upstream repo | Commit | File |
|---|---|---|---|
| `obdb/SAEJ1979/signalsets/v3/default.json` | https://github.com/OBDb/SAEJ1979 | `d3259214a9e0340c4a6cff9ec5f8ff5953eee6f2` | `signalsets/v3/default.json` |
| `obdb/Kia-EV6/signalsets/v3/default.json` | https://github.com/OBDb/Kia-EV6 | `78819b208a425633dc67bb1de23a7f937f9038f2` | `signalsets/v3/default.json` |
| `obdb/Ford-F-150/signalsets/v3/default.json` | https://github.com/OBDb/Ford-F-150 | `f31fc31da4a9bf58f967cde69f051ac9d30eb059` | `signalsets/v3/default.json` |

Retrieved 2026-07-13 from `https://raw.githubusercontent.com/OBDb/{Repo}/main/signalsets/v3/default.json`.
The three files above are **verbatim copies — not modified**.

`obdb/Test-Variant/` is **not** OBDb data. It is a hand-written fixture, authored for this
repository, that exists solely to exercise model-year variant-file selection (no OBDb vehicle
repo currently ships a `{YYYY}-{YYYY}.json` alongside its `default.json`, so the selection rule
has no real-world fixture to test against).

## License

OBDb data is licensed under **Creative Commons Attribution-ShareAlike 4.0 International
(CC BY-SA 4.0)**.

- License: https://creativecommons.org/licenses/by-sa/4.0/
- Legal code: https://creativecommons.org/licenses/by-sa/4.0/legalcode
- Source: https://github.com/OBDb

Attribution: **OBDb contributors** (https://github.com/OBDb), CC BY-SA 4.0.

These files are used as **opaque data**: they are parsed at runtime and are never
code-generated into `.kt` source. A generated Kotlin source file containing the signal tables
would be an adaptation of CC BY-SA material and would place the app's own source under
ShareAlike. Keeping the JSON as data keeps the ShareAlike obligation on the data alone.

The app's user-facing About/Licenses screen must carry this attribution, a link to the license,
and — if any vendored signalset is ever edited — an "changes were made" notice. This is a
distribution obligation, not a nicety.
