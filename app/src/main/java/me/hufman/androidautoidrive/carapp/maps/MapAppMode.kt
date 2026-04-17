package me.hufman.androidautoidrive.carapp.maps

import android.graphics.Bitmap
import androidx.lifecycle.MutableLiveData
import com.google.gson.JsonObject
import io.bimmergestalt.idriveconnectkit.CDS
import io.bimmergestalt.idriveconnectkit.CDSProperty
import io.bimmergestalt.idriveconnectkit.RHMIDimensions
import io.bimmergestalt.idriveconnectkit.SidebarRHMIDimensions
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.BuildConfig
import me.hufman.androidautoidrive.MutableAppSettingsObserver
import me.hufman.androidautoidrive.carapp.FullImageConfig
import me.hufman.androidautoidrive.carapp.music.MusicAppMode
import me.hufman.androidautoidrive.cds.CDSData
import me.hufman.androidautoidrive.cds.CDSEventHandler
import me.hufman.androidautoidrive.cds.CDSVehicleUnits
import me.hufman.androidautoidrive.maps.LatLong
import kotlin.math.max

class DynamicScreenCaptureConfig(val fullDimensions: RHMIDimensions,
                                 val carTransport: MusicAppMode.TRANSPORT_PORTS,
                                 val timeProvider: () -> Long = {System.currentTimeMillis()}): ScreenCaptureConfig {
	companion object {
		const val RECENT_INTERACTION_THRESHOLD = 5000
		private const val MIN_JPEG_QUALITY = 10
	}

	override val maxWidth: Int = fullDimensions.visibleWidth
	override val maxHeight: Int = fullDimensions.visibleHeight
	// Tested WebP lossy on a MINI ID5/ID6 head unit on 2026-04-13 — RHMI image widget
	// rendered a blank tile, so the protocol rejects anything other than JPEG/PNG.
	// Keep JPEG as the only viable lossy format for this pipeline.
	override val compressFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG

	override val compressQuality: Int
		get() {
			val recentInteraction = recentInteractionUntil > timeProvider()
			val base = if (carTransport == MusicAppMode.TRANSPORT_PORTS.USB) {
				if (recentInteraction) 40 else 65
			} else {
				if (recentInteraction) 12 else 40
			}
			// Interaction already forces the highest per-transport quality and
			// updateMotionSpeed() resets the penalty to zero when the user is
			// touching the map, so this subtraction is effectively a no-op then.
			return (base - motionQualityPenalty).coerceAtLeast(MIN_JPEG_QUALITY)
		}

	var recentInteractionUntil: Long = 0
		private set

	fun startInteraction(timeoutMs: Int = DynamicScreenCaptureConfig.RECENT_INTERACTION_THRESHOLD) {
		recentInteractionUntil = max(recentInteractionUntil, timeProvider() + timeoutMs)
	}

	// Motion-adaptive JPEG quality: read by compressQuality each frame.
	// Discrete penalty points (0 / 15 / 25) with hysteresis thresholds so
	// we don't flap between bands at a single boundary speed. Replaces the
	// previous resolution-scaling scheme — the map producer keeps rendering
	// at full widget resolution and we shed bits in the encoder instead,
	// which avoids the Surface rebind storm the scale path used to trigger.
	@Volatile
	var motionQualityPenalty: Int = 0
		private set

	/**
	 * Push the latest speed from the flavor controller's `onLocationUpdate`.
	 * Forces zero penalty (full quality) when speed is unknown or the user
	 * is actively interacting with the map, otherwise applies a piecewise
	 * table with overlapping bands for hysteresis. Call sites must tolerate
	 * being invoked at ~1 Hz.
	 *
	 * Tier values (2026-04-16 stronger-compression pass): 0 / 15 / 30 / 45
	 * at thresholds 20 / 40 / 70 km/h. At USB base of 65 (idle), this gives:
	 *   stopped / crawling  → 65 quality
	 *   city 20–40 km/h     → 50
	 *   urban 40–70 km/h    → 35
	 *   highway ≥70 km/h    → 20
	 * Non-USB base of 40 clamps to MIN_JPEG_QUALITY (10) above 40 km/h.
	 * Each band has ~10 km/h hysteresis so a boundary crossing never flaps.
	 */
	fun updateMotionSpeed(speedKmh: Float?) {
		if (speedKmh == null || recentInteractionUntil > timeProvider()) {
			motionQualityPenalty = 0
			return
		}
		motionQualityPenalty = when (motionQualityPenalty) {
			45 -> if (speedKmh < 60f) 30 else 45
			30 -> when {
				speedKmh > 70f -> 45
				speedKmh < 30f -> 15
				else -> 30
			}
			15 -> when {
				speedKmh > 40f -> 30
				speedKmh < 15f -> 0
				else -> 15
			}
			else -> if (speedKmh > 20f) 15 else 0
		}
	}
}

