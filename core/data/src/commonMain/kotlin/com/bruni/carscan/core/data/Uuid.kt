package com.bruni.carscan.core.data

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A random (version 4) UUID.
 *
 * Every id the user can own — trip, vehicle, dashboard layout — is one of these
 * rather than an autoincrement integer, because backups are restored onto *other
 * devices*. Autoincrement keys collide by construction: the phone being restored to
 * already has a trip 1. With a UUID, importing the same backup twice lands on the
 * same row twice, so it can be made idempotent; with an integer key it cannot be,
 * at any layer above.
 */
@OptIn(ExperimentalUuidApi::class)
fun newUuid(): String = Uuid.random().toString()
