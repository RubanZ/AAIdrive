package me.hufman.androidautoidrive.carapp.maps

import android.content.Context
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.os.Handler
import android.util.Log
import me.hufman.androidautoidrive.AppSettingsObserver
import me.hufman.androidautoidrive.maps.CarLocationProvider
import me.hufman.androidautoidrive.maps.LatLong

/**
 * Yandex MapKit implementation of `MapInteractionController`.
 *
 * Phase 2 stub: wires the lifecycle shape (show/pause/zoom) but no camera
 * actually moves yet. Phase 4 fills in:
 *  - `projection.map.move(CameraPosition, Animation, Callback)` for camera updates
 *  - `YandexLocationSource` → `UserLocationLayer` bridging for the blue-dot
 *  - Inactivity `shutdownMapRunnable` paired with `MapKitFactory.onStop()`
 *
 * Routing/TbT stubs: per `.planning/DECISIONS.md` and PROJECT.md Out of Scope,
 * the Yandex MapKit Full free tier forbids turn-by-turn navigation. `navigateTo`,
 * `recalcNavigation`, and `stopNavigation` log a warning and no-op so the
 * shared `MapInteractionController` contract stays intact without violating ToS.
 */
class YandexMapsController(
		private val context: Context,
		private val carLocationProvider: CarLocationProvider,
		private val virtualDisplay: VirtualDisplay,
		private val appSettings: AppSettingsObserver,
		private val mapAppMode: MapAppMode
) : MapInteractionController {

	companion object {
		private const val TAG = "YandexMapsController"
		private const val SHUTDOWN_WAIT_INTERVAL = 120000L
	}

	private val handler = Handler(context.mainLooper)
	private var projection: YandexMapsProjection? = null
	private val yandexLocationSource = YandexLocationSource()
	private var currentLocation: Location? = null
	private var currentZoom = 15f
	private val startZoom = 6f

	init {
		carLocationProvider.callback = { location ->
			handler.post { onLocationUpdate(location) }
		}
	}

	override fun showMap() {
		Log.i(TAG, "Beginning Yandex map projection")
		handler.removeCallbacks(shutdownMapRunnable)

		if (projection == null) {
			Log.i(TAG, "First showing of the map")
			val p = YandexMapsProjection(context, virtualDisplay.display, appSettings, yandexLocationSource)
			this.projection = p
			p.mapListener = Runnable {
				// TODO(phase-4): initCamera(), restore navigation state
			}
		}
		if (projection?.isShowing == false) {
			projection?.show()
		}
		carLocationProvider.start()
	}

	override fun pauseMap() {
		carLocationProvider.stop()
		handler.postDelayed(shutdownMapRunnable, SHUTDOWN_WAIT_INTERVAL)
	}

	private val shutdownMapRunnable = Runnable {
		Log.i(TAG, "Shutting down YandexMapsProjection due to inactivity of ${SHUTDOWN_WAIT_INTERVAL}ms")
		projection?.hide()
		projection = null
	}

	private fun onLocationUpdate(location: Location) {
		currentLocation = location
		yandexLocationSource.onLocationUpdate(location)
		// TODO(phase-4): camera updateCamera(), day/night re-apply
	}

	override fun zoomIn(steps: Int) {
		Log.i(TAG, "Zooming map in $steps steps")
		mapAppMode.startInteraction()
		currentZoom = kotlin.math.min(18f, currentZoom + steps)
		// TODO(phase-4): updateCamera() with the new zoom
	}

	override fun zoomOut(steps: Int) {
		Log.i(TAG, "Zooming map out $steps steps")
		mapAppMode.startInteraction()
		currentZoom = kotlin.math.max(0f, currentZoom - steps)
		// TODO(phase-4): updateCamera() with the new zoom
	}

	override fun navigateTo(dest: LatLong) {
		// Routing dropped from milestone — Yandex MapKit Full free tier forbids
		// turn-by-turn navigation (see .planning/DECISIONS.md and PITFALLS C3).
		Log.w(TAG, "Routing not available on the yandexmap flavor: ignoring navigateTo($dest)")
	}

	override fun recalcNavigation() {
		Log.w(TAG, "Routing not available on the yandexmap flavor: ignoring recalcNavigation()")
	}

	override fun stopNavigation() {
		Log.w(TAG, "Routing not available on the yandexmap flavor: ignoring stopNavigation()")
	}
}
