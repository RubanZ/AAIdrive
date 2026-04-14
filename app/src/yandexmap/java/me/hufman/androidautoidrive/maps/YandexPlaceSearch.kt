package me.hufman.androidautoidrive.maps

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yandex.mapkit.GeoObject
import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.search.Response
import com.yandex.mapkit.search.SearchFactory
import com.yandex.mapkit.search.SearchManager
import com.yandex.mapkit.search.SearchManagerType
import com.yandex.mapkit.search.SearchOptions
import com.yandex.mapkit.search.SearchType
import com.yandex.mapkit.search.Session
import com.yandex.mapkit.search.SuggestItem
import com.yandex.mapkit.search.SuggestOptions
import com.yandex.mapkit.search.SuggestResponse
import com.yandex.mapkit.search.SuggestSession
import com.yandex.mapkit.search.SuggestType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import me.hufman.androidautoidrive.carapp.maps.TAG
import me.hufman.androidautoidrive.carapp.maps.YandexMapsProjection

/**
 * Yandex MapKit implementation of [MapPlaceSearch].
 *
 * **Thread model — read this before touching anything here:**
 *
 * Yandex MapKit's native code (`libmaps-mobile.so`) hard-pins to the Android
 * main looper. Every API call — not just `MapKitFactory.initialize`, but every
 * `SearchFactory.createSearchManager`, `SearchManager.submit`, `SuggestSession.suggest`,
 * etc. — will `SIGABRT` inside the native binding if it runs off-thread
 * (observed 2026-04-14 as `data_app_native_crash` in
 * `Java_com_yandex_mapkit_search_internal_SearchBinding_createSearchManager`
 * when called from `MapAppService` on `CarThread`).
 *
 * This class therefore:
 *  - Exposes a thread-agnostic `MapPlaceSearch` API. Callers can come from any
 *    thread (car RHMI handler, phone-UI fragment coroutine, anywhere).
 *  - Marshals every MapKit call through `Handler(Looper.getMainLooper())`.
 *  - Lazily creates `SearchManager` on the main thread on first use.
 *  - Returns results asynchronously via `CompletableDeferred`, which is already
 *    thread-safe, so callers resume on whatever dispatcher they started on.
 *
 * Session-token behaviour (`SuggestSession` recreated every 180 s) still mirrors
 * the gmap flavor's `AutocompleteSessionToken` billing pattern. The session
 * itself also lives on the main thread.
 */
