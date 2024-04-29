/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("DEPRECATION")

package io.ktor.utils.io

import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.io.*
import kotlinx.io.Buffer
import kotlinx.io.unsafe.*
import kotlin.coroutines.*
import kotlin.jvm.*


@OptIn(InternalAPI::class)
public val ByteWriteChannel.availableForWrite: Int
    get() = CHANNEL_MAX_SIZE - writeBuffer.size

public suspend fun ByteReadChannel.toByteArray(): ByteArray {
    return readBuffer().readBytes()
}

@OptIn(InternalAPI::class)
public suspend fun ByteReadChannel.readByte(): Byte {
    if (readBuffer.exhausted()) {
        awaitContent()
    }

    if (readBuffer.exhausted()) {
        throw EOFException("Not enough data available")
    }

    return readBuffer.readByte()
}

public suspend fun ByteReadChannel.readShort(): Short {
    TODO("Not yet implemented")
}

@OptIn(InternalAPI::class)
public suspend fun ByteReadChannel.readInt(): Int {
    while (availableForRead < 4 && awaitContent()) {
    }

    if (availableForRead < 4) throw EOFException("Not enough data available")

    return readBuffer.readInt()
}

@OptIn(InternalAPI::class)
public suspend fun ByteReadChannel.readLong(): Long {
    while (availableForRead < 8 && awaitContent()) {
    }

    if (availableForRead < 8) throw EOFException("Not enough data available")
    return readBuffer.readLong()
}

@OptIn(InternalAPI::class)
public suspend fun ByteReadChannel.readBuffer(): Buffer {
    val result = Buffer()
    while (!isClosedForRead) {
        result.transferFrom(readBuffer)
        awaitContent()
    }

    closedCause?.let { throw it }

    return result
}

@OptIn(InternalAPI::class)
public suspend fun ByteReadChannel.readBuffer(max: Int): Buffer {
    val result = Buffer()
    var remaining = max

    while (remaining > 0 && !isClosedForRead) {
        if (readBuffer.exhausted()) awaitContent()

        val size = minOf(remaining.toLong(), readBuffer.remaining)
        readBuffer.readTo(result, size)
        remaining -= size.toInt()
    }

    return result
}

@OptIn(InternalAPI::class)
public suspend fun ByteReadChannel.copyAndClose(channel: ByteWriteChannel, limit: Long = Long.MAX_VALUE): Long {
    var result = 0L
    try {
        if (limit == Long.MAX_VALUE) {
            while (!isClosedForRead) {
                result += readBuffer.transferTo(channel.writeBuffer)
                channel.flush()
                awaitContent()
            }
        } else {
            TODO()
        }

        closedCause?.let { throw it }
    } catch (cause: Throwable) {
        cancel(cause)
        channel.close(cause)
        throw cause
    } finally {
        channel.flushAndClose()
    }

    return result
}

@OptIn(InternalAPI::class)
public suspend fun ByteReadChannel.readUTF8Line(): String? {
    var lfIndex = readBuffer.indexOf('\n'.code.toByte())
    if (lfIndex >= 0) {
        return readBuffer.readLine()
    }

    val tmp = Buffer()

    while (lfIndex < 0 && !isClosedForRead) {
        readBuffer.transferTo(tmp)
        awaitContent()

        lfIndex = readBuffer.indexOf('\n'.code.toByte())
    }

    if (tmp.exhausted() && readBuffer.exhausted()) return null

    return buildString {
        if (!tmp.exhausted()) append(tmp.readLine())
        if (!readBuffer.exhausted()) append(readBuffer.readLine())
    }
}

@OptIn(InternalAPI::class)
public suspend fun ByteReadChannel.copyTo(channel: ByteWriteChannel): Long {
    var result = 0L
    try {
        while (!isClosedForRead) {
            result += readBuffer.transferTo(channel.writeBuffer)
            channel.flush()
            awaitContent()
        }
    } catch (cause: Throwable) {
        cancel(cause)
        channel.close(cause)
        throw cause
    } finally {
        channel.flush()
    }

    return result
}

