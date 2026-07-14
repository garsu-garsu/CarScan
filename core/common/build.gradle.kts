plugins {
    id("carscan.kmp")
}

kotlin {
    // A desktop JVM target exists solely so Compose UI tests can run headlessly (Skiko).
    // The alternative was Robolectric, which would force the gauge tests out of commonTest
    // and into Android-only source — and these renderers must behave identically on iOS,
    // so their tests belong where iOS can run them too.
    jvm()
}
