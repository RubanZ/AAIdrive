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
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.MutableAppSettingsObserver
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
 *  - Driving routing via [YandexMapsNavController]: [navigateTo] builds a route
 *    with the Yandex Directions SDK and the projection draws the polyline.
 *    Enabled under the project's Commercial-tier license (DAU<1000 free) —
 *    see `.planning/DECISIONS.md` → "2026-04-15 — Routing unblocked".
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
		private val appSettings: MutableAppSettingsObserver,
		private val mapAppMode: MapAppMode
) : MapInteractionController {

	companion object {
		private const val TAG = "YandexMapsController"
		private const val SHUTDOWN_WAIT_INTERVAL = 120_000L
		// Animation timings mirror gmap (`GMapsController.animateNavigation`):
		// snappy 500ms camera moves for normal zoom, 4s for nav fly-to.
		private val SMOOTH_ANIM = Animation(Animation.Type.SMOOTH, 0.5f)
		// After a manual zoomIn/zoomOut call, suspend speed-adaptive auto-zoom
		// for this long so the user's explicit choice stays visible. Mirrors
		// the Google Maps "user override" pattern.
		private const val MANUAL_ZOOM_OVERRIDE_MS = 20_000L
		// Distance-threshold rerouting. When the live puck drifts further than
		// this from the origin the current route was built from, we rebuild the
		// route from the new position so the polyline stays anchored "from here
		// to destination" instead of slowly drifting behind the user. 150 m is
		// short enough to feel responsive on urban streets (reroutes every ~15 s
		// at city speed) and long enough to avoid hammering the routing backend
		// with one request per GPS tick on the highway.
		private const val REROUTE_MIN_DISTANCE_METERS = 150f
	}

	private val handler = Handler(context.mainLooper)
	private var projection: YandexMapsProjection? = null
	private val yandexLocationSource = YandexLocationSource()

	// Navigation / routing. The nav controller is lazy because
	// `DirectionsFactory.getInstance()` touches native code and must run on
	// the MapKit main thread — the same thread `init {}` runs on when built
	// from `MapAppService.onCarStart` via the main-looper post in
	// `MapController.onCarStart`'s handler. We still wrap in a lazy-by-lambda
	// so any early instantiation path (tests, future call sites) doesn't
	// trigger a JNI crash before MapKit is initialized.
	val navController: YandexMapsNavController by lazy {
		YandexMapsNavController.getInstance(carLocationProvider) {
			// Route callback fires on the main thread; hop through `handler`
			// anyway to normalize with the rest of the controller's reentrancy
			// model and play nice with unit tests that swap the looper.
			handler.post { drawNavigation() }
			mapAppMode.currentNavDestination = it.currentNavDestination
		}
	}

	private var currentLocation: Location? = null
	private var currentZoom = 15f
	private val startZoom = 6f
	private var hasInitializedCamera = false
	// Epoch ms after which speed-adaptive auto-zoom resumes. Bumped on every
	// manual zoomIn/zoomOut so the user's pinch stays respected.
	private var manualZoomUntilMs = 0L
	private var lastSettingsTime = 0L
	// 5 minutes between automatic day/night re-checks, mirrors gmap's interval
	// (GMapsController.SETTINGS_TIME_INTERVAL = 5 * 60000)
	private val settingsIntervalMs = 5 * 60_000L

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
				// If we already have a CDS fix from a previous showing, push it
				// straight into the projection so the puck appears on frame 1.
				yandexLocationSource.latestLatLong()?.let { latLong ->
					p.updateUserLocation(
							latLong,
							yandexLocationSource.latestBearing(),
							yandexLocationSource.latestAccuracyMeters()
					)
				}
				// Restore the route polyline if navigation is still active from
				// a previous showing — matches gmap's `drawNavigation()` call in
				// its `mapListener` for the same "projection was torn down during
				// idle, user came back, keep their route visible" case.
				drawNavigation()
			}
			// Tap on the puck cycles to the next puck style — the same setting
			// the phone-side fragment exposes via radio buttons. Persist via
			// MutableAppSettingsObserver so AppSettingsObserver.callback fires
			// and applySettings picks up ChangedField.PUCK_STYLE.
			p.onPuckTapped = {
				val current = YandexPuckStyle.fromStorageKey(appSettings[AppSettings.KEYS.MAP_PUCK_STYLE])
				val next = current.next()
				Log.i(TAG, "Puck tapped — cycling style ${current.storageKey} → ${next.storageKey}")
				appSettings[AppSettings.KEYS.MAP_PUCK_STYLE] = next.storageKey
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
		val firstFix = currentLocation == null
		currentLocation = location
		yandexLocationSource.onLocationUpdate(location)
		// Feed speed to MapAppMode's motion-adaptive capture config so
		// compressQuality sheds JPEG bits when we're moving, letting the
		// encode/send loop keep up with the framerate without resizing the
		// producer Surface.
		mapAppMode.updateMotionSpeed(
				if (location.hasSpeed()) location.speed * 3.6f else null
		)
		// Push the puck before camera moves so the projection always renders
		// consistent state — placemark + viewport in the same place on every
		// frame, no flicker between the camera fly-in and the first puck draw.
		projection?.updateUserLocation(
				LatLong(location.latitude, location.longitude),
				if (location.hasBearing()) location.bearing else null,
				if (location.hasAccuracy()) location.accuracy else null
		)
		if (firstFix) {
			// First location fix — initialise the camera before applying updates
			// so the puck and viewport land in the same place on the first frame.
			initCamera()
			// Re-apply settings so day/night detection has a coordinate to work with.
			projection?.applySettings()
			lastSettingsTime = System.currentTimeMillis()
			// Still run the reroute check on the first fix — it catches the
			// race where `navigateTo` arrived before any CDS data was flowing
			// and the nav controller stashed the destination without firing a
			// request. Now that we have a live location, build the route.
			maybeReroute(location)
			return
		}
		updateCamera()
		// After the puck/camera catch up, decide whether the active route is
		// far enough from the live position that we need to rebuild it. Cheap
		// no-op when navigation isn't active.
		maybeReroute(location)
		// Periodic day/night re-check
		val now = System.currentTimeMillis()
		if (now - lastSettingsTime > settingsIntervalMs) {
			projection?.applySettings()
			lastSettingsTime = now
		}
	}

	/**
	 * Distance-threshold reroute loop. Runs on every location tick while
	 * navigation is active; rebuilds the Yandex route when either:
	 *  - we have a destination but no route yet (first-fix-after-navigate race,
	 *    or a transient routing error has left us with no polyline), or
	 *  - the puck has drifted at least [REROUTE_MIN_DISTANCE_METERS] from the
	 *    origin the current route was built from — so the polyline stays
	 *    anchored at "from here to dest" instead of slowly trailing behind.
	 *
	 * `NavController.navigateTo` is idempotent w.r.t. `currentNavDestination`,
	 * so calling it repeatedly with the same destination is the right API.
	 * Each call cancels any in-flight `DrivingSession` before issuing a fresh
	 * `requestRoutes`, so we never pile up pending requests.
	 */
	private fun maybeReroute(location: Location) {
		val dest = navController.currentNavDestination ?: return
		val origin = navController.lastRouteOrigin
		val shouldRebuild = when {
			origin == null -> true  // destination set but no successful build yet
			navController.currentNavRoute == null -> true  // last build errored out
			else -> {
				val originLocation = Location("reroute").apply {
					latitude = origin.latitude
					longitude = origin.longitude
				}
				location.distanceTo(originLocation) >= REROUTE_MIN_DISTANCE_METERS
			}
		}
		if (shouldRebuild) {
			Log.d(TAG, "Rerouting: origin=${navController.lastRouteOrigin} current=(${"%.6f".format(location.latitude)},${"%.6f".format(location.longitude)}) dest=$dest")
			navController.navigateTo(dest)
		}
	}

	private fun initCamera() {
		if (hasInitializedCamera) return
		val map = projection?.map ?: return
		val loc = currentLocation
		val target = if (loc != null) Point(loc.latitude, loc.longitude) else map.cameraPosition.target
		val (azimuth, tilt) = cameraAzimuthAndTilt(loc)
		mapAppMode.startInteraction()
		map.move(
				CameraPosition(target, startZoom, azimuth, tilt),
				SMOOTH_ANIM,
				null
		)
		hasInitializedCamera = true
	}

	private fun updateCamera() {
		val map = projection?.map ?: return
		val loc = currentLocation ?: return
		val target = Point(loc.latitude, loc.longitude)
		val (azimuth, tilt) = cameraAzimuthAndTilt(loc)
		currentZoom = autoZoom(loc, currentZoom)
		map.move(
				CameraPosition(target, currentZoom, azimuth, tilt),
				SMOOTH_ANIM,
				null
		)
	}

	/**
	 * Speed-adaptive zoom in heading-up mode. Returns the current zoom
	 * unchanged unless:
	 *  - `MAP_TILT` is on (navigator mode),
	 *  - the location reports a speed (`hasSpeed`),
	 *  - the user hasn't manually zoomed in the last [MANUAL_ZOOM_OVERRIDE_MS].
	 *
	 * Uses a piecewise table with built-in hysteresis so the map doesn't
	 * oscillate when the speed hovers around a band edge (GPS noise of a few
	 * km/h is common). Each band has a wider "stay here" region than its
	 * neighbours' "enter here" threshold.
	 */
	private fun autoZoom(loc: Location, current: Float): Float {
		val enabled = appSettings[AppSettings.KEYS.MAP_TILT].toBoolean()
		if (!enabled || !loc.hasSpeed()) return current
		if (System.currentTimeMillis() < manualZoomUntilMs) return current
		val speedKmh = loc.speed * 3.6f
		Log.d(TAG, "autoZoom: loc.speed=${loc.speed} m/s, speedKmh=$speedKmh, currentZoom=$current")
		// Dead zone: GPS noise can report 1-3 km/h while stationary — don't
		// change zoom on phantom speed.
		if (speedKmh < 5f) return current
		val rounded = current.toInt()
		val result = when {
			rounded >= 17 -> if (speedKmh > 40f) 16f else 17f
			rounded == 16 -> when {
				speedKmh > 70f -> 15f
				speedKmh < 30f -> 17f
				else           -> 16f
			}
		Log.d(TAG, "autoZoom: result=$result (was $current)")
		return result
	}

	// Heading-up camera mode. When MAP_TILT is enabled and we have a live
	// bearing, rotate the map so the direction of travel is at the top of the
	// screen (and add a small 3D tilt for the "navigator" feel). The puck's
	// `setDirection(bearing)` stays as-is — with RotationType.ROTATE the icon's
	// on-screen rotation is `direction - azimuth`, so setting both to `bearing`
	// freezes the arrow pointing straight up while the map rotates under it.
	private fun cameraAzimuthAndTilt(loc: Location?): Pair<Float, Float> {
		val headingUp = appSettings[AppSettings.KEYS.MAP_TILT].toBoolean()
		if (!headingUp || loc == null || !loc.hasBearing()) {
			return 0f to 0f
		}
		return normalizeBearing(loc.bearing) to 35f
	}

	private fun normalizeBearing(raw: Float): Float {
		val mod = raw.rem(360f)
		return if (mod < 0f) mod + 360f else mod
	}

	override fun zoomIn(steps: Int) {
		Log.i(TAG, "Zooming map in $steps steps")
		mapAppMode.startInteraction()
		currentZoom = min(18f, currentZoom + steps)
		manualZoomUntilMs = System.currentTimeMillis() + MANUAL_ZOOM_OVERRIDE_MS
		updateCamera()
	}

	override fun zoomOut(steps: Int) {
		Log.i(TAG, "Zooming map out $steps steps")
		mapAppMode.startInteraction()
		currentZoom = max(0f, currentZoom - steps)
		manualZoomUntilMs = System.currentTimeMillis() + MANUAL_ZOOM_OVERRIDE_MS
		updateCamera()
	}

	// -----------------------------------------------------------------------------------
	// Routing — enabled under Commercial tier (DAU<1000). See .planning/DECISIONS.md
	// "2026-04-15 — Routing unblocked under Commercial tier" for the licensing rationale.
	// Mirrors GMapsController.navigateTo / drawNavigation / recalcNavigation / stopNavigation.
	// -----------------------------------------------------------------------------------

	override fun navigateTo(dest: LatLong) {
		Log.i(TAG, "Beginning Yandex navigation to $dest")
		mapAppMode.startInteraction()
		// Clear any stale route drawing immediately so the user doesn't briefly
		// see the previous polyline while the new route is being computed.
		projection?.drawRoute(null, null)
		navController.navigateTo(dest)
	}

	override fun recalcNavigation() {
		navController.currentNavDestination?.let { dest ->
			Log.i(TAG, "Recalculating Yandex navigation to $dest")
			navController.navigateTo(dest)
		}
	}

	override fun stopNavigation() {
		Log.i(TAG, "Stopping Yandex navigation")
		navController.stopNavigation()
	}

	/**
	 * Drawn in response to [YandexMapsNavController]'s callback. Reads the
	 * current route (or null) off the nav controller and pushes it into the
	 * projection. Mirrors `GMapsController.drawNavigation` — same pattern, the
	 * callback is the single source of truth for "what's currently drawn".
	 */
	private fun drawNavigation() {
		val route = navController.currentNavRoute
		val jams = navController.currentNavJams
		projection?.drawRoute(route, jams)
	}
}