@OptIn(InternalAPI::class)
public suspend fun ByteReadChannel.copyTo(channel: ByteWriteChannel, limit: Long): Long {
    var remaining = limit
    try {
        while (!isClosedForRead && remaining > 0) {
            if (readBuffer.exhausted()) awaitContent()
            val count = minOf(remaining, readBuffer.remaining)
            readBuffer.readTo(channel.writeBuffer, count)
            remaining -= count
            channel.flush()
        }
    } catch (cause: Throwable) {
        cancel(cause)
        channel.close(cause)
        throw cause
    } finally {
        channel.flush()
    }

    return limit - remaining
}

public fun ByteReadChannel.split(): Pair<ByteReadChannel, ByteReadChannel> {
    TODO("Not yet implemented")
}

public suspend fun ByteReadChannel.readByteArray(count: Int): ByteArray = buildPacket {
    while (size < count) {
        val packet = readPacket(count - size)
        writePacket(packet)
    }
}.readByteArray()

@Suppress("DEPRECATION")
@OptIn(InternalAPI::class, InternalIoApi::class)
public suspend fun ByteReadChannel.readRemaining(): ByteReadPacket {
    val result = BytePacketBuilder()
    while (!isClosedForRead) {
        result.transferFrom(readBuffer)
        awaitContent()
    }

    return result.buffer
}

@OptIn(InternalAPI::class, InternalIoApi::class)
public suspend fun ByteReadChannel.readRemaining(max: Long): ByteReadPacket {
    val result = BytePacketBuilder()
    var remaining = max
    while (!isClosedForRead && remaining > 0) {
        if (remaining >= readBuffer.remaining) {
            remaining -= readBuffer.remaining
            readBuffer.transferTo(result)
        } else {
            readBuffer.readTo(result, remaining)
            remaining = 0
        }

        awaitContent()
    }

    return result.buffer
}

/**
 * Reads all available bytes to [dst] buffer and returns immediately or suspends if no bytes available
 * @return number of bytes were read or `-1` if the channel has been closed
 */
@OptIn(InternalAPI::class)
public suspend fun ByteReadChannel.readAvailable(
    buffer: ByteArray,
    offset: Int = 0,
    length: Int = buffer.size - offset
): Int {
    if (isClosedForRead) return -1
    if (readBuffer.exhausted()) awaitContent()
    if (isClosedForRead) return -1

    return readBuffer.readAvailable(buffer, offset, length)
}

/**
 * Invokes [block] if it is possible to read at least [min] byte
 * providing buffer to it so lambda can read from the buffer
 * up to [Buffer.readRemaining] bytes. If there are no [min] bytes available then the invocation returns -1.
 *
 * Warning: it is not guaranteed that all of available bytes will be represented as a single byte buffer
 * eg: it could be 4 bytes available for read but the provided byte buffer could have only 2 available bytes:
 * in this case you have to invoke read again (with decreased [min] accordingly).
 *
 * @param min amount of bytes available for read, should be positive
 * @param block to be invoked when at least [min] bytes available
 *
 * @return number of consumed bytes or -1 if the block wasn't executed.
 */
@OptIn(InternalAPI::class, InternalIoApi::class)
public fun ByteReadChannel.readAvailable(min: Int, block: (Buffer) -> Unit): Int {
    require(min > 0) { "min should be positive" }
    require(min <= CHANNEL_MAX_SIZE) { "Min($min) shouldn't be greater than $CHANNEL_MAX_SIZE" }

    if (availableForRead < min) return -1
    val before = readBuffer.remaining
    block(readBuffer.buffer)
    check(before >= readBuffer.remaining) { "Buffer was corrupted by the block: $before -> ${readBuffer.remaining}" }
    return (before - readBuffer.remaining).toInt()
}

@JvmInline
public value class ReaderScope(public val channel: ByteReadChannel)

public class ReaderJob internal constructor(
    public val channel: ByteWriteChannel,
    public override val job: Job
) : ChannelJob

@Suppress("UNUSED_PARAMETER")
public fun CoroutineScope.reader(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    autoFlush: Boolean = false,
    block: suspend ReaderScope.() -> Unit
): ReaderJob = reader(coroutineContext, ByteChannel(), block)