class YandexPlaceSearch private constructor(
		private val locationProvider: CarLocationProvider,
		private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : MapPlaceSearch {

	companion object {
		private const val SEARCH_SESSION_TTL = 180_000L  // 3 min — gmap parity

		fun getInstance(context: Context, locationProvider: CarLocationProvider): YandexPlaceSearch {
			// Init is idempotent; provider-based main-thread init has already
			// happened by the time this is called, but the AtomicBoolean guard
			// makes a defensive re-entry safe.
			YandexMapsProjection.ensureMapKitInitialized(context)
			return YandexPlaceSearch(locationProvider)
		}
	}

	// Lives only on the main thread.
	private val mainHandler = Handler(Looper.getMainLooper())

	// Lazy because the very first main-thread job uses it; initialised exactly
	// once when we first touch it from the main thread (see `runOnMain`).
	private var searchManager: SearchManager? = null
	private var suggestSession: SuggestSession? = null
	private var suggestSessionStart: Long = 0

	// Cached results keyed by the id surfaced via SuggestItem — lets
	// `resultInformationAsync` short-circuit without another MapKit call.
	private val cachedResults = mutableMapOf<String, CachedResult>()
	private data class CachedResult(val title: String, val subtitle: String?, val location: LatLong?)

	// ---- Thread marshaling ---------------------------------------------------------------------

	/**
	 * Run [block] on the MapKit-owner thread (main looper). If we're already
	 * on that thread, runs inline. Otherwise posts and the caller does not wait.
	 * Every MapKit call in this class MUST go through this function.
	 */
	private fun runOnMain(block: () -> Unit) {
		if (Looper.myLooper() === mainHandler.looper) {
			block()
		} else {
			mainHandler.post(block)
		}
	}

	/** Main-thread-only accessor that lazily creates the shared [SearchManager]. */
	private fun ensureSearchManager(): SearchManager {
		val existing = searchManager
		if (existing != null) return existing
		val fresh = SearchFactory.getInstance()
				.createSearchManager(SearchManagerType.COMBINED)
		searchManager = fresh
		return fresh
	}

	/** Main-thread-only accessor that honours the TTL-based session recreation. */
	private fun ensureSuggestSession(): SuggestSession {
		val now = timeProvider()
		val existing = suggestSession
		if (existing != null && suggestSessionStart + SEARCH_SESSION_TTL >= now) {
			return existing
		}
		existing?.reset()
		val fresh = ensureSearchManager().createSuggestSession()
		suggestSession = fresh
		suggestSessionStart = now
		return fresh
	}

	// ---- MapPlaceSearch contract --------------------------------------------------------------

	override fun searchLocationsAsync(query: String): Deferred<List<MapResult>> {
		val deferred = CompletableDeferred<List<MapResult>>()

		runOnMain {
			val location = locationProvider.currentLocation
			val userPoint = location?.let { Point(it.latitude, it.longitude) }

			val suggestOptions = SuggestOptions().apply {
				suggestTypes = SuggestType.GEO.value or SuggestType.BIZ.value or SuggestType.TRANSIT.value
				if (userPoint != null) setUserPosition(userPoint)
			}
			val worldBounds = BoundingBox(Point(-89.0, -179.0), Point(89.0, 179.0))

			Log.i(TAG, "Starting Yandex suggest for \"$query\" near $userPoint")

			try {
				val session = ensureSuggestSession()
				session.suggest(query, worldBounds, suggestOptions, object : SuggestSession.SuggestListener {
					override fun onResponse(response: SuggestResponse) {
						val items = response.items ?: emptyList()
						Log.i(TAG, "Received ${items.size} suggest results for \"$query\"")
						val mapped = items.mapIndexedNotNull { index, item -> item.toMapResult(index) }
						cachedResults.clear()
						mapped.forEach { cachedResults[it.id] = CachedResult(it.name, it.address, it.location) }
						deferred.complete(mapped)
					}

					override fun onError(error: com.yandex.runtime.Error) {
						Log.w(TAG, "Yandex suggest error for \"$query\": ${error.classifyMessage()}")
						deferred.complete(emptyList())
					}
				})
			} catch (t: Throwable) {
				Log.w(TAG, "Yandex suggest threw synchronously for \"$query\": $t")
				deferred.complete(emptyList())
			}
		}

		return deferred
	}

	override fun resultInformationAsync(resultId: String): Deferred<MapResult?> {
		// Cache short-circuit — cheap, thread-safe because mutableMap is only
		// mutated from the main thread, and reads tolerate stale data fine here.
		cachedResults[resultId]?.let { cached ->
			if (cached.location != null) {
				return CompletableDeferred(
						MapResult(resultId, cached.title, cached.subtitle, location = cached.location)
				)
			}
		}

		val deferred = CompletableDeferred<MapResult?>()

		runOnMain {
			val cached = cachedResults[resultId]
			val searchText = cached?.title ?: resultId
			val userPoint = locationProvider.currentLocation?.let { Point(it.latitude, it.longitude) }
			val anchor = if (userPoint != null) Geometry.fromPoint(userPoint)
			             else Geometry.fromBoundingBox(BoundingBox(Point(-89.0, -179.0), Point(89.0, 179.0)))
			val options = SearchOptions().apply {
				setSearchTypes(SearchType.GEO.value or SearchType.BIZ.value)
				setResultPageSize(1)
				if (userPoint != null) setUserPosition(userPoint)
			}

			try {
				ensureSearchManager().submit(searchText, anchor, options, object : Session.SearchListener {
					override fun onSearchResponse(response: Response) {
						val obj = response.collection?.children?.firstOrNull()?.obj
						if (obj == null) {
							Log.w(TAG, "Yandex search returned no result for \"$searchText\"")
							deferred.complete(null)
							return
						}
						val mapResult = obj.toMapResult(resultId)
						if (mapResult == null) {
							Log.w(TAG, "Yandex GeoObject for \"$searchText\" had no resolvable point")
							deferred.complete(null)
							return
						}
						cachedResults[resultId] = CachedResult(mapResult.name, mapResult.address, mapResult.location)
						deferred.complete(mapResult)
					}

					override fun onSearchError(error: com.yandex.runtime.Error) {
						Log.w(TAG, "Yandex search error for \"$searchText\": ${error.classifyMessage()}")
						deferred.complete(null)
					}
				})
			} catch (t: Throwable) {
				Log.w(TAG, "Yandex searchManager.submit threw for \"$searchText\": $t")
				deferred.complete(null)
			}
		}

		return deferred
	}

	// ---- Helpers ------------------------------------------------------------------------------

	private fun SuggestItem.toMapResult(index: Int): MapResult? {
		val title = displayText ?: searchText ?: return null
		val subtitleText = subtitle?.text ?: ""
		val center: Point? = try { center } catch (_: Throwable) { null }
		val latLong = center?.let { LatLong(it.latitude, it.longitude) }
		val id = searchText ?: "${title}#$index"
		return MapResult(id = id, name = title, address = subtitleText, location = latLong)
	}

	private fun GeoObject.toMapResult(resultId: String): MapResult? {
		val point: Point = geometry.firstNotNullOfOrNull { it.point } ?: return null
		return MapResult(
				id = resultId,
				name = name ?: "",
				address = descriptionText ?: "",
				location = LatLong(point.latitude, point.longitude)
		)
	}
}

/**
 * Classify a Yandex runtime error into a short human-readable tag for logging.
 * Uses reflection on the class name so MapKit version bumps don't break us.
 */
private fun com.yandex.runtime.Error.classifyMessage(): String =
		"${this::class.java.simpleName}(valid=${try { isValid } catch (_: Throwable) { "?" }})"
