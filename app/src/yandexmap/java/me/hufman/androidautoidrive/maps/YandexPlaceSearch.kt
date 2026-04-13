package me.hufman.androidautoidrive.maps

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Yandex MapKit implementation of MapPlaceSearch.
 *
 * Phase 2 stub: returns empty results. Phase 3 wires this up to
 * `com.yandex.mapkit.search.SearchManager` + `SuggestSession` and implements the
 * session-token TTL pattern that matches gmap's billing discipline.
 */
class YandexPlaceSearch private constructor(
		@Suppress("UNUSED_PARAMETER") context: Context,
		@Suppress("UNUSED_PARAMETER") locationProvider: CarLocationProvider
) : MapPlaceSearch {
	companion object {
		fun getInstance(context: Context, locationProvider: CarLocationProvider): YandexPlaceSearch {
			return YandexPlaceSearch(context, locationProvider)
		}
	}

	override fun searchLocationsAsync(query: String): Deferred<List<MapResult>> {
		// TODO(phase-3): SearchManager.submit + SuggestSession
		return CompletableDeferred(emptyList())
	}

	override fun resultInformationAsync(resultId: String): Deferred<MapResult?> {
		// TODO(phase-3): SearchManager resolve by resultId
		return CompletableDeferred(null)
	}
}
