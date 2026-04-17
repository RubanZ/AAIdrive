package me.hufman.androidautoidrive.carapp.maps

import android.util.Log
import com.yandex.mapkit.RequestPoint
import com.yandex.mapkit.RequestPointType
import com.yandex.mapkit.directions.DirectionsFactory
import com.yandex.mapkit.directions.driving.DrivingOptions
import com.yandex.mapkit.directions.driving.DrivingRoute
import com.yandex.mapkit.directions.driving.DrivingRouter
import com.yandex.mapkit.directions.driving.DrivingRouterType
import com.yandex.mapkit.directions.driving.DrivingSession
import com.yandex.mapkit.directions.driving.VehicleOptions
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.navigation.JamSegment
import com.yandex.runtime.Error
import me.hufman.androidautoidrive.maps.CarLocationProvider
import me.hufman.androidautoidrive.maps.LatLong

/**
 * Yandex MapKit implementation of the routing controller. Mirrors
 * [me.hufman.androidautoidrive.carapp.maps.GMapsNavController] — same lifecycle,
 * same callback shape, so [YandexMapsController] can drive it with the exact
 * pattern gmap uses.
 *
 * Licensing note: Yandex MapKit Full free-tier ToS prohibits "navigation along
 * a route". This class is enabled only because the project is licensed under
 * the Commercial tier with DAU below the free threshold (<1000), which lifts
 * that prohibition. See `.planning/DECISIONS.md` → "2026-04-15 — Routing
 * unblocked under Commercial tier".
 *
 * Thread discipline: all MapKit calls must run on the thread that ran
 * `MapKitFactory.initialize(...)` — the main looper. Route requests issued via
 * `DrivingRouter.requestRoutes` fire their `DrivingRouteListener` back on that
 * same thread, so the callback we invoke runs on main and the controller can
 * touch `projection.map` without extra hops.
 */
class YandexMapsNavController(
		private val drivingRouter: DrivingRouter,
		private val locationProvider: CarLocationProvider,
		private val callback: (YandexMapsNavController) -> Unit
) {
	companion object {
		private const val TAG = "YandexMapsNav"

		fun getInstance(
				locationProvider: CarLocationProvider,
				callback: (YandexMapsNavController) -> Unit
		): YandexMapsNavController {
			val router = DirectionsFactory.getInstance()
					.createDrivingRouter(DrivingRouterType.COMBINED)
			return YandexMapsNavController(router, locationProvider, callback)
		}
	}

	var currentNavDestination: LatLong? = null
		private set
	var currentNavRoute: List<Point>? = null
		private set
	/**
	 * Per-segment jam classification for [currentNavRoute]. Same length as
	 * `currentNavRoute.size - 1` when populated (one entry per segment between
	 * consecutive route points). Empty or all-UNKNOWN when traffic data isn't
	 * available for the route region. Consumed by [YandexMapsProjection.drawRoute]
	 * to colour the polyline segment-by-segment via `setStrokeColors`.
	 */
	var currentNavJams: List<JamSegment>? = null
		private set
	/**
	 * LatLong from which [currentNavRoute] was last successfully built. The
	 * controller compares live GPS ticks against this point to decide when
	 * the route has gone stale enough to rebuild (distance-threshold reroute).
	 */
	var lastRouteOrigin: LatLong? = null
		private set

	private var pendingSession: DrivingSession? = null

	private val routeListener = object : DrivingSession.DrivingRouteListener {
		override fun onDrivingRoutes(routes: MutableList<DrivingRoute>) {
			pendingSession = null
			if (routes.isEmpty()) {
				Log.w(TAG, "Yandex returned zero routes for destination $currentNavDestination")
				currentNavRoute = null
				currentNavJams = null
			} else {
				val route = routes[0]
				val pts = route.geometry.points.toList()
				val jams = runCatching { route.jamSegments }.getOrNull()
				Log.i(TAG, "Received ${routes.size} route(s); using first with ${pts.size} points, ${jams?.size ?: 0} jam segments")
				currentNavRoute = pts
				currentNavJams = jams
			}
			callback(this@YandexMapsNavController)
		}

		override fun onDrivingRoutesError(error: Error) {
			pendingSession = null
			Log.w(TAG, "Yandex routing error: $error")
			// Intentionally keep the last-good route + jams on error — a
			// transient routing failure shouldn't nuke the user's visible
			// polyline. Only stopNavigation clears it explicitly.
			callback(this@YandexMapsNavController)
		}
	}

	fun navigateTo(dest: LatLong) {
		currentNavDestination = dest
		val loc = locationProvider.currentLocation
		if (loc == null) {
			Log.w(TAG, "navigateTo($dest) deferred: no current location fix yet")
			return
		}
		val start = LatLong(loc.latitude, loc.longitude)
		requestRoute(start, dest)
	}

	fun stopNavigation() {
		pendingSession?.cancel()
		pendingSession = null
		currentNavDestination = null
		currentNavRoute = null
		currentNavJams = null
		lastRouteOrigin = null
		callback(this)
	}

	private fun requestRoute(start: LatLong, dest: LatLong) {
		pendingSession?.cancel()
		// Record the origin we're building from BEFORE the async request fires —
		// the controller's distance-threshold reroute loop reads this to decide
		// whether the next location tick is far enough away to warrant another
		// round-trip. Recording pre-request (not post-response) collapses races
		// where a second tick arrives before the first route resolves.
		lastRouteOrigin = start
		val points = arrayListOf(
				RequestPoint(Point(start.latitude, start.longitude), RequestPointType.WAYPOINT, null, null, null),
				RequestPoint(Point(dest.latitude, dest.longitude), RequestPointType.WAYPOINT, null, null, null)
		)
		// Default DrivingOptions / VehicleOptions — car profile, online+offline
		// fallback routing, one route returned. Matches gmap's "single driving
		// route" surface; we can widen later if ROUTE-03+ ever land.
		pendingSession = drivingRouter.requestRoutes(
				points,
				DrivingOptions().apply { routesCount = 1 },
				VehicleOptions(),
				routeListener
		)
	}
}
