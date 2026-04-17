package me.hufman.androidautoidrive.carapp.maps

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.appcompat.content.res.AppCompatResources
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.ScreenPoint
import com.yandex.mapkit.ScreenRect
import com.yandex.mapkit.geometry.Circle
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.CircleMapObject
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.map.MapObjectCollection
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.map.PolylineMapObject
import com.yandex.mapkit.map.RotationType
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.navigation.JamSegment
import com.yandex.mapkit.navigation.JamType
import com.yandex.mapkit.traffic.TrafficLayer
import com.yandex.runtime.image.ImageProvider
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.AppSettingsObserver
import me.hufman.androidautoidrive.BuildConfig
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.maps.LatLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Yandex MapKit projection attached to a `VirtualDisplay`, following Path A of
 * the C1 decision (see `.planning/DECISIONS.md`): inflate a real MapKit `MapView`
 * inside an `android.app.Presentation`; rely on the WindowManager compositor to
 * push frames into the `VirtualDisplay`'s Surface, which `VirtualDisplayScreenCapture`
 * then reads via an `ImageReader`.
 *
 * Phase 4 wired the lifecycle, camera, and a `UserLocationLayer` backed by a
 * custom `LocationManager`. That approach was retired after device testing
 * surfaced a `NoSuchFieldError: nativeObject` thrown from
 * `MapKitBinding.setLocationManager` — Yandex MapKit's JNI bridge requires the
 * supplied `LocationManager` instance to carry a private `nativeObject` field
 * of type `com.yandex.runtime.NativeObject`, which a pure-Java implementation
 * cannot provide. Yandex publishes no `BaseLocationManager` for subclassing.
 *
 * Current strategy: skip `setLocationManager` entirely, draw the user puck
 * ourselves as a [PlacemarkMapObject] (`puckPlacemark`) plus an optional
 * accuracy [CircleMapObject] (`accuracyCircle`). [updateUserLocation] is the
 * single entry point — the controller calls it on every CDS tick. Icon/style
 * changes route through [applySettings] via [YandexMapsSettings.ChangedField.PUCK_STYLE].
 *
 * Thread discipline: all MapKit calls must run on the thread that called
 * `MapKitFactory.initialize(...)`. We use the main looper here, matching
 * the gmap/mapbox pattern (`MapboxController.kt:35` / `GMapsController.kt:25`).
 */
