package me.hufman.androidautoidrive.carapp.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexMapsSettingsDiffTest {
	private val base = YandexMapsSettings(
			mapWidescreen = false,
			mapDaytime = true,
			mapBuildings = false,
			mapTraffic = false,
	)

	@Test
	fun firstApplyReturnsAllFields() {
		val changed = YandexMapsSettings.diff(null, base)
		assertEquals(YandexMapsSettings.ChangedField.values().toSet(), changed)
	}

	@Test
	fun identicalSnapshotsProduceNoDiff() {
		val changed = YandexMapsSettings.diff(base, base.copy())
		assertTrue("identical snapshots must yield an empty diff", changed.isEmpty())
	}

	@Test
	fun daytimeFlipIsolated() {
		val changed = YandexMapsSettings.diff(base, base.copy(mapDaytime = false))
		assertEquals(setOf(YandexMapsSettings.ChangedField.DAYTIME), changed)
	}

	@Test
	fun trafficFlipIsolated() {
		val changed = YandexMapsSettings.diff(base, base.copy(mapTraffic = true))
		assertEquals(setOf(YandexMapsSettings.ChangedField.TRAFFIC), changed)
	}

	@Test
	fun buildingsFlipIsolated() {
		val changed = YandexMapsSettings.diff(base, base.copy(mapBuildings = true))
		assertEquals(setOf(YandexMapsSettings.ChangedField.BUILDINGS), changed)
	}

	@Test
	fun widescreenFlipIsolated() {
		val changed = YandexMapsSettings.diff(base, base.copy(mapWidescreen = true))
		assertEquals(setOf(YandexMapsSettings.ChangedField.WIDESCREEN), changed)
	}

	@Test
	fun multipleFieldsFlipTogether() {
		val changed = YandexMapsSettings.diff(
				base,
				base.copy(mapTraffic = true, mapDaytime = false, mapBuildings = true),
		)
		assertEquals(
				setOf(
						YandexMapsSettings.ChangedField.TRAFFIC,
						YandexMapsSettings.ChangedField.DAYTIME,
						YandexMapsSettings.ChangedField.BUILDINGS,
				),
				changed,
		)
	}

	@Test
	fun reapplyingDifferentThenSameSnapshotIsIdempotent() {
		val flipped = base.copy(mapTraffic = true)
		val firstDiff = YandexMapsSettings.diff(base, flipped)
		assertEquals(setOf(YandexMapsSettings.ChangedField.TRAFFIC), firstDiff)
		val secondDiff = YandexMapsSettings.diff(flipped, flipped.copy())
		assertTrue("re-applying the same snapshot must be a no-op", secondDiff.isEmpty())
	}
}
