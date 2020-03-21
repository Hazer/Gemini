package com.haroncode.gemini.core

import com.haroncode.gemini.common.*
import com.haroncode.gemini.common.TestAction.*
import com.haroncode.gemini.connection.BaseConnectionRule
import com.haroncode.gemini.connection.util.identityFlowableTransformer
import com.haroncode.gemini.connector.BaseStoreConnector
import com.haroncode.gemini.connector.StoreConnector
import com.haroncode.gemini.store.BaseStore
import io.reactivex.Flowable
import io.reactivex.observers.TestObserver
import io.reactivex.processors.PublishProcessor
import io.reactivex.schedulers.TestScheduler
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * @author HaronCode.
 */
class BaseStoreTest {

    private lateinit var baseStore: BaseStore<TestAction, TestState, TestViewEvent, TestEffect>
    private lateinit var asyncWorkScheduler: TestScheduler
    private lateinit var testBootstrapperProcessor: PublishProcessor<TestAction>

    private lateinit var testStoreView: TestStoreView
    private lateinit var testActionsProcessor: PublishProcessor<TestAction>
    private lateinit var testStatesObserver: TestObserver<TestState>

    private lateinit var testErrorHandler: TestErrorHandler<TestState>

    private lateinit var testConnector: StoreConnector

    @Before
    fun prepare() {
        asyncWorkScheduler = TestScheduler()
        testActionsProcessor = PublishProcessor.create()
        testBootstrapperProcessor = PublishProcessor.create()
        testStatesObserver = TestObserver()
        testErrorHandler = TestErrorHandler()

        testStoreView = TestStoreView(testActionsProcessor, testStatesObserver)
        baseStore = BaseStore(
            initialState = TestState(),
            reducer = TestReducer(),
            eventProducer = TestEventProducer(),
            bootstrapper = TestBootstrapper(testBootstrapperProcessor),
            middleware = TestMiddleware(asyncWorkScheduler),
            errorHandler = testErrorHandler
        )

        val storeToViewConnectionRule = BaseConnectionRule(
            consumer = testStoreView,
            publisher = baseStore,
            transformer = identityFlowableTransformer()
        )

        val viewToStoreConnectionRule = BaseConnectionRule(
            consumer = baseStore,
            publisher = testStoreView,
            transformer = identityFlowableTransformer()
        )

        testConnector = BaseStoreConnector(listOf(storeToViewConnectionRule, viewToStoreConnectionRule))
        testConnector.connect()
    }

    @Test
    fun `if there are no actions, feature only emits initial state`() {
        assertEquals(1, testStatesObserver.onNextEvents().size)
    }

    @Test
    fun `emitted initial state is correct`() {
        val state = testStatesObserver.onNextEvents().first()
        assertEquals(INITIAL_COUNTER, state.counter)
        assertEquals(INITIAL_LOADING, state.loading)
    }

    @Test
    fun `bootstrapper correct work after create store`() {
        val actions = listOf(
            Unfulfillable,
            Unfulfillable,
            FulfillableInstantly
        )

        actions.forEach(testBootstrapperProcessor::onNext)

        assertEquals(2, testStatesObserver.onNextEvents().size)
    }

    @Test
    fun `event producer doesn't react on all effects`() {
        val actions = listOf(
            FulfillableInstantly,
            FulfillableInstantly,
            FulfillableInstantly
        )

        val events = Flowable.fromPublisher(baseStore.eventSource).test()
        actions.forEach(testActionsProcessor::onNext)

        events.assertNoValues()
    }

    @Test
    fun `event producer reacts on special effect`() {
        val actions = listOf(
            FulfillableInstantly,
            ActionForEvent,
            FulfillableInstantly
        )

        val events = Flowable.fromPublisher(baseStore.eventSource).test()
        actions.forEach(testActionsProcessor::onNext)

        events.assertValueCount(1)
    }

    @Test
    fun `event doesn't save last`() {
        val actions = listOf(
            FulfillableInstantly,
            ActionForEvent,
            FulfillableInstantly
        )

        actions.forEach(testActionsProcessor::onNext)
        Flowable.fromPublisher(baseStore.eventSource).test().assertNoValues()
    }

    @Test
    fun `there should be no state emission besides the initial one for unfulfillable wishes`() {
        val actions = listOf(
            Unfulfillable,
            Unfulfillable,
            Unfulfillable
        )

        actions.forEach(testActionsProcessor::onNext)

        assertEquals(1, testStatesObserver.onNextEvents().size)
    }

    @Test
    fun `there should be the same amount of states as actions that translate 1 - 1 to effects plus one for initial state`() {
        val actions = listOf(
            FulfillableInstantly,
            FulfillableInstantly,
            FulfillableInstantly
        )

        actions.forEach(testActionsProcessor::onNext)

        assertEquals(1 + actions.size, testStatesObserver.onNextEvents().size)
    }