class YandexMapsProjection(
		val parentContext: Context,
		display: Display,
		val appSettings: AppSettingsObserver,
		val locationSource: YandexLocationSource
) : Presentation(parentContext, display) {

	companion object {
		private const val TAG = "YandexMapsProjection"
		// MapKitFactory.initialize must be called exactly once per process.
		// Guard prevents AssertionError on warm-start / reconnect loops driven by MainService.
		private val INITIALIZED = AtomicBoolean(false)
		// One-time flag so the STYLE-05 buildings-mapping rationale is logged
		// at most once per process even though `applySettings` runs frequently.
		private val BUILDINGS_MAPPING_LOGGED = AtomicBoolean(false)

		// Z-order for the user-location placemark — high enough that it stays
		// above traffic and POI labels regardless of zoom.
		private const val PUCK_Z_INDEX = 1000f
		// Accuracy circle is below the placemark so the arrow always reads
		// cleanly even when accuracy is wide.
		private const val ACCURACY_Z_INDEX = 999f
		// Route polyline sits above base tiles / traffic but below the puck so
		// the user's position always reads cleanly against the line.
		private const val ROUTE_Z_INDEX = 500f
		// Route stroke width in dp-ish units (Yandex uses "world-pixel" units
		// that roughly track screen pixels for CanvasItem line objects). Matches
		// the visual weight of gmap's default PolylineOptions.width.
		private const val ROUTE_STROKE_WIDTH = 5f
		// Per-segment jam colour palette — mirrors Yandex Navigator's traffic
		// layer convention so users coming from Yandex Maps / Navigator see the
		// same meaning (green = free flow, red = heavy jam, purple = blocked).
		// ARGB ints consumed directly by PolylineMapObject.setStrokeColors.
		private const val JAM_COLOR_FREE      = 0xFF31C447.toInt() // green
		private const val JAM_COLOR_LIGHT     = 0xFFFFCC00.toInt() // yellow
		private const val JAM_COLOR_HARD      = 0xFFE84B3A.toInt() // red-orange
		private const val JAM_COLOR_VERY_HARD = 0xFFB02020.toInt() // dark red
		private const val JAM_COLOR_BLOCKED   = 0xFF5C2480.toInt() // purple
		// Soft cap on accuracy radius so a degraded fix doesn't paint the
		// entire viewport blue. Yandex `Circle` takes meters; this is generous.
		private const val ACCURACY_MAX_METERS = 500.0

		// Nav-mode map style applied via Map.setMapStyle on onCreate. MapKit's
		// styler DSL is tag-based (see Yandex docs "Map styles" — not the
		// web-renderer `source-layer` format served from core-renderer-tiles).
		// "If an unknown tag is specified, styles are not applied", so every
		// tag below is taken from the documented substrate vocabulary.
		//
		// Rationale for each hidden group in a driving HUD:
		//  - `poi`          — covers the entire POI subtree (major_landmark,
		//                     food_and_drink, shopping, fuel_station, hotel,
		//                     medical, cemetery, outdoor/park, outdoor/beach,
		//                     outdoor/parking, etc.). One rule kills all POI
		//                     icons and text labels.
		//  - `transit`      — covers transit_location/stop/entrance,
		//                     transit_line, transit_schema,
		//                     is_unclassified_transit. Removes bus/tram/metro
		//                     stops, lines and schematic overlays.
		//  - `parking`      — covers the parking polygons in `urban_area` and
		//                     the parking POI subtree. Removes the big 'P'
		//                     blocks that eat urban real estate.
		//
		// NOT hidden (kept deliberately): road*, road_surface, road_marking,
		// crosswalk, underpass, traffic_light, water, landscape (land,
		// landcover, vegetation), urban_area (residential, industrial,
		// sports_ground, park, national_park, beach), terrain, admin,
		// structure (building, entrance, fence, is_tunnel, is_toll),
		// geographic_line. setMapStyle is orthogonal to isNightModeEnabled so
		// day/night still flips independently.
		private const val NAV_MAP_STYLE_JSON = """
[
  {"tags":{"any":["poi"]},"stylers":{"visibility":"off"}},
  {"tags":{"any":["transit"]},"stylers":{"visibility":"off"}},
  {"tags":{"any":["parking"]},"stylers":{"visibility":"off"}},
  {"tags":{"any":["road_marking"]},"stylers":{"color":"#FFFF00FF"}}
]
"""

		fun ensureMapKitInitialized(context: Context) {
			if (INITIALIZED.compareAndSet(false, true)) {
				MapKitFactory.setApiKey(BuildConfig.YandexMapKitApiKey)
				MapKitFactory.setLocale("ru_RU")
				MapKitFactory.initialize(context.applicationContext)
			}
		}
	}

	var mapView: MapView? = null
	var map: Map? = null
	var trafficLayer: TrafficLayer? = null
	var mapListener: Runnable? = null

	// Custom user-location puck (replaces UserLocationLayer — see class kdoc).
	private var puckPlacemark: PlacemarkMapObject? = null
	private var accuracyCircle: CircleMapObject? = null

	// Dedicated sub-collection for route-related objects so stopping navigation
	// can `.clear()` without touching the puck / accuracy circle that live in
	// the root mapObjects collection.
	private var routeCollection: MapObjectCollection? = null
	private var routePolyline: PolylineMapObject? = null

	// Most-recently-applied settings snapshot — used by applySettings to diff
	// changes so we only poke the SDK when something actually flipped.
	private var appliedSettings: YandexMapsSettings? = null

	// Latest desired heading-up state. Cached so the layout listener can
	// re-apply the focus rect whenever the MapView resizes (first layout on
	// car HU often races ahead of applySettings()'s first post{} callback
	// — at that point mapWindow.width/height are still zero and the focus
	// rect is silently skipped).
	private var desiredHeadingUp: Boolean = false

	/**
	 * Optional callback fired when the user taps the puck on the map.
	 * Wired by the controller — typical use is to cycle to the next
	 * [YandexPuckStyle] and persist it. Car HMI does not deliver touch
	 * events to the projection's Presentation today, so this callback is
	 * effectively a no-op in production hardware; the same cycling
	 * behaviour is exposed via the phone-side settings fragment. Kept
	 * wired so that any future touch transport (e.g. a debug build that
	 * mirrors the phone display) can exercise it without further changes.
	 */
	var onPuckTapped: (() -> Unit)? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		Log.i(TAG, "Projection onCreate")

		ensureMapKitInitialized(parentContext)

		window?.setType(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION)
		setContentView(R.layout.yandex_projection)

		val view = findViewById<MapView>(R.id.yandexMapView)
		this.mapView = view
		this.map = view.mapWindow.map

		// MapWindow width/height are still 0 at the moment onCreate runs, so
		// applyFocusRect()'s view.post{} call would see a zero-sized window
		// and silently bail. The layout listener re-applies the rect as soon
		// as the view actually has dimensions, and again on every resize
		// (e.g. widescreen flip).
		view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
			applyFocusRect(desiredHeadingUp)
		}

		// Trim POI / indoor / transit / parking clutter via Yandex's styler DSL
		// before the first frame renders. Logs the boolean return so a typo in
		// the JSON is visible in logcat (MapKit silently keeps the default
		// style on parse failure).
		val styleOk = view.mapWindow.map.setMapStyle(NAV_MAP_STYLE_JSON)
		Log.i(TAG, "Nav map style applied: ok=$styleOk")

		// Car HMI has no touch: disable every user gesture so stray taps on
		// adjacent UI can't nudge the map. Mapbox mirrors this via excluded
		// gesture plugins in build.gradle; Yandex exposes it as setters.
		view.mapWindow.map.apply {
			isZoomGesturesEnabled = false
			isScrollGesturesEnabled = false
			isTiltGesturesEnabled = false
			isRotateGesturesEnabled = false
			isFastTapEnabled = false
		}

		// Traffic layer is eagerly created once so settings toggles are cheap.
		this.trafficLayer = MapKitFactory.getInstance().createTrafficLayer(view.mapWindow)

		// Build the user-location puck and accuracy halo. Both start at
		// (0,0) until updateUserLocation lands the first real fix; they're
		// hidden via setVisible(false) until then.
		val initialPoint = Point(0.0, 0.0)
		val mapObjects = view.mapWindow.map.mapObjects

		// Route collection is added first so route polylines sit below the
		// puck in z-order terms. We still explicitly set ROUTE_Z_INDEX on the
		// polyline to stay robust if the creation order ever shifts.
		this.routeCollection = mapObjects.addCollection()

		val accuracy = mapObjects.addCircle(Circle(initialPoint, 0f))
		// Setters land on the CircleMapObject — addCircle() in this MapKit
		// version only accepts the geometry; style is mutable post-add.
		accuracy.strokeColor = 0xFFFFFFFF.toInt()
		accuracy.strokeWidth = 0f
		accuracy.fillColor = 0x332E7AFF
		accuracy.zIndex = ACCURACY_Z_INDEX
		accuracy.isVisible = false
		this.accuracyCircle = accuracy

		val placemark = mapObjects.addPlacemark(initialPoint)
		placemark.zIndex = PUCK_Z_INDEX
		placemark.isVisible = false
		placemark.addTapListener { _, _ ->
			onPuckTapped?.invoke()
			true // consume — even if there's no listener, swallow the tap
		}
		this.puckPlacemark = placemark

		// First-time settings apply before any frame renders. Pushes the
		// initial puck icon into the placemark.
		applySettings()

		// Re-apply whenever any map setting flips (AppSettingsObserver fires on
		// every change; applySettings diffs internally so this stays cheap).
		appSettings.callback = { applySettings() }

		mapListener?.run()
	}

	override fun onStart() {
		super.onStart()
		Log.i(TAG, "Projection onStart")
		MapKitFactory.getInstance().onStart()
		mapView?.onStart()
	}

	override fun onStop() {
		Log.i(TAG, "Projection onStop")
		mapView?.onStop()
		MapKitFactory.getInstance().onStop()
		super.onStop()
	}

	/**
	 * Update the user-location puck position, heading, and accuracy halo.
	 *
	 * Called by [YandexMapsController] on every `CdsLocationProvider` tick
	 * (and on the first fix to wake the puck out of its hidden state). All
	 * arguments come straight from the cached [YandexLocationSource] state
	 * so the projection itself stays free of `android.location.Location`.
	 *
	 * @param latLong Latest CDS coordinate.
	 * @param bearingDegrees Heading in degrees, or `null` to keep the
	 *                       previous direction (e.g. when the car is parked
	 *                       and bearing is undefined).
	 * @param accuracyMeters GPS accuracy in meters, or `null` if the fix
	 *                       didn't include one — the accuracy halo hides.
	 */
	fun updateUserLocation(latLong: LatLong, bearingDegrees: Float?, accuracyMeters: Float?) {
		val placemark = this.puckPlacemark ?: return
		val accuracy = this.accuracyCircle ?: return

		val point = Point(latLong.latitude, latLong.longitude)
		placemark.geometry = point
		// Normalize bearing into [0, 360) before passing to Yandex. Raw
		// CdsLocationProvider bearings can be negative (the provider negates
		// CDS heading to match Android Location convention — see
		// CarLocationProvider.kt:114), and while PlacemarkMapObject.setDirection
		// nominally accepts any float, some Yandex builds do not normalize
		// negative values and render the icon rotated to the wrong quadrant.
		val normalizedBearing = bearingDegrees?.let { raw ->
			val mod = raw.rem(360f)
			if (mod < 0f) mod + 360f else mod
		}
		if (normalizedBearing != null) {
			placemark.setDirection(normalizedBearing)
		}
		placemark.isVisible = true

		Log.d(TAG, "updateUserLocation lat=${"%.6f".format(latLong.latitude)} lon=${"%.6f".format(latLong.longitude)} bearingRaw=$bearingDegrees bearingNorm=$normalizedBearing accuracyM=$accuracyMeters")

		if (accuracyMeters != null && accuracyMeters > 0f) {
			val clamped = accuracyMeters.toDouble().coerceAtMost(ACCURACY_MAX_METERS)
			accuracy.geometry = Circle(point, clamped.toFloat())
			accuracy.isVisible = true
		} else {
			accuracy.isVisible = false
		}
	}

	/**
	 * Draw (or clear) a route polyline, optionally colouring per-segment
	 * according to Yandex's live traffic classification ([JamSegment]).
	 *
	 * Called by [YandexMapsController]'s `drawNavigation` whenever
	 * [YandexMapsNavController] fires its update callback — the controller owns
	 * the "is there a route?" state, we only own the visual.
	 *
	 * Passing `null` or an empty `points` list clears the current polyline. Any
	 * non-empty list replaces the existing polyline (we drop the old one and
	 * create a new one instead of calling `setGeometry` because Yandex's internal
	 * cache has historically mishandled large geometry swaps on existing
	 * PolylineMapObject instances — recreating is simpler and the allocation
	 * cost is negligible next to the route-request round-trip that preceded it).
	 *
	 * **Colouring:**
	 *  - If `jams` is null/empty OR every entry is `JamType.UNKNOWN`, we use the
	 *    solid fallback colour `R.color.mapRouteLine` — gmap parity.
	 *  - Otherwise we build a `List<Int>` of size `points.size - 1` where each
	 *    entry maps the corresponding `JamSegment.jamType` through the palette
	 *    in the companion object, and call `PolylineMapObject.setStrokeColors`.
	 *  - If `jams.size != points.size - 1` we fall back to the solid colour
	 *    rather than risk a native-layer index mismatch (defensive — Yandex
	 *    normally returns one segment per polyline edge).
	 */
	fun drawRoute(points: List<Point>?, jams: List<JamSegment>? = null) {
		val collection = this.routeCollection ?: return
		// Always clear first — simplest correct behaviour for both "new route"
		// and "stopNavigation" cases. `routePolyline` is invalidated as a side
		// effect so we can't keep a dangling reference into a cleared parent.
		collection.clear()
		routePolyline = null
		if (points == null || points.size < 2) {
			return
		}
		val polyline = collection.addPolyline(Polyline(points))
		polyline.strokeWidth = ROUTE_STROKE_WIDTH
		polyline.zIndex = ROUTE_Z_INDEX

		val expectedJamCount = points.size - 1
		val hasUsableJams = jams != null
				&& jams.size == expectedJamCount
				&& jams.any { it.jamType != JamType.UNKNOWN }
		if (hasUsableJams && jams != null) {
			val segmentColors: List<Int> = jams.map { jamColorFor(it.jamType) }
			polyline.setStrokeColors(segmentColors)
		} else {
			polyline.setStrokeColor(parentContext.getColor(R.color.mapRouteLine))
		}
		this.routePolyline = polyline
	}

	private fun jamColorFor(type: JamType): Int = when (type) {
		JamType.FREE -> JAM_COLOR_FREE
		JamType.LIGHT -> JAM_COLOR_LIGHT
		JamType.HARD -> JAM_COLOR_HARD
		JamType.VERY_HARD -> JAM_COLOR_VERY_HARD
		JamType.BLOCKED -> JAM_COLOR_BLOCKED
		JamType.UNKNOWN -> parentContext.getColor(R.color.mapRouteLine)
	}

	/**
	 * Read the current settings from the observer, snapshot them into a
	 * [YandexMapsSettings], and apply only the dimensions that differ from the
	 * most recently applied snapshot. Idempotent and cheap to call on every
	 * location tick or settings edit.
	 *
	 * `currentLocation` is pulled from the [YandexLocationSource] cache so
	 * day/night detection has a coordinate to work with. On the very first
	 * call before any CDS fix, [LatLong] is null and we default to daytime.
	 */
	fun applySettings() {
		val map = this.map ?: return
		val traffic = this.trafficLayer
		val placemark = this.puckPlacemark

		val currentLatLong: LatLong? = locationSource.latestLatLong()
		val desired = YandexMapsSettings.build(appSettings, currentLatLong)
		val previous = appliedSettings
		val changed = YandexMapsSettings.diff(previous, desired)

		// Track the latest heading-up intent even when nothing else changed,
		// so a layout pass that arrives later (very common on the VirtualDisplay
		// path) can still pick up the correct focus rect.
		desiredHeadingUp = desired.mapHeadingUp

		if (changed.isEmpty()) {
			return
		}

		if (YandexMapsSettings.ChangedField.DAYTIME in changed) {
			map.isNightModeEnabled = !desired.mapDaytime
		}
		if (YandexMapsSettings.ChangedField.TRAFFIC in changed) {
			traffic?.isTrafficVisible = desired.mapTraffic
		}
		if (YandexMapsSettings.ChangedField.BUILDINGS in changed) {
			if (BUILDINGS_MAPPING_LOGGED.compareAndSet(false, true)) {
				Log.i(TAG, "STYLE-05: MAP_BUILDINGS is mapped to Map.set2DMode(!buildings) — Yandex MapKit 4.x does not expose an explicit building-extrusion toggle; disabling 2D mode yields the tilted view that renders extrusions at high zoom.")
			}
			map.set2DMode(!desired.mapBuildings)
		}
		if (YandexMapsSettings.ChangedField.WIDESCREEN in changed ||
				YandexMapsSettings.ChangedField.HEADING_UP in changed) {
			// Yandex uses a MapWindow focusRect to offset where the camera
			// target lands in window coordinates. We always push the focus
			// below screen center so the puck sits in the lower portion of
			// the window (navigator-style "look ahead"): heading-up pins
			// it at 75% of height, everything else at 65%. Widescreen
			// recomputes the same rect (placeholder for a future padded
			// layout).
			applyFocusRect(desired.mapHeadingUp)
		}
		if (YandexMapsSettings.ChangedField.PUCK_STYLE in changed && placemark != null) {
			// Rasterize the vector drawable manually: ImageProvider.fromResource
			// calls AndroidBitmap_getInfo on the compiled resource, which
			// fails with "Error code: -1" for VectorDrawable (observed on
			// device, 2026-04-14). fromBitmap works with any Drawable source.
			val bitmap = rasterizePuckDrawable(desired.puckStyle.drawableRes)
			if (bitmap != null) {
				val provider = ImageProvider.fromBitmap(bitmap)
				// Anchor (0.5, 0.5) puts the GPS coordinate exactly under the
				// arrow's pivot — every puck drawable is authored centered.
				// RotationType.ROTATE is the ONLY way to make setDirection()
				// actually rotate the icon — the default is NO_ROTATION, which
				// silently ignores every direction change (observed 2026-04-14:
				// bearing=82.97° arrived on every tick but the arrow stayed
				// pointing north on screen).
				val style = IconStyle()
						.setAnchor(android.graphics.PointF(0.5f, 0.5f))
						.setRotationType(RotationType.ROTATE)
				placemark.setIcon(provider, style)
			} else {
				Log.w(TAG, "Puck rasterization returned null for ${desired.puckStyle.storageKey}; keeping previous icon")
			}
		}

		appliedSettings = desired
	}

	/**
	 * Rasterize a VectorDrawable resource into a [Bitmap] sized for the puck.
	 *
	 * [ImageProvider.fromResource] internally calls `AndroidBitmap_getInfo`
	 * on the compiled resource entry; for a compiled VectorDrawable this
	 * returns error code -1 ("not a bitmap"), which the Yandex native layer
	 * logs as `yandex.maps: AndroidBitmap_getInfo() failed`. Feeding it a
	 * pre-rasterized Bitmap via [ImageProvider.fromBitmap] sidesteps the
	 * check entirely.
	 *
	 * Size is read from the drawable's own intrinsic bounds (the vector
	 * author set `48dp x 48dp`). If the drawable fails to inflate (missing
	 * resource, XML parse error) we return `null` and leave the previous
	 * icon in place.
	 */
	/**
	 * Move the map's camera target anchor to the lower portion of the window
	 * when heading-up mode is active, so the user puck sits at ~80% of screen
	 * height — a navigator-style "look ahead" view where what's behind the
	 * car doesn't waste pixels. In plain follow mode we reset `focusRect` to
	 * `null` so Yandex uses its default full-window center.
	 *
	 * Deferred via [MapView.post] so the first call (from the first-fix
	 * `applySettings` inside `onLocationUpdate`) still runs after the
	 * `MapView` has laid out and `mapWindow.width` / `height` are non-zero.
	 */
	private fun applyFocusRect(headingUp: Boolean) {
		val view = mapView ?: return
		view.post {
			val window = view.mapWindow
			val w = window.width()
			val h = window.height()
			if (w <= 0 || h <= 0) return@post
			// Heading-up: anchor at 0.80*h. focusRect center sits at
			// (0.60*h + 1.00*h) / 2 = 0.80*h. Full width so horizontal
			// centering is unchanged. Follow mode: null → default center.
			val rect = if (headingUp) {
				ScreenRect(
						ScreenPoint(0f, h * 0.60f),
						ScreenPoint(w.toFloat(), h.toFloat()),
				)
			} else {
				null
			}
			window.focusRect = rect
		}
	}

	private fun rasterizePuckDrawable(drawableRes: Int): Bitmap? {
		val drawable: Drawable = AppCompatResources.getDrawable(parentContext, drawableRes) ?: return null
		val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
		val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
		val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		drawable.setBounds(0, 0, w, h)
		drawable.draw(canvas)
		return bitmap
	}
}
