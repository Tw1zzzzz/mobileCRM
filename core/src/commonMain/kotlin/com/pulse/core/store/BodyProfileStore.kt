package com.pulse.core.store

import com.pulse.core.model.BodyProfile
import com.pulse.core.model.User
import com.pulse.core.storage.JsonFileStorage

/** Birth date, height and weight from «О тебе» («body-profile.json»); demo players get a fixed demo profile. */
class BodyProfileStore(private val storage: JsonFileStorage<BodyProfile>?) : PulseStore<BodyProfile?>(storage?.load()) {
    val profile: BodyProfile? get() = current

    fun reload(user: User?) {
        current = storage?.load() ?: if (user?.isDemo == true) BodyProfile.demo else null
    }

    fun save(profile: BodyProfile) {
        current = profile
        storage?.save(profile)
    }

    companion object {
        const val FILE = "body-profile.json"
    }
}
