package com.anytypeio.anytype.domain.account

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance

interface AwaitAccountStartManager {

    fun state(): Flow<State>
    fun setState(state: State)
    fun awaitStart(): Flow<State.Started>
    fun awaitStopped(): Flow<State.Stopped>

    /** Synchronous snapshot: true once the account has started for this session. */
    fun hasStarted(): Boolean

    /**
     * Grows on every [setState] call, a repeated one included: every account start and stop
     * reports here, even a restart that leaves the state at [State.Started], which [state]
     * would not emit again.
     */
    fun generation(): Long

    object Default: AwaitAccountStartManager {
        private val state = MutableStateFlow<State>(State.Init)
        private val generation = AtomicLong()
        override fun state(): Flow<State> = state
        override fun setState(state: State) {
            generation.incrementAndGet()
            this.state.value = state
        }
        override fun generation(): Long = generation.get()
        override fun awaitStart(): Flow<State.Started> = state.filterIsInstance()
        override fun awaitStopped(): Flow<State.Stopped> = state.filterIsInstance()
        override fun hasStarted(): Boolean = state.value is State.Started
    }

    sealed class State {
        data object Init : State()
        data object Started : State()
        data object Stopped : State()
    }
}