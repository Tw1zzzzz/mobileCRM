package com.pulse.core.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Base for the app stores: one immutable state value, replaced on every change.
 * Screens collect [state] to redraw; queries read the current value.
 */
abstract class PulseStore<S>(initial: S) {
    private val mutable = MutableStateFlow(initial)
    val state: StateFlow<S> = mutable.asStateFlow()

    protected var current: S
        get() = mutable.value
        set(value) {
            mutable.value = value
        }
}
