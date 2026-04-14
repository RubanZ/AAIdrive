package me.hufman.androidautoidrive.carapp.maps

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Circle
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CircleMapObject
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.mapview.MapView
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
		// Soft cap on accuracy radius so a degraded fix doesn't paint the
		// entire viewport blue. Yandex `Circle` takes meters; this is generous.
		private const val ACCURACY_MAX_METERS = 500.0

		fun ensureMapKitInitialized(context: Context) {
			if (INITIALIZED.compareAndSet(false, true)) {
				MapKitFactory.setApiKey(BuildConfig.YandexMapKitApiKey)
				MapKitFactory.setLocale("en_US")
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

	// Most-recently-applied settings snapshot — used by applySettings to diff
	// changes so we only poke the SDK when something actually flipped.
	private var appliedSettings: YandexMapsSettings? = null

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
		if (bearingDegrees != null) {
			placemark.setDirection(bearingDegrees)
		}
		placemark.isVisible = true

		if (accuracyMeters != null && accuracyMeters > 0f) {
			val clamped = accuracyMeters.toDouble().coerceAtMost(ACCURACY_MAX_METERS)
			accuracy.geometry = Circle(point, clamped.toFloat())
			accuracy.isVisible = true
		} else {
			accuracy.isVisible = false
		}
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
		if (YandexMapsSettings.ChangedField.WIDESCREEN in changed) {
			// Widescreen padding: Yandex uses a focusRect on the MapWindow.
			// Phone-in-car HMI already drives fullDimensions via MapAppMode, so
			// the on/off toggle just resets to null for now. A future pass can
			// compute a proper ScreenRect from `AppSettings.MAP_WIDESCREEN`
			// + `mapAppMode` if the car supports the widescreen layout.
			mapView?.mapWindow?.setFocusRect(null)
		}
		if (YandexMapsSettings.ChangedField.PUCK_STYLE in changed && placemark != null) {
			val provider = ImageProvider.fromResource(parentContext, desired.puckStyle.drawableRes)
			// Anchor (0.5, 0.5) puts the GPS coordinate exactly under the
			// arrow's pivot — every puck drawable is authored centered.
			val style = IconStyle().setAnchor(android.graphics.PointF(0.5f, 0.5f))
			placemark.setIcon(provider, style)
		}

		appliedSettings = desired
	}
}
