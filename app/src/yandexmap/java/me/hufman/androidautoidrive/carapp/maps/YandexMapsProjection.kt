package me.hufman.androidautoidrive.carapp.maps

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.user_location.UserLocationLayer
import me.hufman.androidautoidrive.AppSettingsObserver
import me.hufman.androidautoidrive.BuildConfig
import me.hufman.androidautoidrive.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Yandex MapKit projection attached to a `VirtualDisplay`, following Path A of
 * the C1 decision (see `.planning/DECISIONS.md`): inflate a real MapKit `MapView`
 * inside an `android.app.Presentation`; rely on the WindowManager compositor to
 * push frames into the `VirtualDisplay`'s Surface, which `VirtualDisplayScreenCapture`
 * then reads via an `ImageReader`.
 *
 * Phase 4: real MapKit lifecycle wiring, camera exposure, and `UserLocationLayer`
 * bound to the caller-supplied `YandexLocationSource` (a custom `LocationManager`
 * that relays CDS-driven location fixes into MapKit). Settings / traffic / day-night
 * still live in Phase 5.
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
		// Whether the process-level MapKit instance has had its LocationManager
		// replaced with the AAIdrive `YandexLocationSource`. Set exactly once
		// per process; bound to whichever source instance attached first.
		private val LOCATION_MANAGER_INSTALLED = AtomicBoolean(false)

		fun ensureMapKitInitialized(context: Context) {
			if (INITIALIZED.compareAndSet(false, true)) {
				MapKitFactory.setApiKey(BuildConfig.YandexMapKitApiKey)
				MapKitFactory.setLocale("en_US")
				MapKitFactory.initialize(context.applicationContext)
			}
		}

		/**
		 * Install the CDS-driven `LocationManager` on the process-wide `MapKit`.
		 * Idempotent: subsequent calls are no-ops so warm-start cycles don't
		 * repeatedly swap the manager out from under an active `UserLocationLayer`.
		 */
		fun installLocationManager(source: YandexLocationSource) {
			if (LOCATION_MANAGER_INSTALLED.compareAndSet(false, true)) {
				MapKitFactory.getInstance().setLocationManager(source)
			}
		}
	}

	var mapView: MapView? = null
	var map: Map? = null
	var userLocationLayer: UserLocationLayer? = null
	var mapListener: Runnable? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		Log.i(TAG, "Projection onCreate")

		ensureMapKitInitialized(parentContext)
		installLocationManager(locationSource)

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

		// Build the user-location puck. `createUserLocationLayer` consumes the
		// process-wide `MapKit.locationManager`, which `installLocationManager`
		// has already swapped to our CDS-driven `YandexLocationSource`.
		val layer = MapKitFactory.getInstance().createUserLocationLayer(view.mapWindow)
		layer.setVisible(true)
		layer.isHeadingModeActive = true
		this.userLocationLayer = layer

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

	fun applySettings() {
		// TODO(phase-5): traffic layer toggle, day/night styling, widescreen padding
	}
}
