package com.bruni.carscan.core.vehicle

/**
 * One candidate file in a vehicle's `signalsets/v3` directory, and the model years it covers.
 *
 * Ported from OBDb's `python/signalsets/loader.py`. The rule is small and the trap in it is
 * large: **exactly one file wins, and it is NOT merged with `default.json`.** A variant file
 * is a complete replacement — treating it as an overlay would resurrect commands the variant
 * deliberately dropped, and keep the default's scaling for signals the variant re-specified.
 */
data class SignalsetVariant(val fileName: String, val from: Int, val to: Int) {

    /** Width of the covered range. Smaller is more specific, and more specific wins. */
    val span: Int get() = to - from

    fun covers(modelYear: Int): Boolean = modelYear in from..to

    companion object {
        const val DEFAULT_FILE_NAME: String = "default.json"

        private val YEAR_RANGE = Regex("""^(\d{4})-(\d{4})$""")

        /** Null for any name that is not a signalset variant (`README.md`, `2020.json`, …). */
        fun parse(fileName: String): SignalsetVariant? {
            if (fileName == DEFAULT_FILE_NAME) {
                return SignalsetVariant(fileName, from = 0, to = 9999)
            }
            val match = YEAR_RANGE.matchEntire(fileName.removeSuffix(".json")) ?: return null
            val (from, to) = match.destructured
            return SignalsetVariant(fileName, from = from.toInt(), to = to.toInt())
        }

        /**
         * The single file that applies to [modelYear]: of the variants whose range contains the
         * year, the most specific one. Falls back to `default.json`, which covers every year.
         * Null only when nothing covers the year at all.
         */
        fun select(fileNames: Collection<String>, modelYear: Int): SignalsetVariant? =
            fileNames.asSequence()
                .mapNotNull(::parse)
                .filter { it.covers(modelYear) }
                // fileName breaks ties so that a malformed repo with two equally specific
                // variants still resolves the same way on every device.
                .minWithOrNull(compareBy({ it.span }, { it.fileName }))
    }
}
