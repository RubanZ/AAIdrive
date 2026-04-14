package me.hufman.androidautoidrive.carapp.maps

import android.content.Context
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.os.Handler
import android.util.Log
import com.yandex.mapkit.Animation
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.Map
import me.hufman.androidautoidrive.AppSettingsObserver
import me.hufman.androidautoidrive.maps.CarLocationProvider
import me.hufman.androidautoidrive.maps.LatLong
import kotlin.math.max
import kotlin.math.min

/**
 * Yandex MapKit implementation of `MapInteractionController`.
 *
 * Responsibilities (Phase 4):
 *  - Own the `YandexMapsProjection` lifecycle (lazy-create on `showMap`,
 *    inactivity shutdown on `pauseMap`)
 *  - Translate AAIdrive camera commands into `map.move(CameraPosition, Animation, CameraCallback)` calls
 *  - Relay CDS-sourced location fixes into `YandexLocationSource` (which is installed
 *    as MapKit's process-wide `LocationManager` during `YandexMapsProjection.onCreate`)
 *  - Re-clamp zoom to `[0f..18f]`, mirror gmap's SHUTDOWN_WAIT_INTERVAL of 120_000 ms
 *
 * Routing/TbT is out of scope for this milestone — see `.planning/DECISIONS.md`.
 * The `navigateTo` / `recalcNavigation` / `stopNavigation` overrides warn-log and
 * no-op so the shared `MapInteractionController` contract stays intact without
 * violating Yandex MapKit Full's free-tier ToS prohibition on turn-by-turn.
 *
 * All MapKit calls are marshalled through a `Handler(context.mainLooper)` instance.
 * This is the same thread `MapKitFactory.initialize` ran on, and is the thread
 * `YandexMapsProjection.onCreate` / `onStart` / `onStop` execute on because
 * `Presentation` dispatches its own lifecycle on the thread that called `show()`.
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
		private const val SHUTDOWN_WAIT_INTERVAL = 120_000L
		// Animation timings mirror gmap (`GMapsController.animateNavigation`):
		// snappy 500ms camera moves for normal zoom, 4s for nav fly-to.
		private val SMOOTH_ANIM = Animation(Animation.Type.SMOOTH, 0.5f)
	}

	private val handler = Handler(context.mainLooper)
	private var projection: YandexMapsProjection? = null
	private val yandexLocationSource = YandexLocationSource()

	private var currentLocation: Location? = null
	private var currentZoom = 15f
	private val startZoom = 6f
	private var hasInitializedCamera = false

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
				// onCreate has produced a usable Map — point the camera at the
				// current (or default) location before the first frame renders.
				initCamera()
			}
		}
		if (projection?.isShowing == false) {
			projection?.show()
		}
		// Nudge the camera one zoom-step null-op to force a redraw in case the
		// projection was already showing and we just toggled windows. Yandex's
		// `Map.move(CameraPosition)` with the same position is safe to call.
		projection?.map?.let { map ->
			map.move(map.cameraPosition)
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
		hasInitializedCamera = false
	}

	private fun onLocationUpdate(location: Location) {
		if (currentLocation == null) {
			// First location fix — initialise the camera before applying updates
			// so the puck and viewport land in the same place on the first frame.
			currentLocation = location
			yandexLocationSource.onLocationUpdate(location)
			initCamera()
			return
		}
		currentLocation = location
		yandexLocationSource.onLocationUpdate(location)
		updateCamera()
	}

	private fun initCamera() {
		if (hasInitializedCamera) return
		val map = projection?.map ?: return
		val loc = currentLocation
		val target = if (loc != null) Point(loc.latitude, loc.longitude) else map.cameraPosition.target
		mapAppMode.startInteraction()
		map.move(
				CameraPosition(target, startZoom, 0f, 0f),
				SMOOTH_ANIM,
				null
		)
		hasInitializedCamera = true
	}

	private fun updateCamera() {
		val map = projection?.map ?: return
		val loc = currentLocation ?: return
		val target = Point(loc.latitude, loc.longitude)
		map.move(
				CameraPosition(target, currentZoom, 0f, 0f),
				SMOOTH_ANIM,
				null
		)
	}

	override fun zoomIn(steps: Int) {
		Log.i(TAG, "Zooming map in $steps steps")
		mapAppMode.startInteraction()
		currentZoom = min(18f, currentZoom + steps)
		updateCamera()
	}

	override fun zoomOut(steps: Int) {
		Log.i(TAG, "Zooming map out $steps steps")
		mapAppMode.startInteraction()
		currentZoom = max(0f, currentZoom - steps)
		updateCamera()
	}

	// -----------------------------------------------------------------------------------
	// Routing stubs — deliberately no-op (see .planning/DECISIONS.md for the rationale).
	// -----------------------------------------------------------------------------------

	override fun navigateTo(dest: LatLong) {
		Log.w(TAG, "Routing not available on the yandexmap flavor: ignoring navigateTo($dest)")
	}

	override fun recalcNavigation() {
		Log.w(TAG, "Routing not available on the yandexmap flavor: ignoring recalcNavigation()")
	}

	override fun stopNavigation() {
		Log.w(TAG, "Routing not available on the yandexmap flavor: ignoring stopNavigation()")
	}
}
