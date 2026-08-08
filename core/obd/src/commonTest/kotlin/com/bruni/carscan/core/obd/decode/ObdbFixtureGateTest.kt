package com.bruni.carscan.core.obd.decode

import com.bruni.carscan.core.model.DecodedValue
import com.bruni.carscan.core.model.obdb.CommandSpec
import com.bruni.carscan.core.model.obdb.ObdbCommand
import com.bruni.carscan.core.model.obdb.Signalset
import com.bruni.carscan.core.model.obdb.matches
import com.bruni.carscan.core.model.obdb.spec
import com.bruni.carscan.core.obd.decode.fixtures.fixtureFiles
import com.bruni.carscan.core.obd.decode.fixtures.fixtureText
import com.bruni.carscan.core.obd.decode.fixtures.parseFixtureYaml
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate. Every vendored OBDb fixture is a real ELM327 transcript captured from a real
 * car, together with the values OBDb's own reference implementation decodes from it. The
 * whole pipeline runs against all of them:
 *
 *     response lines -> parseElmLine -> IsoTpReassembler -> stripServiceEcho -> SignalDecoder
 *
 * Nothing here is a mock. If this is green, the decoder is right — which is the one thing
 * in this project that cannot be checked later by looking at the screen, because a decoder
 * that is subtly wrong produces numbers that look perfectly plausible.
 *
 * ## Why this is not simply `assertTrue(failures.isEmpty())`
 *
 * 159,577 of 161,612 expected values across 14 vehicles decode exactly. Of the 2,035 that
 * do not, all but 13 files' worth are places where OBDb's reference implementation does
 * something this decoder deliberately refuses to do. OBDb's reassembler
 * (`.schemas/python/can/can_frame.py`) **appends consecutive frames without checking the
 * sequence number**; its decoder (`signals.py`) **parses `nullmin`/`nullmax` but never
 * applies them**; and `command_registry.py` **deletes `rax` from every standard command**
 * before decoding, so it cannot filter on receive address at all. Matching it bit-for-bit
 * would mean adopting those behaviours.
 *
 * The exception is [EXTENDED_ADDRESSING_UNSUPPORTED] — 13 Toyota-Prius fixtures that are
 * perfectly intact and that we get wrong, because ISO-TP extended addressing is not
 * implemented. It is pinned in its own set, under its own name, for exactly that reason.
 *
 * So the assertions below are split by what they actually protect:
 *
 *  - **A wrong value is banned everywhere**, including inside damaged transcripts. This is
 *    the property that matters: no reading may ever be confidently wrong.
 *  - **On an intact transcript every expected value must decode**, with no exceptions
 *    beyond [KNOWN_DIVERGENCES], which is pinned entry-by-entry and must be consumed
 *    exactly — a stale entry fails just as loudly as a new failure.
 *  - **On a damaged transcript a value may be missing**, because the bytes that carried it
 *    were never captured. Damage is not asserted from a filename: [isWellFormed] proves it
 *    structurally, and the files that may withhold anything are pinned *by cause*.
 *
 * No fixture was deleted or edited. See `resources/fixtures/SOURCE.md`.
 */
class ObdbFixtureGateTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun signalset(repo: String): Signalset =
        json.decodeFromString(
            Signalset.serializer(),
            fixtureText("fixtures/$repo/signalsets/v3/default.json"),
        )

    /**
     * The standard commands, addressed both ways OBDb addresses them.
     *
     * `_load_standard_commands` in OBDb's `command_registry.py` loads the SAEJ1979 signalset
     * three times over, re-heading the copies `7E0`, `DB33` and `686A` and **stripping `rax`
     * from all of them**. `DB33` is the low half of `18DB33F1`, the SAE J1979 *functionally
     * addressed* request over 29-bit CAN: Ram-1500 and Audi-A6 answer it from `18DAF110` and
     * `18DAF118`, and 89 fixtures in this corpus are named for it.
     *
     * The copy is made without `rax` — but not because OBDb does that. Functional addressing
     * is a broadcast: `18DB33F1` asks *every* ECU on the bus, so there is no single module the
     * request was addressed to and no address to filter on. `rax = null` is the state our own
     * model already defines for that ("the command cleared the filter and takes whoever
     * answers"), and it is what a real 29-bit signalset entry would carry. Keeping the 11-bit
     * `rax: 7E8` on a copy re-headed for 29-bit would instead compare `18DAF110` against `7E8`
     * and discard every reply — inventing a decode failure out of a filter that was never
     * meant to apply.
     *
     * The `686A` copy is deliberately not made: no fixture in this corpus uses it, and an
     * unexercised branch in a gate is a branch nothing proves right.
     */
    private fun standardCommands(sae: List<ObdbCommand>): List<ObdbCommand> =
        sae + sae.map { it.copy(hdr = "DB33", rax = null) }

    /**
     * The vehicle's own commands — or its make's, when the repo publishes none.
     *
     * Three of the vendored repos (Kia-Sorento, Kia-Niro, Kia-EV3) ship
     * `signalsets/v3/default.json` containing literally `{"commands": []}` while still
     * publishing hundreds of fixtures whose `expected_values` name `KIA_*` signals. Those
     * definitions live one level up, in the make repo `OBDb/Kia`, and OBDb's own harness
     * falls back to it explicitly: `get_model_year_command_registry` checks
     * `if not signalset.commands:` and re-fetches `OBDb/{make}/signalsets/v3/default.json`.
     *
     * It is a fallback, never a merge. The make repo is a superset — every one of
     * Ford-F-150's 116 commands also appears in `OBDb/Ford` — so unioning the two would make
     * [resolve] ambiguous for every command a vehicle defines and drop the whole corpus on
     * the floor. `ifEmpty` is the whole rule, and it matches the reference exactly.
     *
     * **This is a gap in the app, not just in this test.** `EffectiveSignalset.of` composes
     * `SAEJ1979 + vehicle` with no make fallback, so for any vehicle whose OBDb repo carries
     * an empty signalset the app currently offers the standard mode-01 PIDs and nothing else.
     */
    private fun vehicleCommands(repo: String): List<ObdbCommand> =
        signalset(repo).commands.ifEmpty { signalset(repo.substringBefore('-')).commands }

    @Test
    fun `every vendored OBDb fixture decodes to its expected values`() {
        // SAEJ1979 carries the standard mode-01 commands. The vehicle repos do not repeat
        // them, so a Ford `7E0.0101` fixture only resolves against the union — which is
        // exactly the effective-signalset rule the rest of the app is built on.
        //
        // `DB33` is the same 103 commands addressed functionally over 29-bit CAN; see
        // [standardCommands] for why the copy carries no `rax`.
        val sae = standardCommands(signalset("SAEJ1979").commands)

        val wrongInDamaged = mutableListOf<String>()  // a damaged capture that still produced a number
        val disagreeIntact = mutableListOf<String>()  // a sound capture we did not reproduce
        val withheldFiles = sortedSetOf<String>()     // the only files allowed to withhold anything
        var files = 0
        var cases = 0
        var damagedCases = 0
        var checks = 0
        var withheldDamaged = 0

        for (repo in REPOS) {
            val pool = vehicleCommands(repo) + sae

            for (path in fixtureFiles("fixtures/$repo/tests/test_cases")) {
                files++
                val year = modelYearOf(path)
                val fixture = parseFixtureYaml(fixtureText(path))

                val command = resolve(pool, fixture.commandId, year)
                    ?: throw AssertionError(
                        "$path: no command in $repo+SAEJ1979 matches '${fixture.commandId}' for $year",
                    )
                val spec = command.spec()

                for ((i, case) in fixture.cases.withIndex()) {
                    cases++
                    val intact = isWellFormed(case.responseLines)
                    if (!intact) damagedCases++

                    val decoded = decodePipeline(case.responseLines, command, spec)

                    for ((signalId, expected) in case.expected) {
                        checks++
                        val actual = decoded[signalId]
                        val where = "$path [case $i] $signalId"
                        val agrees = actual != null && agrees(actual, expected)
                        when {
                            agrees -> Unit

                            // A sound transcript must reproduce OBDb exactly, whether we got
                            // the wrong number or no number. The only way out is the pinned set.
                            intact -> disagreeIntact += where

                            // A damaged capture is a licence to return nothing. It is never a
                            // licence to return a number, and this is the assertion that says so.
                            actual != null ->
                                wrongInDamaged += "$where: expected '$expected', got $actual"

                            else -> {
                                withheldDamaged++
                                withheldFiles += path
                            }
                        }
                    }
                }
            }
        }

        val correct = checks - disagreeIntact.size - wrongInDamaged.size - withheldDamaged
        println("OBDb gate: $files fixtures, $cases test cases, $checks expected values")
        println("  reproduced exactly     : $correct")
        println("  divergences (intact)   : ${disagreeIntact.size}")
        println("  wrong (damaged)        : ${wrongInDamaged.size}")
        println("  withheld (damaged)     : $withheldDamaged across $damagedCases damaged cases")

        // The corpus itself, so a half-finished checkout cannot turn this into a test of nothing.
        assertEquals(5673, files, "vendored fixture count changed")
        assertEquals(161_612, checks, "expected-value count changed")

        // 1. No number may ever come out of a capture that lost bytes. This is the property
        //    the whole ISO-TP sequence check exists to provide, and OBDb's own reference
        //    does not have it.
        assertTrue(
            wrongInDamaged.isEmpty(),
            "${wrongInDamaged.size} damaged transcripts still produced a value:\n" +
                wrongInDamaged.take(20).joinToString("\n"),
        )

        // 2. Every sound transcript reproduces OBDb exactly, except for the pinned
        //    divergences. Compared as a set both ways: a new disagreement fails, and so does
        //    a stale exemption that has quietly started passing.
        assertEquals(
            KNOWN_DIVERGENCES,
            disagreeIntact.toSortedSet(),
            "the set of values that deliberately disagree with OBDb's reference changed",
        )

        // 3. Damage is bounded by name, not just by count. [isWellFormed] is what *earns* a
        //    case the right to withhold a value, but if it were ever too eager it would hand
        //    that right to a fixture that is actually fine and a real "decoded nothing" bug
        //    could hide behind it. So the files allowed to withhold anything are pinned, and
        //    pinned *by cause*: one more, in any repo, fails here.
        //
        //    The three sets are kept apart on purpose. Only [CORRUPT_CAPTURES] is upstream's
        //    fault. [EXTENDED_ADDRESSING_UNSUPPORTED] is OUR OPEN DEFECT, and folding it in
        //    with the damaged captures would retire a real bug into a list of other people's
        //    problems — which is the exact failure mode this whole test exists to prevent.
        assertEquals(
            (CORRUPT_CAPTURES + EXTENDED_ADDRESSING_UNSUPPORTED + ELM_ECHO_TRANSCRIPTS).toSortedSet(),
            withheldFiles,
            "a fixture outside the known-withholding captures withheld a value",
        )
        assertEquals(1873, damagedCases, "number of structurally damaged transcripts changed")
        assertEquals(2000, withheldDamaged, "number of values withheld from damaged transcripts changed")
    }

    /**
     * True when a transcript is a sound ISO-TP capture: every line is a whole CAN frame, and
     * each answering ECU's frames form a gapless, complete message.
     *
     * This is what separates "OBDb's reference decodes this and we do not" from "the serial
     * capture lost bytes". Eight Ioniq-5 fixtures contain lines like
     * `74C25B800B20192074C27000007021C000D` — a frame truncated mid-byte with the *next*
     * frame glued onto it — and `00D`, the orphaned tail of a frame whose head was never
     * written. The same frame appears intact elsewhere in the same file as
     * `74C25B800B201920051`, so the bytes are simply gone.
     *
     * OBDb's reassembler appends consecutive frames without checking the sequence number, so
     * it reassembles these into a message of plausible length whose contents are shifted, and
     * its expected values were generated from exactly that. Reproducing those numbers would
     * mean reproducing the bug. We drop the buffer instead, and this predicate is how the
     * test proves that is what happened rather than assuming it.
     */
    private fun isWellFormed(lines: List<String>): Boolean {
        class Open(var remaining: Int, var nextSeq: Int)

        val open = HashMap<String, Open>()
        val closed = HashSet<String>()

        for (line in lines) {
            val frame = parseElmLine(line) ?: return false
            val b = frame.bytes
            if (b.isEmpty()) return false
            val pci = b[0].toInt() and 0xFF

            when (pci shr 4) {
                0x0 -> {
                    val len = pci and 0x0F
                    if (len == 0 || len > 7 || b.size < 1 + len) return false
                    closed += frame.canId
                }
                0x1 -> {
                    if (b.size < 2) return false
                    val declared = ((pci and 0x0F) shl 8) or (b[1].toInt() and 0xFF)
                    if (declared == 0) return false
                    open[frame.canId] = Open(declared - (b.size - 2), 1)
                }
                0x2 -> {
                    val o = open[frame.canId] ?: return false          // orphan consecutive frame
                    if ((pci and 0x0F) != o.nextSeq) return false      // dropped frame
                    o.nextSeq = (o.nextSeq + 1) and 0x0F
                    o.remaining -= b.size - 1
                    if (o.remaining <= 0) { open -= frame.canId; closed += frame.canId }
                }
                0x3 -> Unit                                            // flow control: inert
                else -> return false
            }
        }
        return open.isEmpty() && closed.isNotEmpty()                   // nothing left half-built
    }


    /** The full pipeline, exactly as the session layer will run it. */
    private fun decodePipeline(
        responseLines: List<String>,
        command: ObdbCommand,
        spec: CommandSpec,
    ): Map<String, DecodedValue> {
        val reassembler = IsoTpReassembler()
        val decoded = LinkedHashMap<String, DecodedValue>()

        for (line in responseLines) {
            val frame = parseElmLine(line) ?: continue
            for (message in reassembler.feed(frame)) {
                // The adapter does this in hardware with ATCRA; without it, a second ECU
                // answering the same PID overwrites the reading from the one we addressed.
                if (!acceptsReplyFrom(message.canId, command.rax)) continue
                val payload = stripServiceEcho(message, spec) ?: continue
                for (signal in command.signals) {
                    SignalDecoder.decode(signal.fmt, payload)?.let { decoded[signal.id] = it }
                }
            }
        }
        return decoded
    }

    /**
     * Resolves a fixture's `command_id` back to the command that produced it.
     *
     * The id is matched on header + request only. Its `rax` segment is not load-bearing:
     * Ford's mode-01 ids are written `7E0.0101` while the SAEJ1979 command they come from
     * declares `rax: 7E8`, so requiring the segment to match would drop every standard PID
     * on the floor. The year filter is what disambiguates commands that share a PID across
     * model years, and it resolves all 5673 fixtures uniquely.
     */
    private fun resolve(pool: List<ObdbCommand>, commandId: String, year: Int): ObdbCommand? {
        val segments = commandId.substringBefore('|').split('.')
        val hdr = segments.first()
        val request = segments.last().uppercase()

        return pool.singleOrNull {
            it.hdr.equals(hdr, ignoreCase = true) &&
                it.spec().request == request &&
                it.filter.matches(year)
        }
    }

    /**
     * Compares against the decoded *type* rather than guessing the expected string's type.
     *
     * OBDb writes booleans as `0`/`1` and enumerated states as their label (`FUELSYS1: CL`,
     * and `F150_GEAR: '1'` — a label that looks like a number). Inferring a type from the
     * text would compare a gear label numerically and pass for the wrong reason.
     */
    private fun agrees(actual: DecodedValue, expected: String): Boolean = when (actual) {
        is DecodedValue.Numeric -> {
            val e = expected.toDoubleOrNull()
            // OBDb rounds its expected values to 5 decimal places — 1.953125 is stored as
            // 1.95312, and 0.0207145037 as 0.02071. So the tolerance has to be absolute at
            // the rounding granularity (5e-6 = half of the last place), not relative: a
            // relative tolerance is far too tight for the small values and would fail ~2000
            // readings that are in fact exactly right.
            e != null && abs(actual.value - e) <= 5e-6 + 1e-9 * abs(e)
        }
        is DecodedValue.Bool -> expected == (if (actual.value) "1" else "0") ||
            expected.equals(actual.value.toString(), ignoreCase = true)
        is DecodedValue.Enumerated -> expected == actual.label
        is DecodedValue.Text -> expected == actual.value
    }

    /** `fixtures/{repo}/tests/test_cases/{year}/commands/{id}.yaml` */
    private fun modelYearOf(path: String): Int {
        val segments = path.split('/')
        return segments[segments.indexOf("test_cases") + 1].toInt()
    }

    private companion object {
        val REPOS = listOf(
            "Kia-EV6", "Hyundai-Ioniq-5", "Hyundai-Elantra", "Ford-F-150",
            "Hyundai-Sonata", "Hyundai-Kona", "Kia-Sorento", "Kia-Niro", "Kia-EV3",
            "Toyota-Prius", "Ram-1500", "Nissan-Leaf", "Audi-A6", "BMW-3-Series",
        )

        /**
         * Fixtures whose captures lost bytes on the wire, and therefore the only ones
         * permitted to decode to nothing *because upstream is damaged*.
         *
         * Every one fails [isWellFormed] on an ISO-TP structure check, not on a guess:
         * a sequence number that skips (`7E82900AAAAAAAAAAAA` arriving where seq 8 was due),
         * a sequence number that repeats (`7BB21…` twice, two interleaved messages), a frame
         * truncated mid-byte with the next one spliced onto it
         * (`74C25B800B20192074C27000007021C000D`), or `00D`, the orphaned tail of a frame whose
         * head was never written. The same frame appears intact elsewhere in the same file as
         * `74C25B800B201920051`, so the bytes are simply gone.
         *
         * The Kia-Niro and Sonata entries are the same commands, and the same corruption
         * pattern, as the Ioniq-5 ones — `22E001`, `22E009`, `220101` are shared
         * Hyundai/Kia mode-22 commands and OBDb captured them all with the same tooling.
         *
         * OBDb's reassembler appends consecutive frames **without checking the sequence
         * number** (`.schemas/python/can/can_frame.py`), so it rebuilds these into a message
         * of plausible length and shifted content, and its `expected_values` were generated
         * from exactly that. We drop the buffer instead.
         */
        val CORRUPT_CAPTURES = sortedSetOf(
            "fixtures/Hyundai-Sonata/tests/test_cases/2021/commands/7E0.7E8.22E004_f=2015-.yaml",
            "fixtures/Kia-Niro/tests/test_cases/2018/commands/7B3.7BB.220100_fc=1.yaml",
            "fixtures/Kia-Niro/tests/test_cases/2024/commands/7E3.7EB.22E001.yaml",
            "fixtures/Kia-Niro/tests/test_cases/2024/commands/7E3.7EB.22E009.yaml",
            "fixtures/Kia-Niro/tests/test_cases/2024/commands/7E4.7EC.220101_fc=1.yaml",
            "fixtures/Kia-Niro/tests/test_cases/2024/commands/7E4.7EC.220105_fc=1.yaml",
            "fixtures/Hyundai-Ioniq-5/tests/test_cases/2022/commands/744.74C.22E003_fc=1.yaml",
            "fixtures/Hyundai-Ioniq-5/tests/test_cases/2022/commands/7E3.7EB.22E001.yaml",
            "fixtures/Hyundai-Ioniq-5/tests/test_cases/2022/commands/7E3.7EB.22E009.yaml",
            "fixtures/Hyundai-Ioniq-5/tests/test_cases/2022/commands/7E4.7EC.220101_fc=1.yaml",
            "fixtures/Hyundai-Ioniq-5/tests/test_cases/2026/commands/7E3.7EB.22E001.yaml",
            "fixtures/Hyundai-Ioniq-5/tests/test_cases/2026/commands/7E3.7EB.22E009.yaml",
            "fixtures/Hyundai-Ioniq-5/tests/test_cases/2026/commands/7E4.7EC.220101_fc=1.yaml",
            "fixtures/Hyundai-Ioniq-5/tests/test_cases/2026/commands/7E4.7EC.220111.yaml",
        )

        /**
         * **AN OPEN DEFECT IN THIS DECODER. These transcripts are not damaged.**
         *
         * Every one of these commands declares `eax: "2A"` — ISO-TP *extended addressing*,
         * where the first byte after the CAN id is an address extension and the PCI starts at
         * the byte after that. [IsoTpReassembler] does not implement it: it reads byte 0 as the
         * PCI, so `7582A10076116002F2F` is taken as `0x2A` — a consecutive frame with sequence
         * 10 — arrives with no buffer open, and is discarded. Every reply to an `eax` command
         * is dropped, and [isWellFormed] is fooled the same way and calls the transcript
         * damaged. It is not: `750.758.2116` decodes cleanly by hand once the `2A` is skipped.
         *
         *     7582A10076116002F2F  ->  ext 2A | 10 07 | 61 16 00 2F 2F
         *     7582A212F0000000000  ->  ext 2A | 21    | 2F 00 …
         *     message 61 16 00 2F 2F 2F 00, payload after the `61 16` echo: 00 2F 2F 2F 00
         *
         * `PRIUS_TT_FL_V1` is `len:8 min:-20 max:150 add:-40`, so raw `00` -> -40 clamped to
         * -20 and raw `2F` (47) -> 7 — which is exactly `PRIUS_TT_FL_V1: -20`,
         * `PRIUS_TT_FR_V1: 7` as the fixture expects. The bytes are all there; we throw them
         * away.
         *
         * OBDb's reference gets this right: `CANFrame.from_line` consumes one byte as
         * `extended_receive_address` before reading the type when extended addressing is on.
         *
         * `eax` is already modelled ([ObdbCommand.eax]) and already sent to the adapter as
         * `ATCEA`, so this is a decode-side gap only. It fails safe — no wrong number is ever
         * produced, the signals simply never appear — which is why it is pinned here rather
         * than in [KNOWN_DIVERGENCES]: nothing about it is deliberate.
         */
        val EXTENDED_ADDRESSING_UNSUPPORTED = sortedSetOf(
            "fixtures/Toyota-Prius/tests/test_cases/2016/commands/750.758.2116_e=2A,fc=1,f=-2023.yaml",
            "fixtures/Toyota-Prius/tests/test_cases/2016/commands/750.758.2130_e=2A,ta=2A,fc=1,f=-2023.yaml",
            "fixtures/Toyota-Prius/tests/test_cases/2017/commands/750.758.2116_e=2A,fc=1,f=-2023.yaml",
            "fixtures/Toyota-Prius/tests/test_cases/2017/commands/750.758.2130_e=2A,ta=2A,fc=1,f=-2023.yaml",
            "fixtures/Toyota-Prius/tests/test_cases/2019/commands/750.758.2116_e=2A,fc=1,f=-2023.yaml",
            "fixtures/Toyota-Prius/tests/test_cases/2019/commands/750.758.2130_e=2A,ta=2A,fc=1,f=-2023.yaml",
            "fixtures/Toyota-Prius/tests/test_cases/2022/commands/750.758.2130_e=2A,ta=2A,fc=1,f=-2023.yaml",
            "fixtures/Toyota-Prius/tests/test_cases/2024/commands/750.758.221004_e=2A,ta=2A,fc=1,f=2023-.yaml",
            "fixtures/Toyota-Prius/tests/test_cases/2024/commands/750.758.221005_e=2A,ta=2A,fc=1,f=2023-.yaml",
            "fixtures/Toyota-Prius/tests/test_cases/2024/commands/750.758.222021_e=2A,ta=2A,fc=1.yaml",
            "fixtures/Toyota-Prius/tests/test_cases/2025/commands/750.758.221004_e=2A,ta=2A,fc=1,f=2023-.yaml",
            "fixtures/Toyota-Prius/tests/test_cases/2025/commands/750.758.221005_e=2A,ta=2A,fc=1,f=2023-.yaml",
            "fixtures/Toyota-Prius/tests/test_cases/2025/commands/750.758.222021_e=2A,ta=2A,fc=1.yaml",
        )

        /**
         * Sound transcripts that [isWellFormed] cannot certify, because OBDb recorded the
         * ELM327's own chatter alongside the frames.
         *
         * `7E0.0142` case 0 is `"0142\n7DC 04 41 42 35 A2 "` — the echoed request `0142`,
         * then one frame. `770.778.22BC05` cases 1–2 open with the echo `22BC05`. Neither
         * line is a CAN frame, so [parseElmLine] rejects it and [isWellFormed] concludes the
         * capture is damaged. [decodePipeline] handles them correctly (it skips non-frames,
         * which is the entire reason [parseElmLine] is nullable), so nothing is mis-decoded.
         *
         * The value is withheld for an unrelated and deliberate reason: the only ECU that
         * answered is `7DC`, while the command's `rax` is `7E8`. That is the same
         * receive-address filtering described in [KNOWN_DIVERGENCES] — these two files land
         * here rather than there only because the echo line costs them the "intact" verdict.
         */
        val ELM_ECHO_TRANSCRIPTS = sortedSetOf(
            "fixtures/Hyundai-Sonata/tests/test_cases/2024/commands/7E0.0142.yaml",
            "fixtures/Kia-Niro/tests/test_cases/2020/commands/770.778.22BC05.yaml",
        )

        /**
         * The 35 values in sound transcripts where this decoder disagrees with OBDb's
         * reference. None is a decoder defect; each is a behaviour OBDb does not implement
         * and we do, or a fixture OBDb generated against data it has since corrected. Pinned
         * individually so that "we disagree with OBDb" can never become a place for a real
         * bug to hide. (The one real defect found is pinned separately, in
         * [EXTENDED_ADDRESSING_UNSUPPORTED].)
         *
         * **Receive-address filtering — 11 values.** `CLR_DIST` ×7, plus Ram-1500's
         * `LOAD_PCT`, `ECT`, `VSS`. These captures were taken with the adapter's receive
         * filter off, so modules the request was never addressed to answer alongside the one
         * that was. In the Elantra 2022 `[case 2]` transcript `7E8` answers `4B7F` (19327),
         * `7E9` answers `4A9F`, and `7EC`/`7EF` answer `4B4C` (19276); OBDb records 19276,
         * the last ECU on the bus. The Ram-1500 cases are starker still — `7EA03410400` is
         * the *only* frame in the transcript, and `7EA` is the transmission, not the engine
         * at `7E8` that `rax` names, so we report no reading where OBDb reports the TCM's.
         *
         * This is not an oversight upstream, it is by construction:
         * `_make_signalset_generic` in `command_registry.py` does `command.pop('rax', None)`
         * on every standard command before the registry ever sees it. OBDb *cannot* filter on
         * receive address for mode 01. We honour `rax` (what `ATCRA` does in hardware).
         *
         * **Null sentinels — 13 values.** `ELANTRA_HL_SW` ×4, `SONATA_HL_SW`, `KIA_HL_SW` ×3,
         * `PRIUS_FLV` ×4, `KIA_HVBAT_DET_MIN_ID`. `signals.py` parses `nullmin`/`nullmax` and
         * then never reads them, so no value in OBDb's data has ever been checked against its
         * own sentinels. Three flavours of the consequence:
         *
         *  - The headlight switches are declared `max:1 nullmin:0 nullmax:2 unit:noyes` while
         *    reading 4 in normal operation — OBDb's own name for the Elantra and Sonata ones
         *    is "Headlight switch (raw >2)". OBDb scales 4, clamps to `max:1`, reports true.
         *  - `PRIUS_FLV` is `len:16 div:100 max:655 nullmax:655`: the sentinel was written in
         *    *scaled* litres, copied from `max`, while [Fmt.nullmax] is a raw-value bound. Raw
         *    `0300` (768) is a real 7.68 L reading that we withhold. Fixing this needs an
         *    upstream correction, not a decoder change — reading `nullmax` as scaled would
         *    break the `nullmax: 255` disconnected-sensor case the whole rule exists for.
         *  - `KIA_HVBAT_DET_MIN_ID` `[case 31]` is the sentinel earning its keep: the byte at
         *    `bix 240` is `EC`, which is not data at all but part of the CAN id `7EC` of a
         *    frame spliced onto the previous line. The splice landed on a valid sequence
         *    number so the transcript passes [isWellFormed]; `nullmax: 200` is what stops us
         *    reporting it. OBDb clamps it to `max: 98` and calls it a cell id.
         *
         * **A stale SAEJ1979 — 10 values.** Toyota-Prius `SHRTFT1` ×5 and `LONGFT1` ×5, every
         * one off by exactly -28.0. SAE J1979 defines PID 06/07 as `(A-128) × 100/128`, i.e.
         * `mul:100 div:128 add:-100`, which is what our pinned SAEJ1979 says and what makes
         * raw `0x80` — the no-correction point — decode to exactly 0 %. OBDb commit
         * `69b66e4f` (2025-11-09, "Fix bugs in trim pid offsets") changed `add` to `-128`, and
         * `d3259214` (2025-11-29) put it back to `-100`. These fixtures were regenerated
         * inside that 20-day window and have not been regenerated since; -28 is the whole of
         * the difference. Note OBDb fetches SAEJ1979 live from `refs/heads/main` at test time,
         * so its expected values are only ever as old as the last regeneration.
         *
         * **A boolean with a scale factor — 1 value.** `KIA_HL_LOW` is
         * `len:8 max:1 div:12 unit:noyes`. Raw 3 scales to 0.25; we honour the declared
         * boolean unit and report true, OBDb emits the intermediate 0.25. The other cases in
         * the same file agree, because there the raw value is 0 or 12.
         */
        val KNOWN_DIVERGENCES = sortedSetOf(
            "fixtures/Hyundai-Elantra/tests/test_cases/2018/commands/7E0.0131.yaml [case 4] CLR_DIST",
            "fixtures/Hyundai-Elantra/tests/test_cases/2022/commands/7E0.0131.yaml [case 2] CLR_DIST",
            "fixtures/Hyundai-Elantra/tests/test_cases/2022/commands/7E0.0131.yaml [case 4] CLR_DIST",
            "fixtures/Hyundai-Elantra/tests/test_cases/2023/commands/7E0.0131.yaml [case 3] CLR_DIST",
            "fixtures/Hyundai-Sonata/tests/test_cases/2025/commands/7E0.0131.yaml [case 1] CLR_DIST",
            "fixtures/Hyundai-Sonata/tests/test_cases/2025/commands/7E0.0131.yaml [case 6] CLR_DIST",
            "fixtures/Kia-Niro/tests/test_cases/2023/commands/7E0.0131.yaml [case 3] CLR_DIST",
            "fixtures/Ram-1500/tests/test_cases/2019/commands/7E0.0104.yaml [case 2] LOAD_PCT",
            "fixtures/Ram-1500/tests/test_cases/2019/commands/7E0.0105.yaml [case 2] ECT",
            "fixtures/Ram-1500/tests/test_cases/2019/commands/7E0.010D.yaml [case 2] VSS",
            "fixtures/Ram-1500/tests/test_cases/2019/commands/7E0.0131.yaml [case 3] CLR_DIST",
            "fixtures/Hyundai-Elantra/tests/test_cases/2022/commands/770.778.22BC05_f=2021-.yaml [case 2] ELANTRA_HL_SW",
            "fixtures/Hyundai-Elantra/tests/test_cases/2023/commands/770.778.22BC05_f=2021-.yaml [case 2] ELANTRA_HL_SW",
            "fixtures/Hyundai-Elantra/tests/test_cases/2024/commands/770.778.22BC05_f=2021-.yaml [case 3] ELANTRA_HL_SW",
            "fixtures/Hyundai-Elantra/tests/test_cases/2024/commands/770.778.22BC05_f=2021-.yaml [case 6] ELANTRA_HL_SW",
            "fixtures/Hyundai-Sonata/tests/test_cases/2023/commands/770.778.22BC05_f=2021-.yaml [case 2] SONATA_HL_SW",
            "fixtures/Kia-Niro/tests/test_cases/2020/commands/770.778.22BC05.yaml [case 4] KIA_HL_SW",
            "fixtures/Kia-Niro/tests/test_cases/2020/commands/770.778.22BC05.yaml [case 5] KIA_HL_SW",
            "fixtures/Kia-Niro/tests/test_cases/2020/commands/770.778.22BC05.yaml [case 6] KIA_HL_SW",
            "fixtures/Kia-Niro/tests/test_cases/2024/commands/7E4.7EC.220105_fc=1.yaml [case 31] KIA_HVBAT_DET_MIN_ID",
            "fixtures/Toyota-Prius/tests/test_cases/2024/commands/7C0.7C8.221022_fc=1.yaml [case 2] PRIUS_FLV",
            "fixtures/Toyota-Prius/tests/test_cases/2024/commands/7C0.7C8.221022_fc=1.yaml [case 3] PRIUS_FLV",
            "fixtures/Toyota-Prius/tests/test_cases/2025/commands/7C0.7C8.221022_fc=1.yaml [case 2] PRIUS_FLV",
            "fixtures/Toyota-Prius/tests/test_cases/2025/commands/7C0.7C8.221022_fc=1.yaml [case 3] PRIUS_FLV",
            "fixtures/Toyota-Prius/tests/test_cases/2008/commands/7E0.0106.yaml [case 0] SHRTFT1",
            "fixtures/Toyota-Prius/tests/test_cases/2008/commands/7E0.0106.yaml [case 1] SHRTFT1",
            "fixtures/Toyota-Prius/tests/test_cases/2010/commands/7E0.0106.yaml [case 0] SHRTFT1",
            "fixtures/Toyota-Prius/tests/test_cases/2010/commands/7E0.0106.yaml [case 1] SHRTFT1",
            "fixtures/Toyota-Prius/tests/test_cases/2010/commands/7E0.0106.yaml [case 2] SHRTFT1",
            "fixtures/Toyota-Prius/tests/test_cases/2008/commands/7E0.0107.yaml [case 0] LONGFT1",
            "fixtures/Toyota-Prius/tests/test_cases/2008/commands/7E0.0107.yaml [case 1] LONGFT1",
            "fixtures/Toyota-Prius/tests/test_cases/2008/commands/7E0.0107.yaml [case 2] LONGFT1",
            "fixtures/Toyota-Prius/tests/test_cases/2010/commands/7E0.0107.yaml [case 0] LONGFT1",
            "fixtures/Toyota-Prius/tests/test_cases/2010/commands/7E0.0107.yaml [case 1] LONGFT1",
            "fixtures/Kia-Sorento/tests/test_cases/2023/commands/770.778.22BC09.yaml [case 2] KIA_HL_LOW",
        )
    }
}
