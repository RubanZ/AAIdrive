package me.hufman.androidautoidrive.maps

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.search.Response
import com.yandex.mapkit.search.SearchFactory
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
 * and inspecting the error class.
 *
 * **Thread model:** every MapKit call is marshalled onto the main looper
 * because `libmaps-mobile.so` aborts otherwise (same hard thread-pinning that
 * [YandexPlaceSearch] has to work around — see PITFALLS C2 and the crash
 * analysis in that file's KDoc).
 *
 * Yandex does not expose an introspection endpoint like Mapbox's `/tokens/v2`,
 * so the only ground truth is "attempt an API call and observe the outcome":
 *  - Response arrives → key is valid → `true`
 *  - `UnauthorizedError` / `ForbiddenError` → key rejected → `false`
 *  - Anything else (network, remote, timeout) → unknown → `null`
 */
class YandexKeyValidation(val context: Context) {

	private val mainHandler = Handler(Looper.getMainLooper())

	suspend fun validateKey(): Boolean? {
		val result = CompletableDeferred<Boolean?>()

		val runnable = Runnable {
			try {
				YandexMapsProjection.ensureMapKitInitialized(context)
				val searchManager = SearchFactory.getInstance()
						.createSearchManager(SearchManagerType.COMBINED)
				val anchor = Geometry.fromPoint(Point(55.751244, 37.618423))  // Moscow, arbitrary
				val options = SearchOptions().apply {
					setSearchTypes(SearchType.GEO.value)
					setResultPageSize(1)
				}
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
			} catch (t: Throwable) {
				Log.w(TAG, "Yandex key validation threw synchronously: $t")
				result.complete(null)
			}
		}

		if (Looper.myLooper() === mainHandler.looper) {
			runnable.run()
		} else {
			mainHandler.post(runnable)
		}

		return result.await()
	}
}