class MapAppMode(val fullDimensions: RHMIDimensions,
                 val appSettings: MutableAppSettingsObserver,
                 val cdsData: CDSData,
                 val screenCaptureConfig: DynamicScreenCaptureConfig): FullImageConfig, ScreenCaptureConfig by screenCaptureConfig {
	companion object {
		// whether the custom map is currently navigating somewhere
		private var currentNavDestination: LatLong? = null
			set(value) {
				value?.let { currentNavDestinationObservable.postValue(it) }
				field = value
			}
		private val currentNavDestinationObservable = MutableLiveData<LatLong?>()

		fun build(fullDimensions: RHMIDimensions,
		          appSettings: MutableAppSettingsObserver,
		          cdsData: CDSData,
		          carTransport: MusicAppMode.TRANSPORT_PORTS): MapAppMode {
			val screenCaptureConfig = DynamicScreenCaptureConfig(fullDimensions, carTransport)
			return MapAppMode(fullDimensions, appSettings, cdsData, screenCaptureConfig)
		}
	}

	init {
		cdsData.addEventHandler(CDS.VEHICLE.UNITS, 10000, object: CDSEventHandler {
			override fun onPropertyChangedEvent(property: CDSProperty, propertyValue: JsonObject) {
				// just subscribing in order to ensure that distanceUnits is updated
			}
		})
	}

	// current navigation status, for the UI to observe
	// wraps the static fields so that mock MapAppMode objects can be passed around for testing
	var currentNavDestination: LatLong?
		get() = MapAppMode.currentNavDestination
		set(value) {
			MapAppMode.currentNavDestination = value
		}
	val currentNavDestinationObservable: MutableLiveData<LatLong?>
		get() = MapAppMode.currentNavDestinationObservable

	// navigation distance units
	val distanceUnits: CDSVehicleUnits.Distance
		get() = CDSVehicleUnits.fromCdsProperty(cdsData[CDSProperty.VEHICLE_UNITS]).distanceUnits

	// toggleable settings
	val settings = listOfNotNull(
			// only show the Widescreen option if the car screen is wide
			if (fullDimensions.rhmiWidth >= 1000)        // RHMIDimensions widescreen cut-off
				AppSettings.KEYS.MAP_WIDESCREEN else null
			) + MapToggleSettings.settings + listOfNotNull(
			// add the Mapbox style toggle if it is filled in
			if (BuildConfig.FLAVOR_map=="mapbox" && appSettings[AppSettings.KEYS.MAPBOX_STYLE_URL].isNotBlank())
				AppSettings.KEYS.MAP_CUSTOM_STYLE else null
	)

	// the current appDimensions depending on the widescreen setting
	val appDimensions = SidebarRHMIDimensions(fullDimensions) {isWidescreen}

	// the screen dimensions used by FullImageConfig
	// FullImageConfig uses rhmiDimensions.width/height to set the image capture region
	override val rhmiDimensions = appDimensions

	val isWidescreen: Boolean
		get() = appSettings[AppSettings.KEYS.MAP_WIDESCREEN].toBoolean()
	override val invertScroll: Boolean
		get() = appSettings[AppSettings.KEYS.MAP_INVERT_SCROLL].toBoolean()

	// screen capture quality adjustment
	fun startInteraction(timeoutMs: Int = DynamicScreenCaptureConfig.RECENT_INTERACTION_THRESHOLD) {
		screenCaptureConfig.startInteraction(timeoutMs)
	}

	// motion-adaptive JPEG quality — forwarded to the screen capture config
	// so compressQuality sheds bits on the next encode.
	fun updateMotionSpeed(speedKmh: Float?) {
		screenCaptureConfig.updateMotionSpeed(speedKmh)
	}
}