    @Test
    fun `there should be 3 times as many states as actions that translate 1 - 3 to effects plus one for initial state`() {
        val actions = listOf(
            TranslatesTo3Effects,
            TranslatesTo3Effects,
            TranslatesTo3Effects
        )

        actions.forEach(testActionsProcessor::onNext)

        assertEquals(1 + actions.size * 3, testStatesObserver.onNextEvents().size)
    }

    @Test
    fun `the number of state emissions should reflect the number of effects plus one for initial state in complex case`() {
        val actions = listOf(
            FulfillableInstantly, // maps to 1 effect
            FulfillableInstantly, // maps to 1 effect
            MaybeFulfillable, // maps to 1 in this case
            Unfulfillable, // maps to 0
            FulfillableInstantly, // maps to 1
            FulfillableInstantly, // maps to 1
            MaybeFulfillable, // maps to 0 in this case
            TranslatesTo3Effects // maps to 3
        )

        actions.forEach(testActionsProcessor::onNext)

        assertEquals(8 + 1, testStatesObserver.onNextEvents().size)
        val expectedState = TestState(
            counter = (INITIAL_COUNTER + 1 + 1) * 10 + 1 + 1 + 3 * 1,
            loading = false
        )
        assertEquals(expectedState, testStatesObserver.onNextEvents().last())
    }

    @Test
    fun `there should be no state emission after store destroying`() {
        val mockServerDelayMs = 10L

        testActionsProcessor.onNext(FulfillableAsync(mockServerDelayMs))

        assertEquals(2, testStatesObserver.onNextEvents().size)

        val stateBeforeDestroy = testStatesObserver.onNextEvents().last()

        assertEquals(INITIAL_COUNTER, stateBeforeDestroy.counter)
        assertEquals(true, stateBeforeDestroy.loading)

        asyncWorkScheduler.advanceTimeBy(5, TimeUnit.MILLISECONDS)

        testConnector.dispose()
        baseStore.dispose()

        asyncWorkScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        val stateAfterDestroy = testStatesObserver.onNextEvents().last()

        assertEquals(INITIAL_COUNTER, stateAfterDestroy.counter)
        assertEquals(true, stateAfterDestroy.loading)
    }

    @Test
    fun `last state should be delivered after view rebind`() {
        val mockServerDelayMs = 10L

        testActionsProcessor.onNext(FulfillableAsync(mockServerDelayMs))

        assertEquals(2, testStatesObserver.onNextEvents().size)

        val stateBeforeUnbind = testStatesObserver.onNextEvents().last()

        assertEquals(INITIAL_COUNTER, stateBeforeUnbind.counter)
        assertEquals(true, stateBeforeUnbind.loading)

        asyncWorkScheduler.advanceTimeBy(5, TimeUnit.MILLISECONDS)

        testConnector.disconnect()

        asyncWorkScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testConnector.connect()

        val stateAfterRebind = testStatesObserver.onNextEvents().last()

        assertEquals(INITIAL_COUNTER + DELAYED_FULFILL_AMOUNT, stateAfterRebind.counter)
        assertEquals(false, stateAfterRebind.loading)
    }

    @Test
    fun `error handler handles exception in middleware`() {
        testActionsProcessor.onNext(FulfillableInstantly)

        val expectedState = TestState(
            counter = INITIAL_COUNTER + 1,
            loading = false
        )

        assertEquals(expectedState, testStatesObserver.onNextEvents().last())

        testActionsProcessor.onNext(LeadsToExceptionInMiddleware)

        assertEquals(expectedState, testErrorHandler.lastState)
        assertTrue(testErrorHandler.lastThrowable is IOException)

        testActionsProcessor.onNext(FulfillableInstantly)

        val expectedStateAfterErrorHandling = expectedState.copy(counter = expectedState.counter + 1)

        assertEquals(expectedStateAfterErrorHandling, testStatesObserver.onNextEvents().last())
    }

    @Test
    fun `error handler handles exception in reducer`() {
        testActionsProcessor.onNext(FulfillableInstantly)

        val expectedState = TestState(
            counter = INITIAL_COUNTER + 1,
            loading = false
        )

        assertEquals(expectedState, testStatesObserver.onNextEvents().last())

        testActionsProcessor.onNext(TranslatesToExceptionInReducer)

        assertEquals(expectedState, testErrorHandler.lastState)
        assertTrue(testErrorHandler.lastThrowable is IllegalStateException)

        testActionsProcessor.onNext(FulfillableInstantly)

        val expectedStateAfterErrorHandling = expectedState.copy(counter = expectedState.counter + 1)

        assertEquals(expectedStateAfterErrorHandling, testStatesObserver.onNextEvents().last())
    }
}
