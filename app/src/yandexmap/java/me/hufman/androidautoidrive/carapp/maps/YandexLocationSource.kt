package me.hufman.androidautoidrive.carapp.maps

import me.hufman.androidautoidrive.maps.LatLong

/**
 * Plain-Kotlin holder for the most-recent CDS-sourced location fix.
 *
 * **Why this exists (and why it's NOT a Yandex `LocationManager` anymore):**
 * Yandex MapKit 4.x's `MapKitFactory.setLocationManager(LocationManager)` is a
 * JNI bridge that requires the supplied object to carry a private
 * `nativeObject` field of type `com.yandex.runtime.NativeObject` — the Yandex
 * native code uses it to attach a peer handle. A pure-Java implementation of
 * the `LocationManager` interface fails at the first JNI invocation with
 *
 *   java.lang.NoSuchFieldError: no "Lcom/yandex/runtime/NativeObject;"
 *   field "nativeObject" in class ".../YandexLocationSource"
 *
 * Yandex does not publish a `BaseLocationManager` we could subclass to inherit
 * the native field, so the supported approach is to skip `setLocationManager`
 * entirely and draw the user-location puck ourselves as a
 * [com.yandex.mapkit.map.PlacemarkMapObject] — see [YandexMapsProjection.updateUserLocation].
 *
 * This class therefore holds the raw CDS values and exposes them to the
 * projection on every tick. No Yandex MapKit types are imported here so the
 * unit-test classpath can construct it without loading `libmaps-mobile.so`.
 */
class YandexLocationSource {

	@Volatile private var lastLatitude: Double? = null
	@Volatile private var lastLongitude: Double? = null
	@Volatile private var lastBearing: Float? = null
	@Volatile private var lastAccuracyMeters: Float? = null

	/** Latest fix as a shared-type [LatLong], or `null` before any tick. */
	fun latestLatLong(): LatLong? {
		val lat = lastLatitude ?: return null
		val lon = lastLongitude ?: return null
		return LatLong(lat, lon)
	}

	/** Latest CDS bearing in degrees, or `null` if the fix had no bearing. */
	fun latestBearing(): Float? = lastBearing

	/** Latest CDS accuracy in meters, or `null` if the fix had no accuracy. */
	fun latestAccuracyMeters(): Float? = lastAccuracyMeters

	/**
	 * Called by [YandexMapsController] on every `CdsLocationProvider` callback.
	 * Pure data-cache update; the projection reads back via the `latest*`
	 * accessors when it draws the puck on the next frame.
	 */
	fun onLocationUpdate(androidLocation: android.location.Location) {
		this.lastLatitude = androidLocation.latitude
		this.lastLongitude = androidLocation.longitude
		this.lastBearing = if (androidLocation.hasBearing()) androidLocation.bearing else null
		this.lastAccuracyMeters = if (androidLocation.hasAccuracy()) androidLocation.accuracy else null
	}
}
