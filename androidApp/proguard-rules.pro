# R8 rules for the release build.
#
# Only reflection-driven things need to be here. SQLDelight (generated code), Compose, Kable and
# the coroutines runtime resolve everything statically and need nothing. AdMob, Play Billing,
# Play Services and Ktor ship their own consumer rules in their AARs/JARs.

# --- kotlinx.serialization ---------------------------------------------------------------------
# The compiler plugin resolves most serializers statically, but a @Serializable class's generated
# Companion.serializer() is only ever reached reflectively for polymorphic and nav-route lookups.
# Without this the lookup throws SerializationException at runtime — and only at runtime, which is
# why a green release build proves nothing here.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <1>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# The app's own serializable models — nav routes, OBDb signalset DTOs, dashboard layout, settings.
# Their field *names* are the JSON/back-stack keys, so renaming them silently changes the wire
# format: bundled OBDb assets stop parsing and saved dashboards come back empty.
-keepclassmembers,allowobfuscation class com.bruni.carscan.core.model.** { *; }
-keepclassmembers,allowobfuscation class com.bruni.carscan.core.data.** { *; }
-keepclassmembers,allowobfuscation class com.bruni.carscan.core.vehicle.** { *; }
-keepclassmembers,allowobfuscation class com.bruni.carscan.feature.dashboard.** { *; }

# --- Navigation Compose type-safe routes -------------------------------------------------------
# Route is a @Serializable sealed interface; the NavHost matches destinations by the serial name,
# which defaults to the fully-qualified class name. Obfuscate it and every navigate() misses.
-keep class com.bruni.carscan.nav.Route { *; }
-keep class com.bruni.carscan.nav.Route$* { *; }

# --- Room, via WorkManager, via AdMob ----------------------------------------------------------
# This app stores nothing in Room — it uses SQLDelight. Room arrives transitively:
# play-services-ads-api pulls androidx.work:work-runtime, whose WorkDatabase is a Room database,
# and androidx.startup instantiates it in a ContentProvider before any of our code runs.
#
# Room finds its generated implementation by name — Class.forName(canonicalName + "_Impl") — so a
# renamed or shrunk WorkDatabase_Impl is invisible to it. Without this the release build dies at
# launch, every time, on "Failed to create an instance of androidx.work.impl.WorkDatabase", before
# a single screen draws. Found on a device; no host test can see it.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# --- Koin --------------------------------------------------------------------------------------
# Constructor DSL resolves by KClass, so the classes it constructs must keep their identity as
# ViewModels. Everything else in Koin is plain lambdas.
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }

# --- Noise -------------------------------------------------------------------------------------
# Kable annotates ScanResultAndroidAdvertisement @Parcelize without depending on the parcelize
# runtime, so the annotation class is genuinely absent. ART ignores annotations it cannot resolve,
# and nothing here ever parcels a scan result — this is a missing annotation, not a missing type.
-dontwarn kotlinx.parcelize.Parcelize

# Ktor and OkHttp reference optional JVM-only APIs that are absent on Android; R8 warns about each.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn kotlinx.coroutines.debug.**
