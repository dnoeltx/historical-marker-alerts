package com.dnoel.markeralerts.trip

import com.dnoel.markeralerts.domain.TrackPoint
import kotlinx.coroutines.flow.Flow

/**
 * Where position fixes come from.
 *
 * This interface is the seam the whole design hangs on. Today the real
 * implementation is Fused Location while a foreground service runs. Moving to
 * always-on background monitoring later replaces only what is behind this
 * interface — the proximity rules, the alerting, and the UI never learn that
 * anything changed.
 *
 * It also makes the app drivable from a recorded route, which is the only
 * practical way to test a feature whose acceptance criteria is a 600-mile drive.
 */
interface LocationSource {
    fun positions(): Flow<TrackPoint>
}
