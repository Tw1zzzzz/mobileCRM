package com.pulse.android.platform

import kotlin.time.Instant

/** A usage event reduced to what screen time needs (Android `UsageEvents.Event`). */
data class RawUsageEvent(val packageName: String, val type: Type, val time: Instant, val className: String = "") {
    enum class Type { RESUMED, PAUSED, SCREEN_OFF, UNLOCKED }
}

data class PackageSession(val packageName: String, val start: Instant, val end: Instant)

/**
 * Foreground sessions from resume / pause events. Only one app is in the foreground, and the system does not always
 * log the pause of the previous one, so another app's resume closes it. An app may have several activities resumed
 * (a player over its main screen): its session ends when the last of them pauses. A screen-off closes whatever is
 * open; an app still open runs until now.
 */
object UsageEventsParser {
    fun sessions(events: List<RawUsageEvent>, now: Instant): List<PackageSession> {
        val open = linkedMapOf<String, Instant>()
        val resumed = mutableMapOf<String, MutableSet<String>>()
        val result = mutableListOf<PackageSession>()
        fun close(pkg: String, at: Instant) {
            resumed.remove(pkg)
            val start = open.remove(pkg) ?: return
            if (at > start) result += PackageSession(pkg, start, at)
        }
        for (event in events.sortedBy { it.time }) when (event.type) {
            RawUsageEvent.Type.RESUMED -> {
                open.keys.filter { it != event.packageName }.forEach { close(it, event.time) }
                open.putIfAbsent(event.packageName, event.time)
                resumed.getOrPut(event.packageName) { mutableSetOf() } += event.className
            }
            RawUsageEvent.Type.PAUSED -> {
                val activities = resumed[event.packageName]
                activities?.remove(event.className)
                if (activities.isNullOrEmpty()) close(event.packageName, event.time)
            }
            RawUsageEvent.Type.SCREEN_OFF -> open.keys.toList().forEach { close(it, event.time) }
            RawUsageEvent.Type.UNLOCKED -> Unit
        }
        open.keys.toList().forEach { close(it, now) }
        return result.sortedBy { it.start }
    }

    fun unlocks(events: List<RawUsageEvent>): List<Instant> =
        events.filter { it.type == RawUsageEvent.Type.UNLOCKED }.map { it.time }.sorted()
}
