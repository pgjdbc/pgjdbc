package org.postgresql.test.io

import net.jqwik.api.Arbitraries.integers
import net.jqwik.api.Arbitraries.just
import net.jqwik.api.Arbitraries.longs
import net.jqwik.api.Arbitraries.oneOf
import net.jqwik.api.Arbitraries.sequences
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.lifecycle.AddLifecycleHook
import net.jqwik.api.lifecycle.PropagationMode
import net.jqwik.api.stateful.ActionSequence
import org.postgresql.PGConnection
import org.postgresql.largeobject.LargeObjectManager
import java.io.ByteArrayInputStream
import java.sql.Connection
import java.util.Random
/**
 * See [jqwik sample](https://jqwik.net/docs/current/user-guide.html#stateful-testing).
 */
@AddLifecycleHook(DbConnectionHook::class, propagateTo = PropagationMode.ALL_DESCENDANTS)
class BlobInputStreamProperties {
    data class InputStreamTestData(
        val dataLength: Int,
        val bufferSize: Int,
        val limit: Long,
        val actions: ActionSequence<InputStreamState>
    )

    @Property
    fun `blob InputStream`(@ForAll("data") data: InputStreamTestData, con: Connection) {
        // TODO: move contents, expectedBuffer, actualBuffer initialization outside of once per sample
        val rnd = Random()
        val contents = ByteArray(data.dataLength)
        rnd.nextBytes(contents)

        val expectedBuffer = ByteArray(data.dataLength)
        val actualBuffer = ByteArray(data.dataLength)

        con.autoCommit = false
        val lom = con.unwrap(PGConnection::class.java).largeObjectAPI
        val oid = lom.createLO(LargeObjectManager.READWRITE)
        try {
            lom.open(oid).use { blob ->
                blob.write(contents)

                // TODO: should seek(0) be a part of blob.getInputStream?
                blob.seek(0)

                data.actions.run(
                    InputStreamState(
                        dataLength = data.dataLength,
                        bufferSize = data.bufferSize,
                        limit = data.limit,
                        actual = blob.getInputStream(data.bufferSize, data.limit),
                        expected = ByteArrayInputStream(
                            if (data.limit < contents.size) contents.copyOf(data.limit.toInt()) else contents
                        ),
                        actualBuffer = actualBuffer,
                        expectedBuffer = expectedBuffer
                    )
                )
            }
        } finally {
            lom.unlink(oid)
        }
    }

    @Provide
    fun data() =
        Combinators.combine(
            integers().between(0, 20000),
            integers().between(1, 60000),
            longs().greaterOrEqual(1L)
        ).flatAs { size, bufferSize, limit ->
            sequences(
                oneOf(
                    read(),
                    readOffsetLength(size),
                    skip(size.toLong()),
                    mark(size),
                    reset()
                )
            )
                .map { InputStreamTestData(size, bufferSize, limit, it) }
        }

    @Provide
    fun sizes() = integers().between(0, 200)

    private fun read() =
        just(ActionInputStreamState.Read)

    private fun readOffsetLength(size: Int) =
        integers().between(0, size).flatMap { offset ->
            integers().between(0, size - offset).map { length ->
                ActionInputStreamState.ReadOffsetLength(offset, length)
            }
        }

    private fun skip(size: Long) =
        longs().between(0, size).map { ActionInputStreamState.Skip(it) }

    private fun mark(size: Int) =
        integers().between(0, size).map { ActionInputStreamState.Mark(it) }

    private fun reset() =
        just(ActionInputStreamState.Reset)
}


