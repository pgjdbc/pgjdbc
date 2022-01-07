package org.postgresql.test.io

import net.jqwik.api.stateful.Action
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import java.io.InputStream
import java.nio.ByteBuffer

class InputStreamState(
    val actual: InputStream,
    val expected: InputStream,
    val actualBuffer: ByteArray,
    val expectedBuffer: ByteArray
)

sealed interface ActionInputStreamState: Action<InputStreamState> {
    object Read: ActionInputStreamState {
        override fun run(state: InputStreamState) = state.apply {
            val expected = expected.read()
            val actual = this.actual.read()
            assertThat(actual).describedAs(toString()).isEqualTo(expected)
        }

        override fun toString() = "read()"
    }

    class ReadOffsetLength(val offset: Int, val length: Int): ActionInputStreamState {
        override fun run(state: InputStreamState) = state.apply {
            val expected = expected.read(expectedBuffer, offset, length)
            val actual = actual.read(actualBuffer, offset, length)

            SoftAssertions().apply {
                assertThat(actual).describedAs("return value from read(byte[], %d, %d)", offset, length)
                    .isEqualTo(expected)
                if (length > 0 &&
                    ByteBuffer.wrap(expectedBuffer, offset, length) !=
                    ByteBuffer.wrap(actualBuffer, offset, length)) {
                    assertThat(actualBuffer.copyOfRange(offset, offset + length))
                        .describedAs("contents of buffer at %d..%d", offset, offset + length)
                        .isEqualTo(expectedBuffer.copyOfRange(offset, offset + length))
                }
            }.assertAll()
        }

        override fun toString() = "read([], $offset, $length)"
    }

    class Skip(val n: Long): ActionInputStreamState {
        override fun run(state: InputStreamState) = state.apply {
            val expected = expected.skip(n)
            val actual = this.actual.skip(n)
            assertThat(actual).describedAs("skip(%d)", n).isEqualTo(expected)
        }

        override fun toString() = "skip($n)"
    }

    class Mark(val readlimit: Int): ActionInputStreamState {
        override fun run(state: InputStreamState) = state.apply {
            val expected = expected.mark(readlimit)
            val actual = this.actual.mark(readlimit)
            assertThat(actual).describedAs("mark(%d)", readlimit).isEqualTo(expected)
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

