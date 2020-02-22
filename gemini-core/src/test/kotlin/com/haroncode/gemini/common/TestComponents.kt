package com.haroncode.gemini.common

import com.haroncode.gemini.common.TestAction.*
import com.haroncode.gemini.common.TestEffect.*
import com.haroncode.gemini.core.elements.Middleware
import com.haroncode.gemini.core.elements.Reducer
import io.reactivex.Flowable
import io.reactivex.Scheduler
import java.util.concurrent.TimeUnit

/**
 * @author HaronCode.
 */

const val INITIAL_COUNTER = 10
const val INITIAL_LOADING = false

const val DELAYED_FULFILL_AMOUNT = 5

const val CONDITIONAL_MULTIPLIER = 10

data class TestState(
    val id: Long = 1L,
    val counter: Int = INITIAL_COUNTER,
    val loading: Boolean = INITIAL_LOADING
)

sealed class TestAction {
    object Unfulfillable : TestAction()
    object FulfillableInstantly : TestAction()
    data class FulfillableAsync(val delayMs: Long) : TestAction()
    object TranslatesTo3Effects : TestAction()
    object MaybeFulfillable : TestAction()
    object ActionForEvent : TestAction()
}

sealed class TestViewEvent {
    object SimpleEvent : TestViewEvent()
}

sealed class TestEffect {
    data class InstantEffect(val amount: Int) : TestEffect()
    object StartedAsync : TestEffect()
    data class FinishedAsync(val amount: Int) : TestEffect()
    object MultipleEffect1 : TestEffect()
    object MultipleEffect2 : TestEffect()
    object MultipleEffect3 : TestEffect()
    data class ConditionalThingHappened(val multiplier: Int) : TestEffect()
    object EffectForEvent : TestEffect()
}

class TestReducer : Reducer<TestState, TestEffect> {

    override fun invoke(state: TestState, effect: TestEffect): TestState = when (effect) {
        is InstantEffect -> state.copy(counter = state.counter + effect.amount)
        is FinishedAsync -> state.copy(counter = state.counter + effect.amount, loading = false)
        is ConditionalThingHappened -> state.copy(counter = state.counter * effect.multiplier)
        StartedAsync -> state.copy(loading = true)
        EffectForEvent -> state.copy()
        MultipleEffect1,
        MultipleEffect2,
        MultipleEffect3 -> state.copy(counter = state.counter + 1)
    }
}

class TestMiddleware(
    private val asyncWorkScheduler: Scheduler
) : Middleware<TestAction, TestState, TestEffect> {

    override fun invoke(action: TestAction, state: TestState): Flowable<TestEffect> = when (action) {
        Unfulfillable -> Flowable.empty()
        FulfillableInstantly -> Flowable.just(InstantEffect(1))
        ActionForEvent -> Flowable.just(EffectForEvent)
        is FulfillableAsync -> Flowable.just(DELAYED_FULFILL_AMOUNT)
            .delay(action.delayMs, TimeUnit.MILLISECONDS, asyncWorkScheduler)
            .map<TestEffect>(::FinishedAsync)
            .startWith(StartedAsync)
        TranslatesTo3Effects -> Flowable.just(
            MultipleEffect1,
            MultipleEffect2,
            MultipleEffect3
        )
        MaybeFulfillable ->
            if (state.counter % 3 == 0) Flowable.just<TestEffect>(
                ConditionalThingHappened(
                    CONDITIONAL_MULTIPLIER
                )
            )
            else Flowable.empty<TestEffect>()
    }
}
