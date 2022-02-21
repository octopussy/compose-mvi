package com.soulplatform.pure.screen

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

private const val TAG = "MVI_CORE"

interface MVIProcessor<TViewState : MVIViewState, TEvent : MVIViewEvent, TEffect : MVIViewSideEffect> {
    val viewState: StateFlow<TViewState>
    val effects: Flow<TEffect>
    fun sendEvent(event: TEvent)
}

abstract class MVIBaseViewModel<
        TState : MVIState,
        TStateChange : MVIStateChange,
        TViewState : MVIViewState,
        TEvent : MVIViewEvent,
        TEffect : MVIViewSideEffect>(
        private val reducer: MVIReducer<TState, TStateChange>,
        private val mapper: MVIStateToViewMapper<TState, TViewState>
) : ViewModel(),
        MVIProcessor<TViewState, TEvent, TEffect>, CoroutineScope {

    private val modelName = javaClass.simpleName

    override val coroutineContext =
        SupervisorJob() +
                Dispatchers.Main.immediate +
                CoroutineExceptionHandler { _, error -> onError(error) }

    protected abstract val initialState: TState

    protected val state by lazy { MutableStateFlow(initialState) }

    private val _changes: Channel<TStateChange> = Channel()
    private val _effects: Channel<TEffect> = Channel()
    private val _events: MutableSharedFlow<TEvent> = MutableSharedFlow()

    override val viewState: StateFlow<TViewState> by lazy {
        state.map { mapper.map(it) }.stateIn(
                this,
                WhileSubscribed(5_000),
                mapper.map(state.value) // TODO: ugly shit, do something
        )
    }

    override val effects = _effects.receiveAsFlow()

    init {
        subscribeChanges()
        subscribeEvents()
    }

    abstract fun handleEvents(event: TEvent)

    final override fun sendEvent(event: TEvent) {
        launch { _events.emit(event) }
    }

    protected fun postChange(change: TStateChange) {
        launch { _changes.send(change) }
    }

    protected fun setEffect(builder: () -> TEffect) {
        val effectValue = builder()
        launch { _effects.send(effectValue) }
    }

    protected fun onError(error: Throwable, logOnly: Boolean = false) {
        Timber.tag(TAG).e(error, modelName)
        if (!logOnly) {
            // TODO
            //workers.observeWorker.scheduleDirect { errorHandler.startErrorHandling(error) }
        }
    }

    private fun subscribeChanges() {
        launch(Dispatchers.Default) {
            _changes.receiveAsFlow().collect { change ->
                state.update { reducer.reduce(it, change) }
            }
        }
    }

    private fun subscribeEvents() {
        launch {
            _events.collect {
                handleEvents(it)
            }
        }
    }
}

interface MVIStateToViewMapper<TState : MVIState, TViewState : MVIViewState> {
    fun map(state: TState): TViewState
}

interface MVIState

interface MVIStateChange

interface MVIViewState

interface MVIViewEvent

interface MVIViewSideEffect

interface MVIReducer<S : MVIState, C : MVIStateChange> {
    fun reduce(state: S, change: C): S
}
