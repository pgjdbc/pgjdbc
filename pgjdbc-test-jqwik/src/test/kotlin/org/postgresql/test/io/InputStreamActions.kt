package org.postgresql.test.io

import net.jqwik.api.stateful.Action
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import java.io.InputStream
import java.util.concurrent.ThreadLocalRandom

class InputStreamState(
    val dataLength: Int,
    val bufferSize: Int,
    val limit: Long,
    val actual: InputStream,
    val expected: InputStream,
    val actualBuffer: ByteArray,
    val expectedBuffer: ByteArray
) {
    override fun toString(): String {
        return "InputStreamState(dataLength=$dataLength, bufferSize=$bufferSize, limit=$limit)"
    }
}

sealed interface ActionInputStreamState: Action<InputStreamState> {
    object Read: ActionInputStreamState {
        override fun run(state: InputStreamState) = state.apply {
            val expected = expected.read()
            val actual = actual.read()
            assertThat(actual).describedAs(toString()).isEqualTo(expected)
        }

        override fun toString() = "read()"
    }

    class ReadOffsetLength(val offset: Int, val length: Int): ActionInputStreamState {
        override fun run(state: InputStreamState) = state.apply {
            // Initialize buffer with garbage so we can detect if actual.read modifies data
            // outside the offset..offset+length
            ThreadLocalRandom.current().nextBytes(expectedBuffer)
            expectedBuffer.copyInto(actualBuffer)
            val actual = actual.read(actualBuffer, offset, length)
            val expected = expected.read(expectedBuffer, offset, length)

            SoftAssertions().apply {
                assertThat(actual).describedAs("return value from read(byte[], %d, %d)", offset, length).apply {
                    if (length > 0) {
                        // TODO: support case when actual implementation reads less bytes than requested
                        isEqualTo(expected)
                    } else {
                        // InputStream allows return 0 when 0 bytes requested even when EOF reached
                        isIn(0, expected)
                    }
                }
                assertThat(actualBuffer)
                    .describedAs("contents of buffer after read([], %d, %d)", offset, length)
                    .isEqualTo(expectedBuffer)
            }.assertAll()
        }

        override fun toString() = "read([], $offset, $length)"
    }

    class Skip(val n: Long): ActionInputStreamState {
        override fun run(state: InputStreamState) = state.apply {
            // .skip() can skip less bytes than requested
            val actual = actual.skip(n)
            assertThat(actual).describedAs("skip(%d)", n)
                .isBetween(0, n)

            // Skip the expected stream
            val skipped = expected.skip(actual)
            assertThat(actual).describedAs("skip(%d) seem to skip past the end of stream", n)
                .isEqualTo(skipped)
        }

        override fun toString() = "skip($n)"
    }

    class Mark(val readlimit: Int): ActionInputStreamState {
        override fun run(state: InputStreamState) = state.apply {
            expected.mark(readlimit)
            actual.mark(readlimit)
        }

        override fun toString() = "mark($readlimit)"
    }

    object Reset: ActionInputStreamState {
        override fun run(state: InputStreamState) = state.apply {
            expected.reset()
            actual.reset()
        }

        override fun toString() = "reset()"
    }
}

