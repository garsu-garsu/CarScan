# Vendored OBDb data shipped in the app

`SAEJ1979.json` is a verbatim, unmodified copy of the OBDb SAE J1979 signalset. It is the
mode-01 PID set every OBD-II vehicle answers — engine RPM, vehicle speed, coolant temperature —
and **no OBDb vehicle repository contains it**: a vehicle repo holds only that manufacturer's own
commands. Without this file the app can connect to a car and then has nothing it knows how to ask
it, so it ships in the APK rather than being fetched.

## Provenance

| File | Upstream repo | Commit | Upstream path |
|---|---|---|---|
| `SAEJ1979.json` | https://github.com/OBDb/SAEJ1979 | `d3259214a9e0340c4a6cff9ec5f8ff5953eee6f2` | `signalsets/v3/default.json` |

Retrieved 2026-07-13 from `https://raw.githubusercontent.com/OBDb/SAEJ1979/main/signalsets/v3/default.json`.
**Verbatim copy — not modified.** Renamed from `default.json` to `SAEJ1979.json`; the contents are
byte-identical.

## License

OBDb data is licensed under **Creative Commons Attribution-ShareAlike 4.0 International
(CC BY-SA 4.0)**.

- License: https://creativecommons.org/licenses/by-sa/4.0/
- Legal code: https://creativecommons.org/licenses/by-sa/4.0/legalcode
- Source: https://github.com/OBDb

Attribution: **OBDb contributors** (https://github.com/OBDb), CC BY-SA 4.0.

This file is used as **opaque data**: it is parsed at runtime by `SignalsetParser` and is never
code-generated into `.kt` source. A generated Kotlin source file containing the signal tables
would be an adaptation of CC BY-SA material and would place the app's own source under ShareAlike.
Keeping the JSON as data keeps the ShareAlike obligation on the data alone.

Because this file is **distributed in the APK**, the attribution above, a link to the license, and
a "no changes were made" statement must appear in the app's About / Licenses screen. That is a
distribution obligation of BY-SA, not a nicety — and unlike the test fixtures, this copy actually
reaches users.
