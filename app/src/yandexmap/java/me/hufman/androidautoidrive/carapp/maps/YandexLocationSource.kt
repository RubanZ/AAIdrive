package me.hufman.androidautoidrive.carapp.maps

import android.location.Location

/**
 * Adapter between AAIdrive's `CdsLocationProvider` (phone-side car data) and
 * Yandex MapKit's location subscriber model.
 *
 * Phase 2 stub: stores the last location but has no Yandex-side subscribers.
 * Phase 5 wires this into `MapKitFactory.getInstance().createUserLocationLayer(...)`
 * or the custom `LocationManager`/`LocationListener` contract MapKit exposes.
 */
class YandexLocationSource {
	var location: Location? = null
		private set

	fun onLocationUpdate(location: Location) {
		this.location = location
		// TODO(phase-5): forward to Yandex's UserLocationLayer / LocationManager subscribers
	}
}
