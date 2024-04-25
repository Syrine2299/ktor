/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.utils.io

import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.intrinsics.*
import kotlinx.io.*
import kotlinx.io.Buffer
import kotlinx.io.unsafe.*
import kotlin.coroutines.*
import kotlin.jvm.*

@OptIn(InternalAPI::class)
public suspend fun ByteWriteChannel.writeByte(value: Byte) {
    writeBuffer.writeByte(value)
    flush()
}

@OptIn(InternalAPI::class)
public suspend fun ByteWriteChannel.writeShort(value: Short) {
    writeBuffer.writeShort(value)
    flush()
}

@OptIn(InternalAPI::class)
public suspend fun ByteWriteChannel.writeInt(value: Int) {
    writeBuffer.writeInt(value)
    flush()
}

@OptIn(InternalAPI::class)
public suspend fun ByteWriteChannel.writeLong(value: Long) {
    writeBuffer.writeLong(value)
    flush()
}

@OptIn(InternalAPI::class)
public suspend fun ByteWriteChannel.writeByteArray(array: ByteArray) {
    writeBuffer.write(array)
    flush()
}

@OptIn(InternalAPI::class)
public suspend fun ByteWriteChannel.writeSource(source: Source) {
    writeBuffer.transferFrom(source)
    flush()
}

@OptIn(InternalAPI::class)
public suspend fun ByteWriteChannel.writeString(value: String) {
    writeBuffer.writeText(value)
    flush()
}

@OptIn(InternalAPI::class)
public suspend fun ByteWriteChannel.writeFully(value: ByteArray, offset: Int = 0, length: Int = value.size - offset) {
    writeBuffer.write(value, offset, offset + length)
    flush()
}

@OptIn(InternalAPI::class)
public suspend fun ByteWriteChannel.writeBuffer(value: Source) {
    writeBuffer.transferFrom(value)
    flush()
}

@OptIn(InternalAPI::class)
public suspend fun ByteWriteChannel.writeStringUtf8(value: String) {
    writeBuffer.writeText(value)
    flush()
}

@OptIn(InternalAPI::class)
public suspend fun ByteWriteChannel.writePacket(copy: Buffer) {
    writeBuffer.transferFrom(copy)
    flush()
}

@OptIn(InternalAPI::class)
public suspend fun ByteWriteChannel.writePacket(copy: Source) {
    writeBuffer.transferFrom(copy)
    flush()
}

public fun ByteWriteChannel.close(cause: Throwable?) {
    if (cause == null) {
        ::flushAndClose.fireAndForget()
    } else {
        cancel(cause)
    }
}

@JvmInline
public value class WriterScope(public val channel: ByteWriteChannel)

public interface ChannelJob {
    public val job: Job
}

public suspend fun ChannelJob.join() {
    job.join()
}

public fun ChannelJob.invokeOnCompletion(block: () -> Unit) {
    job.invokeOnCompletion { block() }
}

public fun ChannelJob.cancel(): Unit = job.cancel()

public class WriterJob internal constructor(
    public val channel: ByteReadChannel,
    public override val job: Job
) : ChannelJob

@Suppress("UNUSED_PARAMETER")
public fun CoroutineScope.writer(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    autoFlush: Boolean = false,
    block: suspend WriterScope.() -> Unit
): WriterJob = writer(coroutineContext, ByteChannel(), block)

public fun CoroutineScope.writer(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    channel: ByteChannel,
    block: suspend WriterScope.() -> Unit
): WriterJob {
    val job = launch(coroutineContext) {
        try {
            block(WriterScope(channel))
        } catch (cause: Throwable) {
            channel.cancel(cause)
        } finally {
            channel.flushAndClose()
        }
    }

    return WriterJob(channel, job)
}

/**
 * Await for [desiredSpace] will be available for write and invoke [block] function providing [Memory] instance and
 * the corresponding range suitable for wiring in the memory. The block function should return number of bytes were
 * written, possibly 0.
 *
 * Similar to [ByteReadChannel.read], this function may invoke block function with lesser memory range when the
 * specified [desiredSpace] is bigger that the buffer's capacity
 * or when it is impossible to represent all [desiredSpace] bytes as a single memory range
 * due to internal implementation reasons.
 */
@OptIn(SnapshotApi::class, UnsafeIoApi::class, InternalAPI::class, InternalIoApi::class)
public suspend fun ByteWriteChannel.write(
    desiredSpace: Int = 1,
    block: (Memory, Int, Int) -> Int
): Int {
    val before = writeBuffer.size
    UnsafeBufferAccessors.writeToTail(writeBuffer.buffer, desiredSpace, block)
    val after = writeBuffer.size
    val written = after - before
    flush()
    return written
}

public suspend fun ByteWriteChannel.awaitFreeSpace() {
    flush()
}

@OptIn(InternalCoroutinesApi::class)
internal fun <R> (suspend () -> R).fireAndForget() {
    this.startCoroutineCancellable(NO_CALLBACK)
}

private val NO_CALLBACK = object : Continuation<Any?> {
    override val context: CoroutineContext
        get() = EmptyCoroutineContext

    override fun resumeWith(result: Result<Any?>) {
    }
}
