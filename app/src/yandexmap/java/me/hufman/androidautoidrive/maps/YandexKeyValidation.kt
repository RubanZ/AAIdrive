package me.hufman.androidautoidrive.maps

import android.content.Context

/**
 * Validates the configured Yandex MapKit API key.
 *
 * Yandex does not expose an introspection endpoint (unlike Mapbox's /tokens/v2),
 * so validation means issuing a trivial API call and inspecting the error class.
 *
 * Phase 2 stub: returns null (unknown). Phase 3 wires this up to a real
 * SearchManager request and inspects the error class (`RESTRICTED_API_KEY` → false,
 * success → true, network error → null).
 */
class YandexKeyValidation(@Suppress("UNUSED_PARAMETER") val context: Context) {
	@Suppress("RedundantSuspendModifier")
	suspend fun validateKey(): Boolean? {
		// TODO(phase-3): real validation via SearchManager submit + error class inspection.
		return null
	}
}
