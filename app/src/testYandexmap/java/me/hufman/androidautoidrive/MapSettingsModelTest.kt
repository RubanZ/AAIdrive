package me.hufman.androidautoidrive

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import me.hufman.androidautoidrive.phoneui.viewmodels.MapSettingsModel
import me.hufman.androidautoidrive.phoneui.viewmodels.YandexKeyValidator
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class MapSettingsModelTest {
	@Rule
	@JvmField
	val instantTaskExecutorRule = InstantTaskExecutorRule()

	@Rule
	@JvmField
	val coroutineRule = CoroutineTestRule()

	private val validKey = YandexKeyValidator { true }
	private val invalidKey = YandexKeyValidator { false }
	private val unknownKey = YandexKeyValidator { null }

	@Test
	fun testFactory() {
		val context = mock<Context>()
		val factory = MapSettingsModel.Factory(context)
		val model = factory.create(MapSettingsModel::class.java)
		Assert.assertNotNull(model)
		factory.unsubscribe()
	}

	@Test
	fun testSettings() {
		val context = mock<Context>()
		val carInformation = mock<CarCapabilitiesSummarized>()
		val carInformationLiveData = MutableLiveData(carInformation)
		val model = MapSettingsModel(context, carInformationLiveData, validKey)

		AppSettings.loadDefaultSettings()
		val bindings = mapOf(
				model.mapEnabled to AppSettings.KEYS.ENABLED_MAPS,
				model.mapWidescreen to AppSettings.KEYS.MAP_WIDESCREEN,
				model.mapInvertZoom to AppSettings.KEYS.MAP_INVERT_SCROLL,
				model.mapBuildings to AppSettings.KEYS.MAP_BUILDINGS,
				model.mapTraffic to AppSettings.KEYS.MAP_TRAFFIC,
		)
		bindings.forEach { (viewModel, setting) ->
			AppSettings.tempSetSetting(setting, "true")
			assertEquals("$setting is true", true, viewModel.value)
			AppSettings.tempSetSetting(setting, "false")
			assertEquals("$setting is false", false, viewModel.value)
		}

		val liveDataObserver = Observer<Boolean> {}
		model.mapWidescreenSupported.observeForever(liveDataObserver)
		model.mapWidescreenUnsupported.observeForever(liveDataObserver)
		model.mapWidescreenCrashes.observeForever(liveDataObserver)

		assertEquals(false, model.mapWidescreenSupported.value)
		assertEquals(false, model.mapWidescreenUnsupported.value)
		assertEquals(false, model.mapWidescreenCrashes.value)

		whenever(carInformation.mapWidescreenSupported) doReturn true
		whenever(carInformation.mapWidescreenUnsupported) doReturn false
		whenever(carInformation.mapWidescreenCrashes) doReturn true
		carInformationLiveData.value = carInformation
		assertEquals(true, model.mapWidescreenSupported.value)
		assertEquals(false, model.mapWidescreenUnsupported.value)
		assertEquals(true, model.mapWidescreenCrashes.value)

		model.mapWidescreenSupported.removeObserver(liveDataObserver)
		model.mapWidescreenUnsupported.removeObserver(liveDataObserver)
		model.mapWidescreenCrashes.removeObserver(liveDataObserver)
	}

	@Test
	fun invalidKeyEmitsTrueWhenValidatorReturnsFalse() {
		val context = mock<Context>()
		val carInformationLiveData = MutableLiveData(mock<CarCapabilitiesSummarized>())
		val model = MapSettingsModel(context, carInformationLiveData, invalidKey)

		val observer = Observer<Boolean?> {}
		model.invalidKey.observeForever(observer)
		assertEquals(true, model.invalidKey.value)
		model.invalidKey.removeObserver(observer)
	}

	@Test
	fun invalidKeyEmitsFalseWhenValidatorReturnsTrue() {
		val context = mock<Context>()
		val carInformationLiveData = MutableLiveData(mock<CarCapabilitiesSummarized>())
		val model = MapSettingsModel(context, carInformationLiveData, validKey)

		val observer = Observer<Boolean?> {}
		model.invalidKey.observeForever(observer)
		assertEquals(false, model.invalidKey.value)
		model.invalidKey.removeObserver(observer)
	}

	@Test
	fun invalidKeyEmitsFalseWhenValidatorReturnsNull() {
		val context = mock<Context>()
		val carInformationLiveData = MutableLiveData(mock<CarCapabilitiesSummarized>())
		val model = MapSettingsModel(context, carInformationLiveData, unknownKey)

		val observer = Observer<Boolean?> {}
		model.invalidKey.observeForever(observer)
		// null is not == false, so the banner stays hidden — matches the
		// existing semantics where unknown validation state is treated as OK.
		assertEquals(false, model.invalidKey.value)
		model.invalidKey.removeObserver(observer)
	}
}
