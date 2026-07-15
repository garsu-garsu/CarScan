package com.bruni.carscan.di

import org.koin.core.module.Module

/**
 * The bindings that cannot exist in common code: the SQLite driver, the preferences file, and the
 * set of transports this platform actually has.
 *
 * That last one is why `ObdConnector.supported` exists. iOS has no Bluetooth Classic — Apple's
 * ExternalAccessory framework reaches only MFi-certified hardware and no ELM327 clone is MFi — so
 * the iOS actual supplies no SPP factory, the connect screen therefore has no SPP section, and no
 * screen anywhere in the app has to know which platform it is running on.
 */
expect fun platformModule(): Module
