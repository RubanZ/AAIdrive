package me.hufman.androidautoidrive.maps

import android.content.Context
import android.util.Log
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.search.Response
import com.yandex.mapkit.search.SearchFactory
import com.yandex.mapkit.search.SearchManager
import com.yandex.mapkit.search.SearchManagerType
import com.yandex.mapkit.search.SearchOptions
import com.yandex.mapkit.search.SearchType
import com.yandex.mapkit.search.Session
import com.yandex.runtime.Error as YandexError
import com.yandex.runtime.network.ForbiddenError
import com.yandex.runtime.network.UnauthorizedError
import kotlinx.coroutines.CompletableDeferred
import me.hufman.androidautoidrive.carapp.maps.TAG
import me.hufman.androidautoidrive.carapp.maps.YandexMapsProjection

/**
 * Validates the configured Yandex MapKit API key by issuing a trivial search
 * and inspecting the error class if one comes back.
 *
 * Yandex does not expose a `/tokens/v2`-style introspection endpoint like
 * Mapbox does, so the only ground truth is "attempt an API call and observe
 * the outcome":
 *  - Response arrives → key is valid → `true`
 *  - `UnauthorizedError` / `ForbiddenError` → key is explicitly rejected → `false`
 *  - Anything else (network, remote, timeout) → unknown → `null`
 */
class YandexKeyValidation(val context: Context) {
	private val searchManager: SearchManager by lazy {
		YandexMapsProjection.ensureMapKitInitialized(context)
		SearchFactory.getInstance().createSearchManager(SearchManagerType.COMBINED)
	}

	suspend fun validateKey(): Boolean? {
		val result = CompletableDeferred<Boolean?>()
		val anchor = Geometry.fromPoint(Point(55.751244, 37.618423))  // Moscow, arbitrary
		val options = SearchOptions().apply {
			setSearchTypes(SearchType.GEO.value)
			setResultPageSize(1)
		}

		try {
			searchManager.submit("Coffee", anchor, options, object : Session.SearchListener {
				override fun onSearchResponse(response: Response) {
					result.complete(true)
				}

				override fun onSearchError(error: YandexError) {
					when (error) {
						is UnauthorizedError, is ForbiddenError -> {
							Log.w(TAG, "Yandex key validation: key rejected (${error::class.java.simpleName})")
							result.complete(false)
						}
						else -> {
							Log.w(TAG, "Yandex key validation: inconclusive (${error::class.java.simpleName})")
							result.complete(null)
						}
					}
				}
			})
		} catch (e: Throwable) {
			Log.w(TAG, "Yandex key validation threw synchronously: $e")
			result.complete(null)
		}

		return result.await()
	}
}
