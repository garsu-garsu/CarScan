package com.bruni.carscan.core.obd.decode.fixtures

/** One OBDb test case: an ELM transcript and what it must decode to. */
class FixtureCase(val expected: Map<String, String>, val responseLines: List<String>)

/** One `tests/test_cases/{year}/commands/{id}.yaml`. */
class FixtureFile(val commandId: String, val cases: List<FixtureCase>)

/**
 * A mapping key. The leading character must accept a digit: OBDb names BMW's signals
 * `3SERIES_EOT` and Ram's `1500_GEAR`, and an identifier-shaped pattern silently skipped
 * all 2466 of them — the line simply did not match, so the value was never checked and
 * the gate's own `checks` count hid the hole by matching what it had collected.
 */
private val KEY = Regex("^([A-Za-z0-9_][A-Za-z0-9_.\\-]*):\\s*(.*)$")

/**
 * Reads the exact YAML subset OBDb's test cases use — and nothing else.
 *
 * A real YAML parser would be a JVM-only dependency (`kaml`), which would put the gate
 * test on one platform. The grammar here is tiny and fixed:
 *
 * ```
 * command_id: 7E4.7EC.220101|fc=1
 * test_cases:
 * - expected_values:
 *     EV6_HVBAT_SOC: 55.5
 *   response: |-
 *     7EC103E620101EFFBE7
 *     7EC21EF6F0000000000
 * ```
 *
 * Scalars are returned as the raw text after the colon, with surrounding quotes stripped
 * and nothing else interpreted. That is deliberate: `F150_GEAR: '1'` is the *label* "1"
 * of an enumerated signal while `DTC_CNT: 0` is the number zero, and the only thing that
 * tells them apart is the quoting. Guessing a type here would silently compare a gear
 * label against a number.
 */
fun parseFixtureYaml(text: String): FixtureFile {
    var commandId: String? = null
    val cases = mutableListOf<FixtureCase>()

    var expected: MutableMap<String, String>? = null
    var response: MutableList<String>? = null
    var inExpected = false
    var blockIndent = -1                       // > 0 while inside a `|-` block

    fun closeCase() {
        val e = expected ?: return
        cases += FixtureCase(e, response ?: emptyList())
        expected = null
        response = null
        inExpected = false
        blockIndent = -1
    }

    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        if (raw.isBlank()) { i++; continue }
        val indent = raw.indexOfFirst { !it.isWhitespace() }

        // Inside a block scalar, anything indented past the key is literal content.
        if (blockIndent > 0) {
            if (indent >= blockIndent) {
                response?.add(raw.trim())
                i++
                continue
            }
            blockIndent = -1
        }

        var line = raw.trim()
        if (line.startsWith("- ")) {           // a new list item starts a new test case
            closeCase()
            expected = LinkedHashMap()
            response = mutableListOf()
            line = line.removePrefix("- ").trim()
        }

        val m = KEY.matchEntire(line)
        if (m == null) { i++; continue }
        val key = m.groupValues[1]
        val value = m.groupValues[2].trim()

        when {
            key == "command_id" -> commandId = unquote(value)
            key == "test_cases" -> Unit
            key == "expected_values" -> inExpected = true
            key == "response" -> {
                inExpected = false
                when {
                    // Multi-frame, written as a `|-` block: one frame per line.
                    value.isEmpty() || value.startsWith("|") || value.startsWith(">") ->
                        blockIndent = indent + 1

                    // Multi-frame, written as a double-quoted scalar instead — frames are
                    // separated by a literal `\n` escape and the scalar folds across
                    // physical lines. Same data, third spelling. Reading it as one line
                    // splices every frame into a single 40-byte "frame" that decodes to
                    // nothing, which is what hid 1423 values behind a plausible-looking
                    // empty result.
                    value.startsWith("\"") -> {
                        val scalar = StringBuilder(value)
                        while (!scalar.endsWithClosingQuote() && i + 1 < lines.size) {
                            i++
                            scalar.append(' ').append(lines[i].trim())   // a fold is a space
                        }
                        response?.addAll(unescapeDoubleQuoted(scalar.toString()))
                    }

                    // Single frame, written inline — bare or single-quoted. Most of mode 01.
                    // The quotes are YAML's, not the transcript's: leaving them on makes the
                    // line non-hex and parseElmLine correctly rejects it, which loses the
                    // whole transcript rather than one character.
                    else -> response?.add(unquote(value))
                }
            }
            inExpected -> expected?.put(key, unquote(value))
        }
        i++
    }
    closeCase()

    return FixtureFile(
        commandId = commandId ?: error("fixture has no command_id"),
        cases = cases,
    )
}

/** True once the accumulated scalar has its closing quote, i.e. `"…"`. */
private fun StringBuilder.endsWithClosingQuote(): Boolean =
    length > 1 && this[length - 1] == '"'

/** Strips the quotes, turns the `\n` escapes back into frame boundaries. */
private fun unescapeDoubleQuoted(scalar: String): List<String> =
    scalar.removeSurrounding("\"")
        .split("\\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

private fun unquote(s: String): String =
    if (s.length >= 2 && (s.first() == '\'' || s.first() == '"') && s.last() == s.first()) {
        s.substring(1, s.length - 1)
    } else {
        s
    }
