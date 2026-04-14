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
			puckStyle = YandexPuckStyle.ARROW_BLUE,
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
	fun puckStyleFlipIsolated() {
		val changed = YandexMapsSettings.diff(base, base.copy(puckStyle = YandexPuckStyle.ARROW_RED))
		assertEquals(setOf(YandexMapsSettings.ChangedField.PUCK_STYLE), changed)
	}

	@Test
	fun puckStyleNextWrapsAtEnd() {
		// guarantee the in-car tap cycler can rotate through the full
		// catalogue without manual size handling — the controller depends
		// on next() never returning the same value forever.
		var visited = mutableSetOf<YandexPuckStyle>()
		var cursor = YandexPuckStyle.DEFAULT
		repeat(YandexPuckStyle.values().size + 1) {
			visited += cursor
			cursor = cursor.next()
		}
		assertEquals(YandexPuckStyle.values().toSet(), visited)
	}

	@Test
	fun puckStyleStorageKeyRoundTrip() {
		YandexPuckStyle.values().forEach { style ->
			assertEquals(style, YandexPuckStyle.fromStorageKey(style.storageKey))
		}
		// unknown keys fall back to DEFAULT
		assertEquals(YandexPuckStyle.DEFAULT, YandexPuckStyle.fromStorageKey(null))
		assertEquals(YandexPuckStyle.DEFAULT, YandexPuckStyle.fromStorageKey(""))
		assertEquals(YandexPuckStyle.DEFAULT, YandexPuckStyle.fromStorageKey("not_a_real_style"))
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
