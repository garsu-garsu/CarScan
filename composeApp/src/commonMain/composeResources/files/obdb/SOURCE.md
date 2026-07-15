# Vendored OBDb data shipped in the app

`SAEJ1979.json` is a verbatim, unmodified copy of the OBDb SAE J1979 signalset. It is the
mode-01 PID set every OBD-II vehicle answers — engine RPM, vehicle speed, coolant temperature —
and **no OBDb vehicle repository contains it**: a vehicle repo holds only that manufacturer's own
commands. Without this file the app can connect to a car and then has nothing it knows how to ask
it, so it ships in the APK rather than being fetched.

`Kia-EV6.json`, `Hyundai-Ioniq-5.json`, `Hyundai-Elantra.json` and `Ford-F-150.json` are the
curated vehicle signalsets the garage lets a user pick. Each is unioned with `SAEJ1979.json` at
load time by `BundledSignalsetSource` — see that file's KDoc.

## Provenance

| File | Upstream repo | Commit | Upstream path |
|---|---|---|---|
| `SAEJ1979.json` | https://github.com/OBDb/SAEJ1979 | `d3259214a9e0340c4a6cff9ec5f8ff5953eee6f2` | `signalsets/v3/default.json` |
| `Kia-EV6.json` | https://github.com/OBDb/Kia-EV6 | `eb8df4cabc7ff97b467df4b220d788e4e6fc0787` | `signalsets/v3/default.json` |
| `Hyundai-Ioniq-5.json` | https://github.com/OBDb/Hyundai-Ioniq-5 | `89d243602cfc8635719d789e4cc34af5b1759100` | `signalsets/v3/default.json` |
| `Hyundai-Elantra.json` | https://github.com/OBDb/Hyundai-Elantra | `40d28ab204940a8790b32ef3357f107ac85784f7` | `signalsets/v3/default.json` |
| `Ford-F-150.json` | https://github.com/OBDb/Ford-F-150 | `ad00fb3e4c22429b45bb307d418e1413fd5358da` | `signalsets/v3/default.json` |

Retrieved 2026-07-13 from `https://raw.githubusercontent.com/OBDb/SAEJ1979/main/signalsets/v3/default.json`.
**Verbatim copy — not modified.** Renamed from `default.json` to `SAEJ1979.json`; the contents are
byte-identical.

`Kia-EV6.json`, `Hyundai-Ioniq-5.json`, `Hyundai-Elantra.json` and `Ford-F-150.json` were retrieved
2026-07-15 from `https://raw.githubusercontent.com/OBDb/<repo>/main/signalsets/v3/default.json` for
each repo listed above, at the commit SHA cited in the table (the SHA that last touched that path
at the time of retrieval). **Verbatim copies — not modified.** Each renamed from `default.json` to
`<repo>.json`; the contents are byte-identical to the cited commit.

## License

OBDb data is licensed under **Creative Commons Attribution-ShareAlike 4.0 International
(CC BY-SA 4.0)**.

- License: https://creativecommons.org/licenses/by-sa/4.0/
- Legal code: https://creativecommons.org/licenses/by-sa/4.0/legalcode
- Source: https://github.com/OBDb

Attribution: **OBDb contributors** (https://github.com/OBDb), CC BY-SA 4.0.

These files are used as **opaque data**: each is parsed at runtime by `SignalsetParser` and is
never code-generated into `.kt` source. A generated Kotlin source file containing the signal
tables would be an adaptation of CC BY-SA material and would place the app's own source under
ShareAlike. Keeping the JSON as data keeps the ShareAlike obligation on the data alone.

Because these files are **distributed in the APK**, the attribution above, a link to the license,
and a "no changes were made" statement must appear in the app's About / Licenses screen. That is a
distribution obligation of BY-SA, not a nicety — and unlike the test fixtures, these copies
actually reach users.
