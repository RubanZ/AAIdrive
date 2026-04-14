package me.hufman.androidautoidrive.carapp.maps

import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.maps.LatLong
import me.hufman.androidautoidrive.utils.TimeUtils

/**
 * Snapshot of the user-visible map settings that live under the `yandexmap`
 * flavor. Used by `YandexMapsProjection.applySettings` to apply changes
 * idempotently and diffably — only the bits that actually changed hit the
 * underlying Yandex APIs, which matters because `TrafficLayer.setTrafficVisible`
 * and `Map.setNightModeEnabled` each trigger a tile refresh.
 *
 * Yandex does NOT expose a style-URL-based customisation surface like Mapbox,
 * so there is no `mapCustomStyle` / `mapboxStyleUrl` analogue here. The only
 * "mode switch" is day/night via `Map.setNightModeEnabled(Boolean)`.
 */
data class YandexMapsSettings(
		val mapWidescreen: Boolean,
		val mapDaytime: Boolean,
		val mapBuildings: Boolean,
		val mapTraffic: Boolean,
) {
	/**
	 * Fields that can independently change between two settings snapshots.
	 * Used by [diff] so the projection can branch on a pure data result
	 * instead of inlining `if (previous?.x != desired.x)` checks against
	 * the live MapKit objects (which can't be touched in unit tests).
	 */
	enum class ChangedField {
		DAYTIME,
		TRAFFIC,
		BUILDINGS,
		WIDESCREEN,
	}

	companion object {
		fun build(appSettings: AppSettings, location: LatLong?): YandexMapsSettings {
			val daytime = location == null || TimeUtils.getDayMode(location)
			return YandexMapsSettings(
					mapWidescreen = appSettings[AppSettings.KEYS.MAP_WIDESCREEN].toBoolean(),
					mapDaytime = daytime,
					mapBuildings = appSettings[AppSettings.KEYS.MAP_BUILDINGS].toBoolean(),
					mapTraffic = appSettings[AppSettings.KEYS.MAP_TRAFFIC].toBoolean(),
			)
		}

		/**
		 * Pure-data diff between two snapshots. A `null` `previous` means
		 * "first apply" and yields every field — this preserves the
		 * original `applySettings` semantics where the very first call
		 * pushes every value to the SDK regardless of defaults.
		 *
		 * An empty result means "no SDK calls needed" — the idempotency
		 * guarantee that [YandexMapsProjection.applySettings] relies on.
		 */
		fun diff(previous: YandexMapsSettings?, desired: YandexMapsSettings): Set<ChangedField> {
			if (previous == null) {
				return ChangedField.values().toSet()
			}
			if (previous == desired) {
				return emptySet()
			}
			val changed = mutableSetOf<ChangedField>()
			if (previous.mapDaytime != desired.mapDaytime) changed += ChangedField.DAYTIME
			if (previous.mapTraffic != desired.mapTraffic) changed += ChangedField.TRAFFIC
			if (previous.mapBuildings != desired.mapBuildings) changed += ChangedField.BUILDINGS
			if (previous.mapWidescreen != desired.mapWidescreen) changed += ChangedField.WIDESCREEN
			return changed
		}
	}
}
