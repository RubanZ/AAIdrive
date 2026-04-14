package me.hufman.androidautoidrive.carapp.maps

import me.hufman.androidautoidrive.R

/**
 * Catalogue of user-location puck variants for the `yandexmap` flavor.
 *
 * Persisted as the string [storageKey] in [me.hufman.androidautoidrive.AppSettings.KEYS.MAP_PUCK_STYLE].
 * A future milestone will extend this with 3D model variants — keep [next]
 * cycling-friendly so the in-car tap handler can rotate through every entry
 * without knowing the catalogue size.
 *
 * The drawable resources live in `app/src/yandexmap/res/drawable/`. All puck
 * icons MUST share the 64x64 viewport with the active pixel at (32,32) so
 * swapping at runtime via [com.yandex.mapkit.map.PlacemarkMapObject.setIcon]
 * does not shift the placemark relative to its anchor.
 */
enum class YandexPuckStyle(val storageKey: String, val drawableRes: Int, val labelRes: Int) {
	ARROW_BLUE("arrow_blue", R.drawable.ic_yandex_puck_arrow_blue, R.string.lbl_yandex_puck_arrow_blue),
	ARROW_RED("arrow_red", R.drawable.ic_yandex_puck_arrow_red, R.string.lbl_yandex_puck_arrow_red),
	;

	/** Next variant in the catalogue, wrapping back to the first after the last. */
	fun next(): YandexPuckStyle {
		val all = values()
		return all[(ordinal + 1) % all.size]
	}

	companion object {
		val DEFAULT: YandexPuckStyle = ARROW_BLUE

		/** Lookup by persisted key; falls back to [DEFAULT] for unknown / blank input. */
		fun fromStorageKey(key: String?): YandexPuckStyle {
			if (key.isNullOrBlank()) return DEFAULT
			return values().firstOrNull { it.storageKey == key } ?: DEFAULT
		}
	}
}
