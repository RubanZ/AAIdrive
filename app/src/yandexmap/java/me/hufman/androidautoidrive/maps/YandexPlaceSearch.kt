package me.hufman.androidautoidrive.maps

import android.content.Context
import android.util.Log
import com.yandex.mapkit.GeoObject
import com.yandex.mapkit.MapKitFactory
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
 * Wraps [SearchManager] for full search (user-submitted queries) and
 * [SuggestSession] for autocomplete. The autosuggest session is held for
 * [SEARCH_SESSION_TTL] ms and recreated on expiry, mirroring the billing
 * discipline the gmap flavor uses with Google Places AutocompleteSessionToken.
 *
 * All MapKit calls must run on the thread that called `MapKitFactory.initialize`;
 * here we rely on `YandexMapsProjection.ensureMapKitInitialized(context)` being
 * called either from `MapAppService.onCarStart()` or from
 * `YandexMapsProjection.onCreate()` before the first search lands. Both entry
 * points use the application context's main looper.
 */
class YandexPlaceSearch private constructor(
		private val searchManager: SearchManager,
		private val locationProvider: CarLocationProvider,
		private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : MapPlaceSearch {
	companion object {
		private const val SEARCH_SESSION_TTL = 180_000L  // 3 minutes — parity with gmap

		fun getInstance(context: Context, locationProvider: CarLocationProvider): YandexPlaceSearch {
			// MapKit must be initialised exactly once per process before any factory call.
			// ensureMapKitInitialized is idempotent via AtomicBoolean.
			YandexMapsProjection.ensureMapKitInitialized(context)
			val searchManager = SearchFactory.getInstance()
					.createSearchManager(SearchManagerType.COMBINED)
			return YandexPlaceSearch(searchManager, locationProvider)
		}
	}

	// ---- Suggest session (autocomplete) lifecycle ------------------------------------------------

	private var suggestSessionStart: Long = 0
	private var suggestSession: SuggestSession? = null

	// Keep references to pending search Sessions so they can be cancelled when
	// the map pane is closed. Yandex returns a Session handle from every submit().
	private val pendingSearchSessions = mutableListOf<Session>()

	// Cache of the most recent search response's items, keyed by a synthetic id,
	// so `resultInformationAsync(id)` can look them up without re-querying.
	private val cachedResults = mutableMapOf<String, CachedResult>()

	private data class CachedResult(val title: String, val subtitle: String?, val location: LatLong?)

	private fun obtainSuggestSession(): SuggestSession {
		val now = timeProvider()
		val existing = suggestSession
		if (existing == null || suggestSessionStart + SEARCH_SESSION_TTL < now) {
			existing?.reset()
			val fresh = searchManager.createSuggestSession()
			suggestSession = fresh
			suggestSessionStart = now
			return fresh
		}
		return existing
	}

	override fun searchLocationsAsync(query: String): Deferred<List<MapResult>> {
		val location = locationProvider.currentLocation
		val userPoint = location?.let { Point(it.latitude, it.longitude) }

		// Autosuggest gives the in-car menu its fast, forgiving list of options.
		// Full SearchManager.submit is reserved for resultInformationAsync
		// (invoked when the user selects a suggestion).
		val session = obtainSuggestSession()
		val suggestOptions = SuggestOptions().apply {
			suggestTypes = SuggestType.GEO.value or SuggestType.BIZ.value or SuggestType.TRANSIT.value
			if (userPoint != null) {
				setUserPosition(userPoint)
			}
		}

		// A world-spanning bounding box lets Yandex use its own default ranking
		// relative to userPosition without being artificially clipped. The gmap
		// flavor uses an effectively-point bounding box with the user position
		// as the bias; Yandex already treats userPosition as the bias so we
		// don't need to over-constrain bounds.
		val worldBounds = BoundingBox(Point(-89.0, -179.0), Point(89.0, 179.0))

		Log.i(TAG, "Starting Yandex suggest for \"$query\" near $userPoint")
		val deferred = CompletableDeferred<List<MapResult>>()

		try {
			session.suggest(query, worldBounds, suggestOptions, object : SuggestSession.SuggestListener {
				override fun onResponse(response: SuggestResponse) {
					val items = response.items ?: emptyList()
					Log.i(TAG, "Received ${items.size} suggest results for \"$query\"")
					val mapped = items.mapIndexedNotNull { index, item -> item.toMapResult(index) }
					// Replace the cache with fresh entries so resultInformationAsync can hit it.
					cachedResults.clear()
					mapped.forEach { cachedResults[it.id] = CachedResult(it.name, it.address, it.location) }
					deferred.complete(mapped)
				}

				override fun onError(error: com.yandex.runtime.Error) {
					Log.w(TAG, "Yandex suggest error for \"$query\": ${error.classifyMessage()}")
					deferred.complete(emptyList())
				}
			})
		} catch (e: Exception) {
			Log.w(TAG, "Yandex suggest threw synchronously for \"$query\": $e")
			deferred.complete(emptyList())
		}

		return deferred
	}

	override fun resultInformationAsync(resultId: String): Deferred<MapResult?> {
		// If we already have coordinates cached from the suggest pass, short-circuit.
		val cached = cachedResults[resultId]
		if (cached?.location != null) {
			return CompletableDeferred(
					MapResult(resultId, cached.title, cached.subtitle, location = cached.location)
			)
		}

		val searchText = cached?.title ?: resultId
		val userPoint = locationProvider.currentLocation?.let { Point(it.latitude, it.longitude) }
		val anchorGeometry = if (userPoint != null) Geometry.fromPoint(userPoint) else Geometry.fromBoundingBox(BoundingBox(Point(-89.0, -179.0), Point(89.0, 179.0)))
		val options = SearchOptions().apply {
			setSearchTypes(SearchType.GEO.value or SearchType.BIZ.value)
			setResultPageSize(1)
			if (userPoint != null) setUserPosition(userPoint)
		}
		val deferred = CompletableDeferred<MapResult?>()

		try {
			val session = searchManager.submit(searchText, anchorGeometry, options, object : Session.SearchListener {
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
			synchronized(pendingSearchSessions) { pendingSearchSessions += session }
		} catch (e: Exception) {
			Log.w(TAG, "Yandex searchManager.submit threw for \"$searchText\": $e")
			deferred.complete(null)
		}

		return deferred
	}

	// ---- Helpers ---------------------------------------------------------------------------------

	private fun SuggestItem.toMapResult(index: Int): MapResult? {
		val title = displayText ?: searchText ?: return null
		val subtitle = subtitle?.text ?: ""
		val center: Point? = try { center } catch (t: Throwable) { null }
		val latLong = center?.let { LatLong(it.latitude, it.longitude) }
		// Use searchText when available (stable id), otherwise fall back to a
		// synthetic title+index key. Either way it round-trips through the
		// cachedResults map below.
		val id = searchText ?: "${title}#$index"
		return MapResult(
				id = id,
				name = title,
				address = subtitle,
				location = latLong
		)
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
 * The error surface type hierarchy distinguishes `UnauthorizedError`, `ForbiddenError`,
 * `BadRequestError`, `NotFoundError`, `NetworkError`, `RemoteError`, etc.
 * We avoid pattern-matching on exact class names so MapKit version bumps don't break us.
 */
private fun com.yandex.runtime.Error.classifyMessage(): String =
		"${this::class.java.simpleName}(valid=${try { isValid } catch (_: Throwable) { "?" }})"
