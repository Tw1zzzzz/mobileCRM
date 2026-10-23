package com.pulse.android.platform

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient

enum class HealthCardState { AVAILABLE, NEEDS_INSTALL, UNAVAILABLE }

/** Health Connect is built into Android 14+; on Android 9–13 it is an app from Google Play that may need installing or updating. */
object HealthConnectAvailability {
    const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

    fun cardState(sdkStatus: Int): HealthCardState = when (sdkStatus) {
        HealthConnectClient.SDK_AVAILABLE -> HealthCardState.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthCardState.NEEDS_INSTALL
        else -> HealthCardState.UNAVAILABLE
    }

    fun state(context: Context): HealthCardState = cardState(HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE))

    /** Google Play page of the provider, opened on its onboarding after install. */
    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW, "market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding".toUri())
        .setPackage("com.android.vending")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
