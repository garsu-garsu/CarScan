package com.bruni.carscan.core.common.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The whole of this app's state management.
 *
 * Deliberately not a library. The complexity in CarScan lives in the wire protocol — a
 * half-duplex conversation with counterfeit hardware — not in moving values into a screen.
 * An MVI framework would add an abstraction layer over the thirty lines below and put itself
 * between a developer and a stack trace, and buy nothing in return.
 *
 * [S] is the state a screen renders. [I] is something the user did. [E] is a one-shot event —
 * navigate, show a snackbar, ask for a permission — which is emphatically *not* state: a
 * navigation that lives in state fires again on rotation, and that is a bug you find in the
 * store's reviews rather than in a test.
 */
abstract class MviViewModel<S, I, E>(initialState: S) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    /**
     * Effects are conflated into a small buffer rather than dropped or suspended: the UI may not
     * be listening at the instant one is emitted (backgrounded, mid-recomposition), and a
     * suspending emit here would stall whatever coroutine produced it.
     */
    private val effects = Channel<E>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val effect: Flow<E> = effects.receiveAsFlow()

    protected val scope: CoroutineScope get() = viewModelScope

    /** The reducer. Implement as a single `when (intent)`. */
    abstract fun onIntent(intent: I)

    /** Reduce the current state. The only way state changes. */
    protected fun setState(reducer: S.() -> S) {
        _state.value = _state.value.reducer()
    }

    protected fun emitEffect(e: E) {
        effects.trySend(e)
    }

    /** Collect a flow into state for as long as the screen is alive. */
    protected fun <T> Flow<T>.collectIntoState(onEach: (T) -> Unit) {
        scope.launch { collect { onEach(it) } }
    }
}
