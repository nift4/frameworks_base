/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.util.concurrency

import android.os.Handler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

/**
 * Wrap an [Executor] in a mock [Handler] that execute when [Handler.post] is called, and throws an
 * exception otherwise. This is useful when a class requires a Handler only because Handlers are
 * used by ContentObserver, and no other methods are used.
 *
 * If the [executor] is a [DelayableExecutor], it also supports:
 * * [Handler.postDelayed] with a Runnable parameter
 * * [Handler.postAtTime] with a RunnableParameter
 */
fun mockExecutorHandler(executor: Executor): Handler {
    val handlerMock = Mockito.mock(Handler::class.java, RuntimeExceptionAnswer())
    val cancellations = ConcurrentHashMap<Runnable, MutableList<Cancellation>>()
    doAnswer { invocation: InvocationOnMock ->
            executor.execute(invocation.getArgument(0))
            true
        }
        .`when`(handlerMock)
        .post(any())
    if (executor is DelayableExecutor) {
        doAnswer { invocation: InvocationOnMock ->
                val runnable = invocation.getArgument<Runnable>(0)
                val uptimeMillis = invocation.getArgument<Long>(1)
                val token = Any()
                val canceller =
                    executor.executeAtTime(
                        {
                            cancellations.get(runnable)?.removeIf { it.token == token }
                            cancellations.remove(runnable, emptyList())
                            runnable.run()
                        },
                        uptimeMillis
                    )
                cancellations
                    .getOrPut(runnable) { CopyOnWriteArrayList() }
                    .add(Cancellation(token, canceller))
                true
            }
            .`when`(handlerMock)
            .postAtTime(any(), anyLong())
        doAnswer { invocation: InvocationOnMock ->
                val runnable = invocation.getArgument<Runnable>(0)
                val delayInMillis = invocation.getArgument<Long>(1)
                val token = Any()
                val canceller =
                    executor.executeDelayed(
                        {
                            cancellations.get(runnable)?.removeIf { it.token == token }
                            cancellations.remove(runnable, emptyList())
                            runnable.run()
                        },
                        delayInMillis
                    )
                cancellations
                    .getOrPut(runnable) { CopyOnWriteArrayList() }
                    .add(Cancellation(token, canceller))
                true
            }
            .`when`(handlerMock)
            .postDelayed(any(), anyLong())
        doAnswer { invocation: InvocationOnMock ->
                val runnable = invocation.getArgument<Runnable>(0)
                cancellations.remove(runnable)?.forEach(Runnable::run)
                Unit
            }
            .`when`(handlerMock)
            .removeCallbacks(any())
    }
    return handlerMock
}

private class Cancellation(val token: Any, canceller: Runnable) : Runnable by canceller

private class RuntimeExceptionAnswer : Answer<Any> {
    override fun answer(invocation: InvocationOnMock): Any {
        throw RuntimeException(invocation.method.name + " is not stubbed")
    }
}
