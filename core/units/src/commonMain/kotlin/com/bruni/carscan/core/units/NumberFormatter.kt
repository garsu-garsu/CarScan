package com.bruni.carscan.core.units

/**
 * Formats a number the way the user's locale writes it.
 *
 * `commonMain` has no number formatter — `String.format` is `java.*` and cannot compile for
 * iOS — so every number in the app was being rendered with a hard-coded `'.'`. **Six of the
 * eight shipping locales (ru, de, pl, pt-BR, es, uk) write `13,8`**, so that was wrong for
 * most of the user base, silently.
 *
 * Contract, identical on every platform:
 * - exactly [decimals] fraction digits, padded if necessary (`13.8` at 2 places is `"13.80"`);
 * - **half-up** rounding, ties away from zero (`2.5` → `3`, `-2.5` → `-3`). Both platforms
 *   default to banker's rounding, so both actuals override it. A formatter that truncated
 *   would break [com.bruni.carscan.core.designsystem]'s `GaugeSpec.decimals` contract;
 * - **no grouping separator, ever.** German groups thousands with a dot: a grouped 1726 rpm
 *   reads as `"1.726"`, which is 1.7 to anyone glancing at a gauge.
 *
 * [locale] is a BCP-47 tag (`"de"`, `"pt-BR"`). `null` means the device's locale. An unknown
 * tag falls back to a working formatter rather than throwing.
 *
 * Not thread-safe, and does not need to be: it is created in composition and used from the UI
 * thread. Hold one per locale rather than one per call — the underlying platform formatter is
 * expensive to build and a dashboard formats ~160 numbers a second.
 */
expect class NumberFormatter(locale: String? = null) {
    fun format(value: Double, decimals: Int): String
}