public fun CoroutineScope.reader(
    coroutineContext: CoroutineContext,
    channel: ByteChannel,
    block: suspend ReaderScope.() -> Unit
): ReaderJob {
    val job = launch(coroutineContext) {
        try {
            block(ReaderScope(channel))
        } catch (cause: Throwable) {
            channel.close(cause)
        } finally {
            channel.flushAndClose()
        }
    }

    return ReaderJob(channel, job)
}

/**
 * Reads a packet of [packet] bytes from the channel.
 *
 * @throws EOFException if the channel is closed before the packet is fully read.
 */
@Suppress("DEPRECATION")
@OptIn(InternalAPI::class)
public suspend fun ByteReadChannel.readPacket(packet: Int): ByteReadPacket {
    val result = Buffer()
    while (result.size < packet) {
        if (readBuffer.exhausted()) awaitContent()
        if (isClosedForRead) break

        if (readBuffer.remaining > packet - result.size) {
            readBuffer.readTo(result, packet - result.size)
        } else {
            readBuffer.transferTo(result)
        }
    }

    if (result.size < packet) {
        throw EOFException("Not enough data available, required $packet bytes but only ${result.size} available")
    }
    return result
}

public suspend fun ByteReadChannel.discardExact(value: Long) {
    if (discard(value) < value) throw EOFException("Unable to discard $value bytes")
}

@OptIn(InternalAPI::class)
public suspend fun ByteReadChannel.discard(max: Long = Long.MAX_VALUE): Long {
    var remaining = max
    while (remaining > 0 && !isClosedForRead) {
        if (availableForRead == 0) awaitContent()
        val count = minOf(remaining, readBuffer.remaining)
        readBuffer.discard(count)

        remaining -= count
    }

    return max - remaining
}

@OptIn(InternalAPI::class)
public suspend fun ByteReadChannel.readUTF8LineTo(out: Appendable, max: Int): Boolean {
    if (readBuffer.exhausted()) awaitContent()
    if (isClosedForRead) return false

    var cr = readBuffer.indexOf('\r'.code.toByte())
    var lf = readBuffer.indexOf('\n'.code.toByte())
    var searchEnd = readBuffer.remaining
    while (cr < 0 && lf < 0 && readBuffer.remaining < max && awaitContent()) {
        cr = readBuffer.indexOf('\r'.code.toByte(), searchEnd)
        lf = readBuffer.indexOf('\n'.code.toByte(), searchEnd)
        searchEnd = readBuffer.remaining
    }

    if (cr < 0 && lf < 0) {
        val count = minOf(max.toLong(), readBuffer.remaining)
        out.append(readBuffer.readString(count))
    } else {
        val eol = when {
            cr >= 0 && lf >= 0 -> minOf(cr, lf)
            cr >= 0 -> cr
            else -> lf
        }

        out.append(readBuffer.readString(eol))
        readBuffer.readByte()
        if (eol == cr && lf == cr + 1) readBuffer.readByte()
    }

    return true
}

@OptIn(InternalAPI::class, SnapshotApi::class, UnsafeIoApi::class, InternalIoApi::class)
public suspend fun ByteReadChannel.read(block: suspend (Memory, Int, Int) -> Int): Int {
    if (isClosedForRead) return -1
    if (readBuffer.exhausted()) awaitContent()
    if (isClosedForRead) return -1

    var result = 0
    UnsafeBufferAccessors.readFromHead(readBuffer.buffer) { array, start, endExclusive ->
        result = block(array, start, endExclusive)
        result
    }

    return result
}

@OptIn(InternalAPI::class, InternalIoApi::class)
public val ByteReadChannel.availableForRead: Int
    get() = readBuffer.buffer.size.toInt()

@OptIn(InternalAPI::class)
public suspend fun ByteReadChannel.readFully(out: ByteArray) {
    while (availableForRead < out.size) awaitContent()
    if (availableForRead < out.size) throw EOFException("Not enough data available")

    readBuffer.readTo(out)
}
