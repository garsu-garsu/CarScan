@file:OptIn(ExperimentalEncodingApi::class)

package com.bruni.carscan.core.data.backup

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The stored BLOBs travel through the JSON records as base64 — the stdlib's, not a hand-rolled
 * one. It costs a third more bytes than raw binary would, and buys a format that is one text
 * document per record instead of a second length-prefixed container inside the first.
 */
internal fun base64Encode(bytes: ByteArray): String = Base64.encode(bytes)

internal fun base64Decode(text: String): ByteArray = Base64.decode(text)
