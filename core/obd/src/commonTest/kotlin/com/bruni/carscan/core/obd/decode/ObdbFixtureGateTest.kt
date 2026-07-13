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
 * 56,065 of 57,160 expected values decode exactly. The other 1,095 do not, and every one
 * of them is a place where OBDb's reference implementation does something this decoder
 * deliberately refuses to do. OBDb's reassembler (`.schemas/python/can/can_frame.py`)
 * **appends consecutive frames without checking the sequence number**, and its decoder
 * (`signals.py`) **parses `nullmin`/`nullmax` but never applies them** and never filters
 * on the receive address. Matching it bit-for-bit would mean adopting those behaviours.
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
 *    structurally, and the count is pinned so it cannot quietly grow.
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

    @Test
    fun `every vendored OBDb fixture decodes to its expected values`() {
        // SAEJ1979 carries the standard mode-01 commands. The vehicle repos do not repeat
        // them, so a Ford `7E0.0101` fixture only resolves against the union — which is
        // exactly the effective-signalset rule the rest of the app is built on.
        val sae = signalset("SAEJ1979").commands

        val wrongInDamaged = mutableListOf<String>()  // a damaged capture that still produced a number
        val disagreeIntact = mutableListOf<String>()  // a sound capture we did not reproduce
        val withheldFiles = sortedSetOf<String>()     // the only files allowed to withhold anything
        var files = 0
        var cases = 0
        var damagedCases = 0
        var checks = 0
        var withheldDamaged = 0

        for (repo in REPOS) {
            val pool = signalset(repo).commands + sae

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
        assertEquals(2815, files, "vendored fixture count changed")
        assertEquals(57_160, checks, "expected-value count changed")

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
        //    could hide behind it. So the files allowed to withhold anything are pinned: a
        //    ninth one, in any repo, fails here.
        assertEquals(
            CORRUPT_CAPTURES,
            withheldFiles,
            "a fixture outside the known-corrupt captures withheld a value",
        )
        assertEquals(502, damagedCases, "number of structurally damaged transcripts changed")
        assertEquals(1087, withheldDamaged, "number of values withheld from damaged transcripts changed")
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
     * model years, and it resolves all 2815 fixtures uniquely.
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
        val REPOS = listOf("Kia-EV6", "Hyundai-Ioniq-5", "Hyundai-Elantra", "Ford-F-150")

        /**
         * The only fixtures whose captures lost bytes on the wire, and therefore the only
         * ones permitted to decode to nothing. All eight are Ioniq-5 multi-frame transcripts
         * carrying truncated or spliced lines; see [isWellFormed] for what that looks like.
         */
        val CORRUPT_CAPTURES = sortedSetOf(
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
         * The eight values in sound transcripts where this decoder deliberately disagrees
         * with OBDb's reference. Neither is a decoder defect; each is a behaviour OBDb does
         * not implement and we do. Pinned individually so that "we disagree with OBDb" can
         * never become a place for a real bug to hide.
         *
         * **`CLR_DIST` × 4 — receive-address filtering.** These Elantra captures were taken
         * with the adapter's receive filter off, so several ECUs answer PID `01 31` at once.
         * In the 2022 `[case 2]` transcript `7E8` answers `4B7F` (19327), `7E9` answers
         * `4A9F`, and `7EC`/`7EF` answer `4B4C` (19276). OBDb applies no `rax` filter and
         * simply lets the last ECU on the bus overwrite the others, so it records 19276 —
         * a reading from a module the request was never addressed to. We honour the
         * command's `rax: 7E8` (what `ATCRA` does in hardware) and report 19327, the answer
         * from the ECU we actually asked. The other three cases are transcripts in which
         * *only* a non-addressed ECU replied, so we correctly report no reading at all.
         *
         * **`ELANTRA_HL_SW` × 4 — null sentinels.** The signal is declared
         * `len:8 max:1 nullmin:0 nullmax:2 unit:noyes`, and OBDb's own name for it is
         * "Headlight switch (raw >2)" — its raw value in normal operation is 4, which is at
         * or above its own `nullmax`. OBDb never notices, because `signals.py` parses
         * `nullmin`/`nullmax` and then never reads them; it scales 4, clamps to `max:1` and
         * reports true. We apply the sentinel as the contract requires and report "no
         * reading". Worth the lead's attention: this is evidence that `nullmin`/`nullmax` in
         * OBDb data are unvalidated, because nothing upstream has ever executed them.
         */
        val KNOWN_DIVERGENCES = sortedSetOf(
            "fixtures/Hyundai-Elantra/tests/test_cases/2018/commands/7E0.0131.yaml [case 4] CLR_DIST",
            "fixtures/Hyundai-Elantra/tests/test_cases/2022/commands/7E0.0131.yaml [case 2] CLR_DIST",
            "fixtures/Hyundai-Elantra/tests/test_cases/2022/commands/7E0.0131.yaml [case 4] CLR_DIST",
            "fixtures/Hyundai-Elantra/tests/test_cases/2023/commands/7E0.0131.yaml [case 3] CLR_DIST",
            "fixtures/Hyundai-Elantra/tests/test_cases/2022/commands/770.778.22BC05_f=2021-.yaml [case 2] ELANTRA_HL_SW",
            "fixtures/Hyundai-Elantra/tests/test_cases/2023/commands/770.778.22BC05_f=2021-.yaml [case 2] ELANTRA_HL_SW",
            "fixtures/Hyundai-Elantra/tests/test_cases/2024/commands/770.778.22BC05_f=2021-.yaml [case 3] ELANTRA_HL_SW",
            "fixtures/Hyundai-Elantra/tests/test_cases/2024/commands/770.778.22BC05_f=2021-.yaml [case 6] ELANTRA_HL_SW",
        )
    }
}
