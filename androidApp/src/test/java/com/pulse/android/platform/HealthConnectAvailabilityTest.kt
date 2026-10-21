package com.pulse.android.platform

import androidx.health.connect.client.HealthConnectClient
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthConnectAvailabilityTest {
    @Test fun installedProviderCanConnect() {
        assertEquals(HealthCardState.AVAILABLE, HealthConnectAvailability.cardState(HealthConnectClient.SDK_AVAILABLE))
    }

    @Test fun outdatedProviderOffersUpdate() {
        assertEquals(HealthCardState.NEEDS_INSTALL, HealthConnectAvailability.cardState(HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED))
    }

    @Test fun unsupportedDeviceIsUnavailable() {
        assertEquals(HealthCardState.UNAVAILABLE, HealthConnectAvailability.cardState(HealthConnectClient.SDK_UNAVAILABLE))
        assertEquals(HealthCardState.UNAVAILABLE, HealthConnectAvailability.cardState(-42))
    }
}
