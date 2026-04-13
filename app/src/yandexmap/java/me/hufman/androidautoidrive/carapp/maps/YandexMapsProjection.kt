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
 * Phase 2 stub: shape only — initialises MapKit once per process, inflates the
 * layout, and exposes `map` / `mapListener`. Phase 4 fills in lifecycle, settings
 * application, and integration with `YandexLocationSource`.
 *
 * Thread discipline: all MapKit calls must run on the thread that called
 * `MapKitFactory.initialize(...)`. We use the main looper here, matching
 * Mapbox's pattern in `MapboxController` + `MapboxProjection`.
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
	var mapListener: Runnable? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		Log.i(TAG, "Projection onCreate")

		ensureMapKitInitialized(parentContext)

		window?.setType(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION)
		setContentView(R.layout.yandex_projection)

		val view = findViewById<MapView>(R.id.yandexMapView)
		this.mapView = view
		this.map = view.mapWindow.map

		// TODO(phase-4): applySettings(), drive camera through locationSource
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
