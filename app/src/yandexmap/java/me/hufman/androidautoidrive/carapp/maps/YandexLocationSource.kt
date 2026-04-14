package me.hufman.androidautoidrive.carapp.maps

import android.os.SystemClock
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.location.Location
import com.yandex.mapkit.location.LocationListener
import com.yandex.mapkit.location.LocationManager
import com.yandex.mapkit.location.LocationStatus
import com.yandex.mapkit.location.SubscriptionSettings
import me.hufman.androidautoidrive.maps.LatLong

/**
 * Bridges AAIdrive's `CdsLocationProvider` (car-sourced `android.location.Location`
 * callbacks) into Yandex MapKit's `LocationManager` contract.
 *
 * Wired via `MapKit.setLocationManager(this)` so that any subsequent
 * `createUserLocationLayer(mapWindow)` uses our CDS-driven updates instead of
 * Yandex's default Android `FusedLocationProvider`. Semantically equivalent to
 * how the gmap flavor uses `GoogleMap.setLocationSource(GMapsLocationSource)`.
 *
 * Thread discipline: `onLocationUpdate` is called from the controller's main
 * looper (same thread MapKit was initialised on), so subscribers are invoked
 * synchronously on the correct thread without extra dispatch.
 */
class YandexLocationSource : LocationManager {
	private val subscribers = mutableListOf<LocationListener>()
	private var lastLocation: Location? = null

	override fun subscribeForLocationUpdates(settings: SubscriptionSettings, listener: LocationListener) {
		synchronized(subscribers) { subscribers += listener }
		// Replay the last known fix so the puck appears immediately instead of
		// waiting for the next car tick.
		lastLocation?.let { listener.onLocationUpdated(it) }
		listener.onLocationStatusUpdated(LocationStatus.AVAILABLE)
	}

	override fun requestSingleUpdate(listener: LocationListener) {
		lastLocation?.let { listener.onLocationUpdated(it) }
	}

	override fun unsubscribe(listener: LocationListener) {
		synchronized(subscribers) { subscribers -= listener }
	}

	override fun suspend() {
		// CdsLocationProvider owns its own lifecycle; nothing to do here.
	}

	override fun resume() {
		// ditto
	}

	/**
	 * Called by the controller on every `CdsLocationProvider` callback — translates
	 * the `android.location.Location` into a Yandex `Location` and broadcasts to
	 * every current subscriber (typically a single `UserLocationLayer`).
	 */
	/** Read the last-known fix as a shared-type [LatLong] (nullable before any fix). */
	fun latestLatLong(): LatLong? {
		val loc = lastLocation ?: return null
		val point = loc.position ?: return null
		return LatLong(point.latitude, point.longitude)
	}

	fun onLocationUpdate(androidLocation: android.location.Location) {
		val yandexLocation = Location(
				Point(androidLocation.latitude, androidLocation.longitude),
				if (androidLocation.hasAccuracy()) androidLocation.accuracy.toDouble() else null,
				if (androidLocation.hasAltitude()) androidLocation.altitude else null,
				null,
				if (androidLocation.hasBearing()) androidLocation.bearing.toDouble() else null,
				if (androidLocation.hasSpeed()) androidLocation.speed.toDouble() else null,
				null,
				androidLocation.time,
				SystemClock.elapsedRealtime()
		)
		this.lastLocation = yandexLocation
		val snapshot = synchronized(subscribers) { subscribers.toList() }
		snapshot.forEach { it.onLocationUpdated(yandexLocation) }
	}
}
