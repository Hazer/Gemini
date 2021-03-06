package com.haroncode.gemini.android.strategies

import androidx.lifecycle.LifecycleOwner
import com.haroncode.gemini.android.LifecycleStrategy
import com.haroncode.gemini.android.extended.AndroidLifecycleEvent
import com.haroncode.gemini.connector.StoreLifecycle.Event

object ResumePauseStrategy : LifecycleStrategy {

    override fun handle(
        source: LifecycleOwner,
        lifecycleState: AndroidLifecycleEvent
    ) = when (lifecycleState) {
        AndroidLifecycleEvent.ON_RESUME -> Event.START
        AndroidLifecycleEvent.ON_PAUSE -> Event.STOP
        else -> null
    }
}
