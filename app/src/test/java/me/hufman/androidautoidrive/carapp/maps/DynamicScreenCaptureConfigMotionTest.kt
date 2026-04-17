package me.hufman.androidautoidrive.carapp.maps

import io.bimmergestalt.idriveconnectkit.GenericRHMIDimensions
import me.hufman.androidautoidrive.carapp.music.MusicAppMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the motion-adaptive JPEG quality ladder. 2026-04-15 aggressive pass:
 * penalties ramp up earlier and deeper to free encode budget on the pipeline.
 *
 * Tiers: 0 / 10 / 20 / 30 at speed thresholds 20 / 40 / 70 km/h, with
 * ~10 km/h hysteresis on each boundary. The state machine steps one band per
 * call, so going from stopped to highway requires several updates.
 *
 * USB baseline (normal 65, interaction 40): quality = 65 / 55 / 45 / 35.
 * BT baseline (normal 40, interaction 12): 40 / 30 / 20 / 10 (MIN clamp).
 */
class DynamicScreenCaptureConfigMotionTest {
	private var mockTime: Long = 1_000_000L

	private fun makeConfig() = DynamicScreenCaptureConfig(
			fullDimensions = GenericRHMIDimensions(1280, 480),
			carTransport = MusicAppMode.TRANSPORT_PORTS.USB,
			timeProvider = { mockTime },
	)

	@Test
	fun nullSpeedForcesFullQuality() {
		val cfg = makeConfig()
		cfg.updateMotionSpeed(85f)
		cfg.updateMotionSpeed(85f)
		cfg.updateMotionSpeed(85f)
		cfg.updateMotionSpeed(null)
		assertEquals(65, cfg.compressQuality)
	}

	@Test
	fun stationarySpeedStaysFullQuality() {
		val cfg = makeConfig()
		cfg.updateMotionSpeed(0f)
		assertEquals(65, cfg.compressQuality)
	}

	@Test
	fun parkingSpeedsStayFullQuality() {
		// Below the 20 km/h entry threshold — carpark crawl keeps full quality.
		val cfg = makeConfig()
		cfg.updateMotionSpeed(18f)
		assertEquals(65, cfg.compressQuality)
	}

	@Test
	fun climbsToFirstPenaltyAfter20Threshold() {
		val cfg = makeConfig()
		cfg.updateMotionSpeed(18f)
		assertEquals(65, cfg.compressQuality)
		cfg.updateMotionSpeed(25f)
		// First band: penalty 10, USB = 65 - 10 = 55.
		assertEquals(55, cfg.compressQuality)
	}

	@Test
	fun oscillationAtLowBoundaryDoesNotFlap() {
		val cfg = makeConfig()
		// 18 ↔ 25 straddles the 20 km/h entry seam. Once we've entered the
		// 10-penalty band, dropping to 18 should NOT flap back to 0 because
		// the exit threshold is 15 km/h.
		cfg.updateMotionSpeed(25f)  // → penalty 10
		assertEquals(55, cfg.compressQuality)
		cfg.updateMotionSpeed(18f)  // stays in band (exit is <15)
		assertEquals(55, cfg.compressQuality)
		cfg.updateMotionSpeed(25f)
		assertEquals(55, cfg.compressQuality)
	}

	@Test
	fun slowingBelowFifteenReturnsFullQuality() {
		val cfg = makeConfig()
		cfg.updateMotionSpeed(25f)  // penalty 10
		assertEquals(55, cfg.compressQuality)
		cfg.updateMotionSpeed(10f)  // < 15 exits back to 0
		assertEquals(65, cfg.compressQuality)
	}

	@Test
	fun highSpeedReachesLowestQualityBand() {
		val cfg = makeConfig()
		// State machine steps one tier per call, walk it up.
		cfg.updateMotionSpeed(25f)  // 0 → 10
		cfg.updateMotionSpeed(45f)  // 10 → 20
		cfg.updateMotionSpeed(75f)  // 20 → 30
		assertEquals(35, cfg.compressQuality)  // USB 65 - 30
	}

	@Test
	fun droppingBelowSixtyLeavesHighestBand() {
		val cfg = makeConfig()
		cfg.updateMotionSpeed(25f)  // 0 → 10
		cfg.updateMotionSpeed(45f)  // 10 → 20
		cfg.updateMotionSpeed(75f)  // 20 → 30
		assertEquals(35, cfg.compressQuality)
		// Still within 30's "stay here" range (>= 60).
		cfg.updateMotionSpeed(62f)
		assertEquals(35, cfg.compressQuality)
		// Crosses the exit threshold.
		cfg.updateMotionSpeed(55f)
		assertEquals(45, cfg.compressQuality)  // USB 65 - 20
	}

	@Test
	fun interactionOverridesHighSpeed() {
		val cfg = makeConfig()
		cfg.updateMotionSpeed(25f)
		cfg.updateMotionSpeed(45f)
		cfg.updateMotionSpeed(75f)
		assertEquals(35, cfg.compressQuality)
		// User pinches: interaction forces penalty to 0 and uses the lower
		// interaction base. USB interaction base = 40, observed = 40.
		cfg.startInteraction(5_000)
		cfg.updateMotionSpeed(75f)
		assertEquals(40, cfg.compressQuality)
	}

	@Test
	fun interactionTimeoutLetsQualityDropAgain() {
		val cfg = makeConfig()
		cfg.startInteraction(5_000)
		cfg.updateMotionSpeed(75f)
		assertEquals(40, cfg.compressQuality)  // interaction base, penalty 0
		mockTime += 6_000L
		// Interaction expired. First call from penalty 0 with 75 km/h → step
		// up to 10 (only one band per call), 65 - 10 = 55.
		cfg.updateMotionSpeed(75f)
		assertEquals(55, cfg.compressQuality)
		cfg.updateMotionSpeed(75f)
		assertEquals(45, cfg.compressQuality)  // 10 → 20, 65 - 20
		cfg.updateMotionSpeed(75f)
		assertEquals(35, cfg.compressQuality)  // 20 → 30, 65 - 30
	}

	@Test
	fun bluetoothTransportFloorsAtMinimumQuality() {
		val cfg = DynamicScreenCaptureConfig(
				fullDimensions = GenericRHMIDimensions(1280, 480),
				carTransport = MusicAppMode.TRANSPORT_PORTS.BT,
				timeProvider = { mockTime },
		)
		// BT normal baseline = 40. Walk the state machine up to penalty 30.
		cfg.updateMotionSpeed(25f)  // 0 → 10
		cfg.updateMotionSpeed(45f)  // 10 → 20
		cfg.updateMotionSpeed(75f)  // 20 → 30
		// 40 - 30 = 10 — sits exactly at the MIN_JPEG_QUALITY floor.
		assertEquals(10, cfg.compressQuality)

		// BT interaction baseline = 12, penalty forced to 0 → observed 12.
		cfg.startInteraction(5_000)
		cfg.updateMotionSpeed(75f)
		assertEquals(12, cfg.compressQuality)
	}
}
