/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.utils.io

import kotlinx.io.*

@Deprecated(
    "Counter is no longer available on the regular ByteReadChannel. Use CounterByteReadChannel instead.",
    level = DeprecationLevel.ERROR,
    replaceWith = ReplaceWith("this.counted().totalBytesRead")
)
public val ByteReadChannel.totalBytesRead: Long
    get() = error("Counter is no longer available on the regular ByteReadChannel. Use CounterByteReadChannel instead.")


public fun ByteReadChannel.counted(): CountedByteReadChannel =
    CountedByteReadChannel(this)

public class CountedByteReadChannel(public val delegate: ByteReadChannel) : ByteReadChannel {

    public val totalBytesRead: Long
        get() = TODO()

    override val closedCause: Throwable?
        get() = TODO("Not yet implemented")

    override val isClosedForRead: Boolean
        get() = TODO("Not yet implemented")

    @InternalAPI
    override val readBuffer: Source
        get() = TODO("Not yet implemented")

    override suspend fun awaitContent(): Boolean {
        TODO("Not yet implemented")
    }

    override fun cancel(cause: Throwable?) {
        TODO("Not yet implemented")
    }
}
