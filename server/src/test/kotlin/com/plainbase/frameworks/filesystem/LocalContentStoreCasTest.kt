package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.CitationFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

/**
 * PB-WRITE-1 named test 2 (debate MUST-FIX 2): [LocalContentStore.compareAndSwapWrite] is ONE atomic
 * operation, not a read-then-write split. An external write that lands between the internal read and
 * the rename is caught by the file-identity recheck — never clobbered.
 *
 * The deterministic seam is the injected `hasher` lambda: it runs on the freshly-read bytes, BEFORE
 * the temp-write + the pre-rename recheck. From inside it we mutate the file on disk (changing
 * fileKey/mtime), then return the CORRECT hash so the base-hash check still passes — so the only thing
 * that can reject the write is the identity recheck the CAS performs immediately before the rename.
 */
class LocalContentStoreCasTest : FunSpec({

    val citations = CitationFactory()

    fun withScannedFile(block: (LocalContentStore, TreePath, Path) -> Unit) {
        val root = Files.createTempDirectory("pb-cas-test")
        try {
            val osPath = root.resolve("page.md")
            Files.writeString(osPath, "original bytes\n")
            val store = LocalContentStore(root)
            store.scan() // populate the indexed-only snapshot so the file is a CAS target
            block(store, TreePath.require("page.md"), osPath)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("an external write between the CAS read and the rename is caught") {
        withScannedFile { store, path, osPath ->
            val original = Files.readAllBytes(osPath)
            val baseHash = citations.contentHash(original)
            val external = "EXTERNAL WRITE WON\n".toByteArray()
            var injected = false

            val saveBytes = "my CAS save\n".toByteArray()
            val result = store.compareAndSwapWrite(path, baseHash, saveBytes) { bytes ->
                // First call hashes the freshly-read bytes — inject the external write here, AFTER the
                // read, BEFORE the rename. Sleep nudges the mtime on filesystems with coarse clocks.
                if (!injected && bytes.contentEquals(original)) {
                    injected = true
                    Thread.sleep(20)
                    Files.write(osPath, external)
                }
                citations.contentHash(bytes)
            }

            result.shouldBeInstanceOf<CasResult.Mismatch>()
            // No lost update: the external bytes survive, the save bytes never landed.
            Files.readAllBytes(osPath) shouldBe external
        }
    }

    test("a matching base_hash with no concurrent write completes the rename") {
        withScannedFile { store, path, osPath ->
            val baseHash = citations.contentHash(Files.readAllBytes(osPath))
            val saveBytes = "clean save\n".toByteArray()
            val result = store.compareAndSwapWrite(path, baseHash, saveBytes, citations::contentHash)
            result.shouldBeInstanceOf<CasResult.Written>().newHash shouldBe citations.contentHash(saveBytes)
            Files.readAllBytes(osPath) shouldBe saveBytes
        }
    }

    test("a stale base_hash is rejected as a Mismatch with the on-disk state") {
        withScannedFile { store, path, osPath ->
            val saveBytes = "rejected save\n".toByteArray()
            val result = store.compareAndSwapWrite(path, "sha256:stale", saveBytes, citations::contentHash)
            val mismatch = result.shouldBeInstanceOf<CasResult.Mismatch>()
            mismatch.currentHash shouldBe citations.contentHash(Files.readAllBytes(osPath))
            Files.readAllBytes(osPath) shouldBe "original bytes\n".toByteArray()
        }
    }

    // FIX 3: a pre-rename I/O failure (temp create/write/move) must become CasResult.Unreadable, NOT an
    // escaping exception — otherwise WritePipeline's already-marked dirty row is orphaned (its hash names
    // bytes that never landed) and reconcile drift-skips it forever. Forced via an unwritable parent dir
    // (the temp-sibling create throws IOException). Original bytes stay intact.
    test("a pre-rename I/O failure yields Unreadable, not an escaping exception, and the file is intact") {
        withScannedFile { store, path, osPath ->
            val original = Files.readAllBytes(osPath)
            val baseHash = citations.contentHash(original)
            val parent = osPath.parent
            val ownerWritable = parent.toFile().setWritable(false)
            try {
                // Skip on platforms/filesystems where the directory cannot be made unwritable (e.g. root):
                // the assertion below only means something when the temp-sibling create can actually fail.
                if (!ownerWritable || parent.toFile().canWrite()) return@withScannedFile

                val result = store.compareAndSwapWrite(path, baseHash, "blocked save\n".toByteArray(), citations::contentHash)

                result.shouldBeInstanceOf<CasResult.Unreadable>()
                Files.readAllBytes(osPath) shouldBe original // the original bytes never changed
            } finally {
                parent.toFile().setWritable(true)
            }
        }
    }
})
