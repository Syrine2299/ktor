/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.utils.io

import io.ktor.utils.io.core.*
import kotlinx.cinterop.*
import kotlinx.io.*
import kotlinx.io.unsafe.*
import platform.posix.*

@OptIn(ExperimentalForeignApi::class, SnapshotApi::class, UnsafeIoApi::class, InternalIoApi::class)
public fun Input.readAvailable(cPointer: CPointer<ByteVar>, offset: Int, length: Int): Int {
    var result = 0
    UnsafeBufferAccessors.readFromHead(buffer) { array, startOffset, endExclusive ->
        val size = minOf(endExclusive - startOffset, length)
        array.usePinned { pinned ->
            memcpy(cPointer + offset, pinned.addressOf(startOffset), size.convert())
        }
        result = size
        size
    }

    return result
}
