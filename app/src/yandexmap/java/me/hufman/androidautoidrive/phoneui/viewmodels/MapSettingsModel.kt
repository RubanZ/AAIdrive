package me.hufman.androidautoidrive.phoneui.viewmodels

import android.content.Context
import androidx.lifecycle.*
import me.hufman.androidautoidrive.*
import me.hufman.androidautoidrive.maps.YandexKeyValidation
import me.hufman.androidautoidrive.phoneui.LiveDataHelpers.map

/**
 * Validator seam so unit tests can drive `invalidKey` deterministically
 * without instantiating `YandexKeyValidation` (which transitively touches
 * the Yandex SDK on the main looper). The single abstract method matches
 * `YandexKeyValidation.validateKey()` so production wiring is a one-line
 * lambda and tests pass a fixed-result lambda.
 */
fun interface YandexKeyValidator {
	suspend fun validateKey(): Boolean?
}

class MapSettingsModel(
		appContext: Context,
		carCapabilitiesSummarized: LiveData<CarCapabilitiesSummarized>,
		keyValidator: YandexKeyValidator = YandexKeyValidator { YandexKeyValidation(appContext).validateKey() },
): ViewModel() {
	class Factory(val appContext: Context): ViewModelProvider.Factory {
		val carInformation = CarInformationObserver()

		@Suppress("UNCHECKED_CAST")
		override fun <T : ViewModel> create(modelClass: Class<T>): T {
			val carCapabilitiesSummarized = MutableLiveData<CarCapabilitiesSummarized>()
			carInformation.callback = {
				carCapabilitiesSummarized.postValue(CarCapabilitiesSummarized(carInformation))
			}
			carCapabilitiesSummarized.value = CarCapabilitiesSummarized(carInformation)
			return MapSettingsModel(appContext, carCapabilitiesSummarized) as T
		}

		fun unsubscribe() {
			carInformation.callback = {}
		}
	}

	val mapEnabled = BooleanLiveSetting(appContext, AppSettings.KEYS.ENABLED_MAPS)
	val mapWidescreen = BooleanLiveSetting(appContext, AppSettings.KEYS.MAP_WIDESCREEN)
	val mapInvertZoom = BooleanLiveSetting(appContext, AppSettings.KEYS.MAP_INVERT_SCROLL)
	val mapBuildings = BooleanLiveSetting(appContext, AppSettings.KEYS.MAP_BUILDINGS)
	val mapTraffic = BooleanLiveSetting(appContext, AppSettings.KEYS.MAP_TRAFFIC)

	val mapWidescreenSupported = carCapabilitiesSummarized.map(false) {
		it.mapWidescreenSupported
	}
	val mapWidescreenUnsupported = carCapabilitiesSummarized.map(false) {
		it.mapWidescreenUnsupported
	}
	val mapWidescreenCrashes = carCapabilitiesSummarized.map(false) {
		it.mapWidescreenCrashes
	}

	val invalidKey: LiveData<Boolean?> = liveData {
		emit(keyValidator.validateKey() == false)
	}
}
