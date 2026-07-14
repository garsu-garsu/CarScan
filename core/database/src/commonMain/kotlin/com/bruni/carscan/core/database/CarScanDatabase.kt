package com.bruni.carscan.core.database

import app.cash.sqldelight.db.SqlDriver
import com.bruni.carscan.db.CarScanDb
import com.bruni.carscan.db.Trip_gps
import com.bruni.carscan.db.Trip_series

/**
 * The one place the column adapters are bound. Constructing [CarScanDb] directly
 * would compile but leave the BLOB columns wired to whatever adapter the caller
 * happened to pass, which is exactly the kind of thing that is only noticed after a
 * user's trip history has already been written with it.
 */
fun createDatabase(driver: SqlDriver): CarScanDb = CarScanDb(
    driver = driver,
    trip_gpsAdapter = Trip_gps.Adapter(
        latAdapter = DoubleArrayColumnAdapter,
        lonAdapter = DoubleArrayColumnAdapter,
        altAdapter = FloatArrayColumnAdapter,
        speedAdapter = FloatArrayColumnAdapter,
        bearingAdapter = FloatArrayColumnAdapter,
    ),
    trip_seriesAdapter = Trip_series.Adapter(
        seriesAdapter = FloatArrayColumnAdapter,
    ),
)
