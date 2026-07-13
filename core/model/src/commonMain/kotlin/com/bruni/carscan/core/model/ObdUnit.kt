package com.bruni.carscan.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The unit a decoded signal is natively expressed in, as declared by OBDb.
 *
 * These are the 81 values of `unitEnum` in the OBDb schema, exactly. This is the
 * unit the value is *stored* in — never the unit it is displayed in. Conversion
 * to the user's preferred unit happens once, at the presentation edge, so that
 * changing a display preference can never corrupt recorded history.
 *
 * A handful of these are not physical quantities at all — [ASCII], [HEX],
 * [OFFON], [ONOFF], [YESNO], [NOYES], [NORMAL], [SCALAR], [UNKNOWN] — and they
 * are what tell the decoder to produce something other than a number.
 */
@Serializable
enum class ObdUnit {
    @SerialName("ampereHours") AMPERE_HOURS,
    @SerialName("amps") AMPS,
    @SerialName("ascii") ASCII,
    @SerialName("bars") BARS,
    @SerialName("celsius") CELSIUS,
    @SerialName("centimeters") CENTIMETERS,
    @SerialName("coulombs") COULOMBS,
    @SerialName("degrees") DEGREES,
    @SerialName("fahrenheit") FAHRENHEIT,
    @SerialName("feet") FEET,
    @SerialName("framesPerSecond") FRAMES_PER_SECOND,
    @SerialName("gallons") GALLONS,
    @SerialName("gallonsPerHour") GALLONS_PER_HOUR,
    @SerialName("gigahertz") GIGAHERTZ,
    @SerialName("gramsPerLiter") GRAMS_PER_LITER,
    @SerialName("gramsPerSecond") GRAMS_PER_SECOND,
    @SerialName("gravity") GRAVITY,
    @SerialName("hertz") HERTZ,
    @SerialName("hex") HEX,
    @SerialName("hours") HOURS,
    @SerialName("inches") INCHES,
    @SerialName("inchPound") INCH_POUND,
    @SerialName("joules") JOULES,
    @SerialName("kelvin") KELVIN,
    @SerialName("kiloampereHours") KILOAMPERE_HOURS,
    @SerialName("kiloamps") KILOAMPS,
    @SerialName("kilogramsPerHour") KILOGRAMS_PER_HOUR,
    @SerialName("kilohertz") KILOHERTZ,
    @SerialName("kilojoules") KILOJOULES,
    @SerialName("kilometers") KILOMETERS,
    @SerialName("kilometersPerHour") KILOMETERS_PER_HOUR,
    @SerialName("kiloohms") KILOOHMS,
    @SerialName("kilopascal") KILOPASCAL,
    @SerialName("kilovolts") KILOVOLTS,
    @SerialName("kilowattHours") KILOWATT_HOURS,
    @SerialName("kilowattHoursPer100Kilometers") KILOWATT_HOURS_PER_100_KILOMETERS,
    @SerialName("kilowattHoursPer100Miles") KILOWATT_HOURS_PER_100_MILES,
    @SerialName("kilowatts") KILOWATTS,
    @SerialName("liters") LITERS,
    @SerialName("litersPerHour") LITERS_PER_HOUR,
    @SerialName("megahertz") MEGAHERTZ,
    @SerialName("megaohms") MEGAOHMS,
    @SerialName("meters") METERS,
    @SerialName("metersPerSecond") METERS_PER_SECOND,
    @SerialName("metersPerSecondSquared") METERS_PER_SECOND_SQUARED,
    @SerialName("microhertz") MICROHERTZ,
    @SerialName("microohms") MICROOHMS,
    @SerialName("miles") MILES,
    @SerialName("milesPerKilowattHour") MILES_PER_KILOWATT_HOUR,
    @SerialName("milesPerHour") MILES_PER_HOUR,
    @SerialName("milliampereHours") MILLIAMPERE_HOURS,
    @SerialName("milliamps") MILLIAMPS,
    @SerialName("milligramsPerDeciliter") MILLIGRAMS_PER_DECILITER,
    @SerialName("milligramsPerStroke") MILLIGRAMS_PER_STROKE,
    @SerialName("millihertz") MILLIHERTZ,
    @SerialName("millimeters") MILLIMETERS,
    @SerialName("milliohms") MILLIOHMS,
    @SerialName("milliseconds") MILLISECONDS,
    @SerialName("millivolts") MILLIVOLTS,
    @SerialName("milliwatts") MILLIWATTS,
    @SerialName("minutes") MINUTES,
    @SerialName("nanohertz") NANOHERTZ,
    @SerialName("newtonMeters") NEWTON_METERS,
    @SerialName("normal") NORMAL,
    @SerialName("noyes") NOYES,
    @SerialName("offon") OFFON,
    @SerialName("ohms") OHMS,
    @SerialName("onoff") ONOFF,
    @SerialName("percent") PERCENT,
    @SerialName("poundFoot") POUND_FOOT,
    @SerialName("psi") PSI,
    @SerialName("radians") RADIANS,
    @SerialName("rpm") RPM,
    @SerialName("scalar") SCALAR,
    @SerialName("seconds") SECONDS,
    @SerialName("terahertz") TERAHERTZ,
    @SerialName("unknown") UNKNOWN,
    @SerialName("volts") VOLTS,
    @SerialName("watts") WATTS,
    @SerialName("yards") YARDS,
    @SerialName("yesno") YESNO,
}

/** Units whose decoded form is a boolean rather than a number. */
val ObdUnit.isBoolean: Boolean
    get() = this == ObdUnit.OFFON || this == ObdUnit.ONOFF ||
        this == ObdUnit.YESNO || this == ObdUnit.NOYES

/** Units whose decoded form is text rather than a number (VIN, ECU name, raw dumps). */
val ObdUnit.isText: Boolean
    get() = this == ObdUnit.ASCII || this == ObdUnit.HEX
