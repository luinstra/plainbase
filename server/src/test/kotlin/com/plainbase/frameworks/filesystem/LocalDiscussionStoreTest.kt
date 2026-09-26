package com.plainbase.frameworks.filesystem

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.Actor
import com.plainbase.domain.discussion.Anchor
import com.plainbase.domain.discussion.Author
import com.plainbase.domain.discussion.AuthorKind
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.CommentRecord
import com.plainbase.domain.discussion.DiscussionCodec
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRecord
import com.plainbase.domain.discussion.DiscussionStatus
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.EntryPath
import com.plainbase.domain.discussion.EntryPut
import com.plainbase.domain.discussion.EntryVersion
import com.plainbase.domain.discussion.FrontmatterExtras
import com.plainbase.domain.discussion.MAX_COMMENT_BYTES
import com.plainbase.domain.discussion.MAX_COMMENT_ENTRIES
import com.plainbase.domain.discussion.PageRef
import com.plainbase.domain.discussion.StoreWrite
import com.plainbase.domain.discussion.Tombstone
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.RootUnavailable
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.time.Instant

class LocalDiscussionStoreTest : FunSpec({
    test("a second create of the same id is exists") {
        withDiscussionRoot { root ->
            val order = mutableListOf<String>()
            val atomics = object : FileAtomics by FileAtomics.Real {
                override fun createLink(link: Path, existing: Path) {
                    order += link.fileName.toString()
                    FileAtomics.Real.createLink(link, existing)
                }
            }
            val store = LocalDiscussionStore(mapOf(ROOT to root), atomics = atomics)
            store.createFiles(ROOT, ID, startPuts()) shouldBe written()
            order shouldContainExactly listOf(COMMENT_NAME, EntryName.Marker.fileName)
            store.createFiles(ROOT, ID, startPuts()) shouldBe StoreWrite.Exists
        }
    }

    test("a failed marker link removes what the create wrote") {
        withDiscussionRoot { root ->
            val atomics = object : FileAtomics by FileAtomics.Real {
                override fun createLink(link: Path, existing: Path) {
                    if (link.fileName.toString() == EntryName.Marker.fileName) throw IOException("marker link failed")
                    FileAtomics.Real.createLink(link, existing)
                }
            }
            val store = LocalDiscussionStore(mapOf(ROOT to root), atomics = atomics)
            val result = store.createFiles(ROOT, ID, startPuts()).shouldBeInstanceOf<StoreWrite.Failed>()
            result.residual shouldBe false
            Files.exists(idDir(root), LinkOption.NOFOLLOW_LINKS) shouldBe false
            Files.exists(root.resolve("$RESERVED_COLLECTION_ROOT/$COLLECTION_DIR"), LinkOption.NOFOLLOW_LINKS) shouldBe true
        }
    }

    test("create refuses an entry over its cap") {
        withDiscussionRoot { root ->
            val tooLarge = EntryName.Comment(COMMENT.id)
            val store = LocalDiscussionStore(mapOf(ROOT to root))

            val result = store.createFiles(
                ROOT,
                ID,
                listOf(EntryPut(tooLarge, ByteArray(MAX_COMMENT_BYTES + 1)), EntryPut(EntryName.Marker, MARKER_BYTES)),
            ).shouldBeInstanceOf<StoreWrite.Failed>()

            result.cause shouldBe "entry exceeds its size cap"
            result.residual shouldBe false
            Files.exists(idDir(root), LinkOption.NOFOLLOW_LINKS) shouldBe false
        }
    }

    test("a failed comment create in an existing discussion is not a residual") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, EntryName.Marker.fileName), MARKER_BYTES)
            val atomics = object : FileAtomics by FileAtomics.Real {
                override fun createLink(link: Path, existing: Path) = throw IOException("comment link failed")
            }
            val store = LocalDiscussionStore(mapOf(ROOT to root), atomics = atomics)

            val result = store.createFiles(ROOT, ID, listOf(EntryPut(COMMENT, COMMENT_BYTES)))
                .shouldBeInstanceOf<StoreWrite.Failed>()

            result.residual shouldBe false
            Files.isDirectory(idDir(root), LinkOption.NOFOLLOW_LINKS) shouldBe true
            Files.readAllBytes(path(root, EntryName.Marker.fileName)).toList() shouldBe MARKER_BYTES.toList()
            Files.exists(path(root, COMMENT.fileName), LinkOption.NOFOLLOW_LINKS) shouldBe false
        }
    }

    test("create and replace do not write through a planted temp symlink") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, EntryName.Marker.fileName), MARKER_BYTES)
            Files.write(path(root, COMMENT.fileName), COMMENT_BYTES)
            val outside = Files.createTempFile("pb-discussion-temp-link", ".md")
            val temp = idDir(root).resolve(".pbtmp.planted.tmp")
            try {
                Files.writeString(outside, "outside sentinel")
                Files.createSymbolicLink(temp, outside)
                Files.deleteIfExists(temp)
            } catch (_: IOException) {
                Files.deleteIfExists(outside)
                return@withDiscussionRoot
            } catch (_: UnsupportedOperationException) {
                Files.deleteIfExists(outside)
                return@withDiscussionRoot
            }
            try {
                val store = LocalDiscussionStore(
                    mapOf(ROOT to root),
                    tempSuffix = { "planted" },
                    onTempPath = { candidate ->
                        Files.deleteIfExists(candidate)
                        Files.createSymbolicLink(candidate, outside)
                    },
                )
                val newComment = EntryName.Comment(CommentId.require("01900000-0000-7000-8000-000000000004"))

                val created = store.createFiles(ROOT, ID, listOf(EntryPut(newComment, COMMENT_BYTES)))
                val replaced = store.replace(ROOT, EntryPath(ID, COMMENT), version(store, COMMENT), "replacement".toByteArray())
                assertSoftly {
                    created.shouldBeInstanceOf<StoreWrite.Failed>()
                    replaced.shouldBeInstanceOf<StoreWrite.Failed>()
                    Files.readString(outside) shouldBe "outside sentinel"
                    Files.readAllBytes(path(root, COMMENT.fileName)).toList() shouldBe COMMENT_BYTES.toList()
                    Files.exists(path(root, newComment.fileName), LinkOption.NOFOLLOW_LINKS) shouldBe false
                    Files.isSymbolicLink(temp) shouldBe true
                }
            } finally {
                Files.deleteIfExists(outside)
            }
        }
    }

    test("a stat failure after creating the discussion directory reports a residual") {
        withDiscussionRoot { root ->
            val store = LocalDiscussionStore(
                mapOf(ROOT to root),
                readCreatedDirectoryAttributes = { throw IOException("stat failed") },
            )

            val result = store.createFiles(ROOT, ID, startPuts()).shouldBeInstanceOf<StoreWrite.Failed>()

            result.residual shouldBe true
            Files.isDirectory(idDir(root), LinkOption.NOFOLLOW_LINKS) shouldBe true
        }
    }

    test("a later put that exists rolls back and fails") {
        withDiscussionRoot { root ->
            val secondId = CommentId.require("01900000-0000-7000-8000-000000000003")
            val secondName = EntryName.Comment(secondId)
            val atomics = object : FileAtomics by FileAtomics.Real {
                override fun createLink(link: Path, existing: Path) {
                    if (link.fileName.toString() == secondName.fileName) {
                        Files.writeString(link, "foreign", StandardOpenOption.CREATE_NEW)
                    }
                    FileAtomics.Real.createLink(link, existing)
                }
            }
            val store = LocalDiscussionStore(mapOf(ROOT to root), atomics = atomics)
            val puts = listOf(
                EntryPut(COMMENT, COMMENT_BYTES),
                EntryPut(secondName, COMMENT_BYTES),
                EntryPut(EntryName.Marker, MARKER_BYTES),
            )
            val result = store.createFiles(ROOT, ID, puts).shouldBeInstanceOf<StoreWrite.Failed>()
            result.residual shouldBe false
            Files.exists(idDir(root), LinkOption.NOFOLLOW_LINKS) shouldBe true
            Files.exists(path(root, COMMENT.fileName), LinkOption.NOFOLLOW_LINKS) shouldBe false
            Files.readString(path(root, secondName.fileName)) shouldBe "foreign"
        }
    }

    test("a failed rollback reports a residual") {
        withDiscussionRoot { root ->
            val atomics = object : FileAtomics by FileAtomics.Real {
                override fun createLink(link: Path, existing: Path) {
                    if (link.fileName.toString() == EntryName.Marker.fileName) throw IOException("marker link failed")
                    FileAtomics.Real.createLink(link, existing)
                }
            }
            val store = LocalDiscussionStore(
                mapOf(ROOT to root),
                atomics = atomics,
                delete = { target ->
                    if (target.fileName.toString() == COMMENT.fileName) throw IOException("rollback failed")
                    Files.delete(target)
                },
            )
            val result = store.createFiles(ROOT, ID, startPuts()).shouldBeInstanceOf<StoreWrite.Failed>()
            result.residual shouldBe true
            Files.exists(path(root, COMMENT.fileName), LinkOption.NOFOLLOW_LINKS) shouldBe true
        }
    }

    test("create rollback leaves an identity-changed entry and reports a residual") {
        withDiscussionRoot { root ->
            val atomics = object : FileAtomics by FileAtomics.Real {
                override fun createLink(link: Path, existing: Path) {
                    if (link.fileName.toString() == EntryName.Marker.fileName) {
                        val comment = path(root, COMMENT.fileName)
                        Files.delete(comment)
                        Files.write(comment, COMMENT_BYTES)
                        throw IOException("marker link failed")
                    }
                    FileAtomics.Real.createLink(link, existing)
                }
            }
            val store = LocalDiscussionStore(mapOf(ROOT to root), atomics = atomics)
            val result = store.createFiles(ROOT, ID, startPuts()).shouldBeInstanceOf<StoreWrite.Failed>()
            result.residual shouldBe true
            Files.readAllBytes(path(root, COMMENT.fileName)).toList() shouldBe COMMENT_BYTES.toList()
        }
    }

    test("versions derive from the bytes read without a second file read") {
        withDiscussionRoot { root ->
            val commentPath = path(root, COMMENT.fileName)
            val large = ByteArray(600 * 1_024) { (it % 251).toByte() }
            Files.createDirectories(idDir(root))
            Files.write(commentPath, large)
            val store = LocalDiscussionStore(mapOf(ROOT to root), hashFile = { _, _ -> error("read must not hash the file again") })
            val read = store.read(ROOT, ID, only = setOf(COMMENT)).shouldBeInstanceOf<EntriesRead.Present>()
            val raw = read.entries.single()
            val expected = versionFor(large.copyOf(MAX_COMMENT_BYTES + 1), large.size.toLong(), MAX_COMMENT_BYTES)
            raw.version shouldBe expected
            raw.take().size shouldBe MAX_COMMENT_BYTES + 1

            Files.write(commentPath, COMMENT_BYTES)
            val small = LocalDiscussionStore(mapOf(ROOT to root)).read(ROOT, ID, only = setOf(COMMENT))
                .shouldBeInstanceOf<EntriesRead.Present>().entries.single()
            small.version.token shouldBe CitationFactory().contentHash(COMMENT_BYTES)
        }
    }

    test("a stable over-cap entry found on retry has its size detail") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, EntryName.Marker.fileName), MARKER_BYTES)
            Files.write(path(root, COMMENT.fileName), COMMENT_BYTES)
            var changed = false
            val store = LocalDiscussionStore(
                mapOf(ROOT to root),
                hashFile = { _, _ -> error("read must not hash the file again") },
                readEntryBytes = { file, maximum ->
                    if (!changed) {
                        changed = true
                        Files.write(file, ByteArray(maximum + 32) { 'x'.code.toByte() })
                    }
                    readAtMost(file, maximum)
                },
            )
            val read = store.read(ROOT, ID).shouldBeInstanceOf<EntriesRead.Present>()
            val result = com.plainbase.domain.discussion.DiscussionAssembly.assemble(ID, read)
                .shouldBeInstanceOf<com.plainbase.domain.discussion.DiscussionRead.Unreadable>()
            result.detail shouldBe "entry exceeds its size cap"
        }
    }

    test("stale replace and stale purge are mismatches") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, COMMENT.fileName), COMMENT_BYTES)
            val store = LocalDiscussionStore(mapOf(ROOT to root))
            val version = version(store, COMMENT)
            Files.write(path(root, COMMENT.fileName), "new bytes".toByteArray())
            store.replace(ROOT, EntryPath(ID, COMMENT), version, "replacement".toByteArray())
                .shouldBeInstanceOf<StoreWrite.Mismatch>().current.shouldBeInstanceOf<EntryVersion>()
            store.purge(ROOT, EntryPath(ID, COMMENT), version).shouldBeInstanceOf<StoreWrite.Mismatch>()
            Files.readString(path(root, COMMENT.fileName)) shouldBe "new bytes"
            listNames(idDir(root)).filter { it.startsWith(".pbtmp") || it.startsWith(".pbpurge") } shouldBe emptyList()
        }
    }

    test("a same-content identity change during replace carries the current version") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, COMMENT.fileName), COMMENT_BYTES)
            var hashes = 0
            val store = LocalDiscussionStore(mapOf(ROOT to root), hashFile = { file, cap ->
                hashes++
                val current = readAtMost(file, cap + 1)
                val currentVersion = versionFor(current, Files.size(file), cap)
                if (hashes == 1) {
                    val replacement = Files.createTempFile(file.parent, "same-content-", ".tmp")
                    Files.write(replacement, current)
                    Files.delete(file)
                    Files.move(replacement, file)
                }
                currentVersion
            })
            val currentVersion = version(store, COMMENT)
            val result = store.replace(ROOT, EntryPath(ID, COMMENT), currentVersion, "replacement".toByteArray())
                .shouldBeInstanceOf<StoreWrite.Mismatch>()
            result.current shouldBe currentVersion
            hashes shouldBe 2
            Files.readAllBytes(path(root, COMMENT.fileName)).toList() shouldBe COMMENT_BYTES.toList()
            listNames(idDir(root)).filter { it.startsWith(".pbtmp") } shouldBe emptyList()
        }
    }

    test("atomic move unavailable fails closed") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, COMMENT.fileName), COMMENT_BYTES)
            val atomics = object : FileAtomics by FileAtomics.Real {
                var copied = false
                override fun atomicMove(source: Path, target: Path): Unit =
                    throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "test")
                override fun copyReplace(source: Path, target: Path) {
                    copied = true
                    FileAtomics.Real.copyReplace(source, target)
                }
            }
            val store = LocalDiscussionStore(mapOf(ROOT to root), atomics = atomics)
            val oldBytes = Files.readAllBytes(path(root, COMMENT.fileName))
            val result = store.replace(ROOT, EntryPath(ID, COMMENT), version(store, COMMENT), "replacement".toByteArray())
                .shouldBeInstanceOf<StoreWrite.Failed>()
            result.residual shouldBe false
            Files.readAllBytes(path(root, COMMENT.fileName)).toList() shouldBe oldBytes.toList()
            atomics.copied shouldBe false
        }
    }

    test("purge tombstones and restore brings back exact bytes") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            val large = ByteArray(600 * 1_024) { (it % 251).toByte() }
            Files.write(path(root, COMMENT.fileName), large)
            val store = LocalDiscussionStore(mapOf(ROOT to root))
            val version = version(store, COMMENT)
            val written = store.purge(ROOT, EntryPath(ID, COMMENT), version).shouldBeInstanceOf<StoreWrite.Written>()
            val tombstone = requireNotNull(written.tombstone)
            Files.exists(path(root, COMMENT.fileName), LinkOption.NOFOLLOW_LINKS) shouldBe false
            store.read(ROOT, ID, only = setOf(COMMENT)).shouldBeInstanceOf<EntriesRead.Present>().entries shouldBe emptyList()
            store.restore(ROOT, tombstone).shouldBeInstanceOf<StoreWrite.Written>()
            Files.readAllBytes(path(root, COMMENT.fileName)).toList() shouldBe large.toList()
        }
    }

    test("discard reports success when empty-directory cleanup fails after deletion") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            val tombstoneName = ".pbpurge.${COMMENT.fileName}.0123456789abcdef"
            Files.writeString(idDir(root).resolve(tombstoneName), "tombstone")
            val store = LocalDiscussionStore(
                mapOf(ROOT to root),
                delete = { path ->
                    if (path == idDir(root)) throw IOException("directory delete failed")
                    Files.delete(path)
                },
            )

            val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val priorLevel = rootLogger.level
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            rootLogger.level = Level.DEBUG
            rootLogger.addAppender(appender)
            try {
                store.discard(ROOT, Tombstone(EntryPath(ID, COMMENT), tombstoneName))
                    .shouldBeInstanceOf<StoreWrite.Written>()

                Files.exists(idDir(root).resolve(tombstoneName), LinkOption.NOFOLLOW_LINKS) shouldBe false
                Files.isDirectory(idDir(root), LinkOption.NOFOLLOW_LINKS) shouldBe true
                appender.list.any {
                    it.level == Level.WARN && "directory delete failed" in it.formattedMessage &&
                        ID.value in it.formattedMessage
                } shouldBe true
            } finally {
                rootLogger.detachAppender(appender)
                rootLogger.level = priorLevel
                appender.stop()
            }
        }
    }

    test("a file created at the target name after the move survives") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, COMMENT.fileName), COMMENT_BYTES)
            var created = false
            val store = LocalDiscussionStore(mapOf(ROOT to root), hashFile = { file, cap ->
                val bytes = readAtMost(file, cap + 1)
                if (file.fileName.toString().startsWith(".pbpurge") && !created) {
                    created = true
                    Files.write(path(root, COMMENT.fileName), "new target".toByteArray(), StandardOpenOption.CREATE_NEW)
                }
                versionFor(bytes, Files.size(file), cap)
            })
            val oldVersion = version(store, COMMENT)
            val tombstone = store.purge(ROOT, EntryPath(ID, COMMENT), oldVersion)
                .shouldBeInstanceOf<StoreWrite.Written>().tombstone
            requireNotNull(tombstone)
            Files.readString(path(root, COMMENT.fileName)) shouldBe "new target"
            store.discard(ROOT, tombstone).shouldBeInstanceOf<StoreWrite.Written>()
            Files.readString(path(root, COMMENT.fileName)) shouldBe "new target"
        }
    }

    test("a purge hash failure moves back") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, COMMENT.fileName), COMMENT_BYTES)
            val store = LocalDiscussionStore(mapOf(ROOT to root), hashFile = { file, cap ->
                if (file.fileName.toString().startsWith(".pbpurge")) throw IOException("hash failed")
                versionFor(readAtMost(file, cap + 1), Files.size(file), cap)
            })
            val result = store.purge(ROOT, EntryPath(ID, COMMENT), EntryVersion(CitationFactory().contentHash(COMMENT_BYTES)))
                .shouldBeInstanceOf<StoreWrite.Failed>()
            result.residual shouldBe false
            Files.readAllBytes(path(root, COMMENT.fileName)).toList() shouldBe COMMENT_BYTES.toList()
            listNames(idDir(root)).none { it.startsWith(".pbpurge") } shouldBe true
        }
    }

    test("a purge whose move back is blocked keeps the tombstone") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, COMMENT.fileName), COMMENT_BYTES)
            var postMove = false
            val store = LocalDiscussionStore(mapOf(ROOT to root), hashFile = { file, cap ->
                val bytes = readAtMost(file, cap + 1)
                if (file.fileName.toString().startsWith(".pbpurge")) {
                    postMove = true
                    Files.write(path(root, COMMENT.fileName), "new target".toByteArray(), StandardOpenOption.CREATE_NEW)
                    EntryVersion("different")
                } else {
                    versionFor(bytes, Files.size(file), cap)
                }
            })
            val oldVersion = version(store, COMMENT)
            val result = store.purge(ROOT, EntryPath(ID, COMMENT), oldVersion).shouldBeInstanceOf<StoreWrite.Failed>()
            result.residual shouldBe true
            postMove shouldBe true
            Files.readString(path(root, COMMENT.fileName)) shouldBe "new target"
            listNames(idDir(root)).count { it.startsWith(".pbpurge") } shouldBe 1
        }
    }

    test("a purge whose hash and move back both fail keeps the tombstone") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, COMMENT.fileName), COMMENT_BYTES)
            var postMove = false
            val store = LocalDiscussionStore(mapOf(ROOT to root), hashFile = { file, cap ->
                if (file.fileName.toString().startsWith(".pbpurge")) {
                    postMove = true
                    Files.write(path(root, COMMENT.fileName), "new target".toByteArray(), StandardOpenOption.CREATE_NEW)
                    throw IOException("hash failed")
                }
                versionFor(readAtMost(file, cap + 1), Files.size(file), cap)
            })

            val result = store.purge(ROOT, EntryPath(ID, COMMENT), version(store, COMMENT))
                .shouldBeInstanceOf<StoreWrite.Failed>()

            result.residual shouldBe true
            postMove shouldBe true
            Files.readString(path(root, COMMENT.fileName)) shouldBe "new target"
            listNames(idDir(root)).count { it.startsWith(".pbpurge.") } shouldBe 1
        }
    }

    test("restore never clobbers a recreated entry") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, COMMENT.fileName), COMMENT_BYTES)
            val store = LocalDiscussionStore(mapOf(ROOT to root))
            val tombstone = requireNotNull(
                store.purge(ROOT, EntryPath(ID, COMMENT), version(store, COMMENT))
                .shouldBeInstanceOf<StoreWrite.Written>().tombstone,
            )
            Files.writeString(path(root, COMMENT.fileName), "new target", StandardOpenOption.CREATE_NEW)
            store.restore(ROOT, tombstone) shouldBe StoreWrite.Exists
            Files.readString(path(root, COMMENT.fileName)) shouldBe "new target"
            Files.exists(idDir(root).resolve(tombstone.fileName), LinkOption.NOFOLLOW_LINKS) shouldBe true
        }
    }

    test("discard failure leaves an ignored tombstone") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, COMMENT.fileName), COMMENT_BYTES)
            val store = LocalDiscussionStore(mapOf(ROOT to root))
            val tombstone = requireNotNull(
                store.purge(ROOT, EntryPath(ID, COMMENT), version(store, COMMENT))
                .shouldBeInstanceOf<StoreWrite.Written>().tombstone,
            )
            val failingDelete = LocalDiscussionStore(mapOf(ROOT to root), delete = { throw IOException("delete failed") })
            val result = failingDelete.discard(ROOT, tombstone).shouldBeInstanceOf<StoreWrite.Failed>()
            result.residual shouldBe true
            failingDelete.read(ROOT, ID).shouldBeInstanceOf<EntriesRead.Present>().entries shouldBe emptyList()
            Files.exists(idDir(root).resolve(tombstone.fileName), LinkOption.NOFOLLOW_LINKS) shouldBe true
        }
    }

    test("the narrowed read reads only what is named") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, EntryName.Marker.fileName), MARKER_BYTES)
            Files.write(path(root, COMMENT.fileName), COMMENT_BYTES)
            val third = EntryName.Comment(CommentId.require("01900000-0000-7000-8000-000000000003"))
            Files.write(path(root, third.fileName), byteArrayOf(0xff.toByte()))
            val read = LocalDiscussionStore(mapOf(ROOT to root)).read(ROOT, ID, only = setOf(EntryName.Marker, COMMENT))
                .shouldBeInstanceOf<EntriesRead.Present>()
            read.entries.map { it.name } shouldContainExactly listOf(COMMENT, EntryName.Marker)
            read.commentCount shouldBe 2
        }
    }

    test("a file that changes identity during both reads fails as retryable I/O") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, COMMENT.fileName), COMMENT_BYTES)
            var byteReads = 0
            val store = LocalDiscussionStore(
                mapOf(ROOT to root),
                readEntryBytes = { file, maximum ->
                    byteReads++
                    val bytes = readAtMost(file, maximum)
                    val replacement = Files.createTempFile(file.parent, "unstable-", ".tmp")
                    Files.write(replacement, bytes)
                    Files.delete(file)
                    Files.move(replacement, file)
                    bytes
                },
            )

            val result = store.read(ROOT, ID, only = setOf(COMMENT))
                .shouldBeInstanceOf<EntriesRead.Failed>()

            result.cause shouldBe "entry identity changed while reading"
            byteReads shouldBe 2
        }
    }

    test("a file that becomes a directory during its read fails") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, COMMENT.fileName), COMMENT_BYTES)
            var byteReads = 0
            val store = LocalDiscussionStore(
                mapOf(ROOT to root),
                readEntryBytes = { file, maximum ->
                    byteReads++
                    val bytes = readAtMost(file, maximum)
                    Files.delete(file)
                    Files.createDirectory(file)
                    bytes
                },
            )

            store.read(ROOT, ID, only = setOf(COMMENT)).shouldBeInstanceOf<EntriesRead.Failed>()
            byteReads shouldBe 1
        }
    }

    test("a mode-000 comment over the bound is not opened") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, EntryName.Marker.fileName), MARKER_BYTES)
            repeat(MAX_COMMENT_ENTRIES + 1) { index ->
                val commentId = CommentId.require("01900000-0000-7000-8000-${(index + 1).toString(16).padStart(12, '0')}")
                Files.write(path(root, EntryName.Comment(commentId).fileName), byteArrayOf(0xff.toByte()))
            }
            val unreadableId = CommentId.require(
                "01900000-0000-7000-8000-${(MAX_COMMENT_ENTRIES + 1).toString(16).padStart(12, '0')}",
            )
            Files.setPosixFilePermissions(
                path(root, EntryName.Comment(unreadableId).fileName),
                PosixFilePermissions.fromString("---------"),
            )
            Files.write(idDir(root).resolve(".pbpurge.ignored.0123456789abcdef"), byteArrayOf(1))
            Files.writeString(idDir(root).resolve("unknown.txt"), "ignored")
            val readNames = mutableListOf<String>()
            val store = LocalDiscussionStore(mapOf(ROOT to root), readEntryBytes = { file, maximum ->
                readNames += file.fileName.toString()
                readAtMost(file, maximum)
            })
            val read = store.read(ROOT, ID).shouldBeInstanceOf<EntriesRead.TooMany>()
            read.count shouldBe 1_001
            read.marker?.name shouldBe EntryName.Marker
            readNames shouldContainExactly listOf(EntryName.Marker.fileName)
        }
    }

    test("symlinked collection is refused and never followed") {
        withDiscussionRoot { root ->
            val outside = Files.createTempDirectory("pb-discussion-outside")
            try {
                Files.writeString(outside.resolve("sentinel"), "outside")
                try {
                    Files.createSymbolicLink(root.resolve(RESERVED_COLLECTION_ROOT), outside)
                } catch (_: IOException) {
                    return@withDiscussionRoot
                } catch (_: UnsupportedOperationException) {
                    return@withDiscussionRoot
                }
                val store = LocalDiscussionStore(mapOf(ROOT to root))
                store.createFiles(ROOT, ID, startPuts()).shouldBeInstanceOf<StoreWrite.Refused>().reason shouldBe "symlink"
                store.list(ROOT) shouldBe com.plainbase.domain.discussion.CollectionRead.Symlinked
                Files.readString(outside.resolve("sentinel")) shouldBe "outside"
                Files.exists(outside.resolve(COLLECTION_DIR), LinkOption.NOFOLLOW_LINKS) shouldBe false
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }

    test("a symlinked discussions directory is refused") {
        withDiscussionRoot { root ->
            val outside = Files.createTempDirectory("pb-discussion-outside")
            try {
                Files.createDirectories(root.resolve(RESERVED_COLLECTION_ROOT))
                Files.createSymbolicLink(root.resolve(RESERVED_COLLECTION_ROOT).resolve(COLLECTION_DIR), outside)
                val store = LocalDiscussionStore(mapOf(ROOT to root))
                store.read(ROOT, ID).shouldBeInstanceOf<EntriesRead.Symlinked>().entry shouldBe
                    "$RESERVED_COLLECTION_ROOT/$COLLECTION_DIR"
                store.list(ROOT) shouldBe com.plainbase.domain.discussion.CollectionRead.Symlinked
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }

    test("a symlinked discussion directory is reported and listed") {
        withDiscussionRoot { root ->
            val outside = Files.createTempDirectory("pb-discussion-outside")
            try {
                Files.createDirectories(root.resolve("$RESERVED_COLLECTION_ROOT/$COLLECTION_DIR"))
                Files.createSymbolicLink(idDir(root), outside)
                val store = LocalDiscussionStore(mapOf(ROOT to root))
                store.read(ROOT, ID).shouldBeInstanceOf<EntriesRead.Symlinked>().entry shouldBe "${ID.value}/"
                store.list(ROOT).shouldBeInstanceOf<com.plainbase.domain.discussion.CollectionRead.Present>()
                    .symlinked shouldBe listOf(ID)
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }

    test("an id directory symlink refuses every write and leaves its target alone") {
        withDiscussionRoot { root ->
            val docs = Files.createDirectories(root.resolve("docs"))
            Files.createDirectories(idDir(root).parent)
            Files.createDirectories(idDir(root))
            Files.write(path(root, COMMENT.fileName), COMMENT_BYTES)
            val realStore = LocalDiscussionStore(mapOf(ROOT to root))
            val tombstone = requireNotNull(
                realStore.purge(ROOT, EntryPath(ID, COMMENT), version(realStore, COMMENT))
                    .shouldBeInstanceOf<StoreWrite.Written>().tombstone,
            )
            Files.move(idDir(root), root.resolve("saved-discussion-id"))
            try {
                Files.createSymbolicLink(idDir(root), docs)
            } catch (_: IOException) {
                return@withDiscussionRoot
            } catch (_: UnsupportedOperationException) {
                return@withDiscussionRoot
            }
            val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val priorLevel = rootLogger.level
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            rootLogger.level = Level.DEBUG
            rootLogger.addAppender(appender)
            try {
                val store = LocalDiscussionStore(mapOf(ROOT to root))
                store.createFiles(ROOT, ID, startPuts())
                    .shouldBeInstanceOf<StoreWrite.Refused>().reason shouldBe "symlink"
                store.replace(ROOT, EntryPath(ID, COMMENT), EntryVersion("old"), COMMENT_BYTES)
                    .shouldBeInstanceOf<StoreWrite.Refused>().reason shouldBe "symlink"
                store.purge(ROOT, EntryPath(ID, COMMENT), EntryVersion("old"))
                    .shouldBeInstanceOf<StoreWrite.Refused>().reason shouldBe "symlink"
                store.restore(ROOT, tombstone).shouldBeInstanceOf<StoreWrite.Refused>().reason shouldBe "symlink"
                store.discard(ROOT, tombstone).shouldBeInstanceOf<StoreWrite.Refused>().reason shouldBe "symlink"

                Files.list(docs).use { it.count() shouldBe 0L }
                appender.list.count {
                    it.level == Level.WARN && "Refused discussion write" in it.formattedMessage &&
                        "symlink" in it.formattedMessage
                } shouldBe 5
            } finally {
                rootLogger.detachAppender(appender)
                rootLogger.level = priorLevel
                appender.stop()
            }
        }
    }

    test("a marker symlink is refused on normal and over-limit reads") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            val outside = Files.createTempFile("pb-discussion-marker-link", ".md")
            try {
                Files.createSymbolicLink(path(root, EntryName.Marker.fileName), outside)
            } catch (_: IOException) {
                Files.deleteIfExists(outside)
                return@withDiscussionRoot
            } catch (_: UnsupportedOperationException) {
                Files.deleteIfExists(outside)
                return@withDiscussionRoot
            }
            val store = LocalDiscussionStore(mapOf(ROOT to root))
            val normal = store.read(ROOT, ID)
            repeat(MAX_COMMENT_ENTRIES + 1) { index ->
                val commentId = CommentId.require(
                    "01900000-0000-7000-8000-${(index + 1).toString(16).padStart(12, '0')}",
                )
                Files.write(path(root, EntryName.Comment(commentId).fileName), COMMENT_BYTES)
            }
            val overLimit = store.read(ROOT, ID)
            assertSoftly {
                normal.shouldBeInstanceOf<EntriesRead.Symlinked>().entry shouldBe EntryName.Marker.fileName
                overLimit.shouldBeInstanceOf<EntriesRead.Symlinked>().entry shouldBe EntryName.Marker.fileName
            }
            Files.deleteIfExists(outside)
        }
    }

    test("known comment symlinks are debug-logged on reads and warn on writes") {
        withDiscussionRoot { root ->
            val outside = Files.createTempFile("pb-discussion-link", ".md")
            val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val priorLevel = rootLogger.level
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            rootLogger.level = Level.DEBUG
            rootLogger.addAppender(appender)
            try {
                Files.createDirectories(idDir(root))
                Files.writeString(outside, "outside")
                try {
                    Files.createSymbolicLink(path(root, COMMENT.fileName), outside)
                } catch (_: IOException) {
                    return@withDiscussionRoot
                } catch (_: UnsupportedOperationException) {
                    return@withDiscussionRoot
                }
                val store = LocalDiscussionStore(mapOf(ROOT to root))
                store.read(ROOT, ID, only = setOf(COMMENT))
                    .shouldBeInstanceOf<EntriesRead.Symlinked>().entry shouldBe COMMENT.fileName
                store.replace(ROOT, EntryPath(ID, COMMENT), EntryVersion("old"), COMMENT_BYTES)
                    .shouldBeInstanceOf<StoreWrite.Refused>().reason shouldBe "symlink"
                store.purge(ROOT, EntryPath(ID, COMMENT), EntryVersion("old"))
                    .shouldBeInstanceOf<StoreWrite.Refused>().reason shouldBe "symlink"
                Files.readString(outside) shouldBe "outside"
                appender.list.any {
                    it.level == Level.DEBUG && COMMENT.fileName in it.formattedMessage
                } shouldBe true
                appender.list.count {
                    it.level == Level.WARN && "Refused discussion write" in it.formattedMessage &&
                        COMMENT.fileName in it.formattedMessage
                } shouldBe 2
            } finally {
                rootLogger.detachAppender(appender)
                rootLogger.level = priorLevel
                appender.stop()
                Files.deleteIfExists(outside)
            }
        }
    }

    test("a regular file where a directory belongs reads as absent and refuses writes") {
        withDiscussionRoot { root ->
            Files.writeString(root.resolve(RESERVED_COLLECTION_ROOT), "regular file")
            val store = LocalDiscussionStore(mapOf(ROOT to root))
            val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val priorLevel = logger.level
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.level = Level.WARN
            logger.addAppender(appender)
            try {
                store.list(ROOT) shouldBe com.plainbase.domain.discussion.CollectionRead.Absent
                store.createFiles(ROOT, ID, startPuts()).shouldBeInstanceOf<StoreWrite.Refused>().reason shouldBe "not a directory"

                Files.delete(root.resolve(RESERVED_COLLECTION_ROOT))
                Files.createDirectories(idDir(root).parent)
                Files.writeString(idDir(root), "regular file")
                store.createFiles(ROOT, ID, startPuts()).shouldBeInstanceOf<StoreWrite.Refused>().reason shouldBe "not a directory"
            } finally {
                logger.detachAppender(appender)
                logger.level = priorLevel
                appender.stop()
            }
            appender.list.count {
                it.level == Level.WARN && "Refused discussion write" in it.formattedMessage &&
                    "not a directory" in it.formattedMessage
            } shouldBe 2
        }
    }

    test("a live read failure is failed") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, EntryName.Marker.fileName), MARKER_BYTES)
            val store = LocalDiscussionStore(mapOf(ROOT to root), readEntryBytes = { _, _ -> throw IOException("read fault") })
            store.read(ROOT, ID, only = setOf(EntryName.Marker)).shouldBeInstanceOf<EntriesRead.Failed>()
        }
    }

    test("a vanished root throws root unavailable") {
        withDiscussionRoot { root ->
            root.toFile().deleteRecursively()
            var marked = false
            val store = LocalDiscussionStore(mapOf(ROOT to root), onRootUnavailable = { marked = true })
            shouldThrow<RootUnavailable> { store.list(ROOT) }
            marked shouldBe true
        }
    }

    test("a root lost mid read throws root unavailable") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, EntryName.Marker.fileName), MARKER_BYTES)
            val store = LocalDiscussionStore(mapOf(ROOT to root), readEntryBytes = { file, maximum ->
                val result = readAtMost(file, maximum)
                root.toFile().deleteRecursively()
                result
            })
            shouldThrow<RootUnavailable> { store.read(ROOT, ID, only = setOf(EntryName.Marker)) }
        }
    }

    test("a root lost mid replace throws root unavailable") {
        withDiscussionRoot { root ->
            Files.createDirectories(idDir(root))
            Files.write(path(root, EntryName.Marker.fileName), MARKER_BYTES)
            val atomics = object : FileAtomics by FileAtomics.Real {
                override fun atomicMove(source: Path, target: Path) {
                    root.toFile().deleteRecursively()
                    FileAtomics.Real.atomicMove(source, target)
                }
            }
            val store = LocalDiscussionStore(mapOf(ROOT to root), atomics = atomics)
            shouldThrow<RootUnavailable> {
                store.replace(ROOT, EntryPath(ID, EntryName.Marker), version(store, EntryName.Marker), MARKER_BYTES)
            }
        }
    }

    test("an unknown root name is a programming error") {
        withDiscussionRoot { root ->
            val store = LocalDiscussionStore(mapOf(ROOT to root))
            shouldThrow<IllegalArgumentException> { store.list(RootName.require("other")) }
        }
    }
})

private val ROOT = RootName.PRIMARY
private const val ID_TEXT = "01900000-0000-7000-8000-000000000001"
private const val COMMENT_TEXT = "01900000-0000-7000-8000-000000000002"
private const val PAGE_TEXT = "0190aaaa-0000-4000-8000-000000000003"
private const val RESERVED_COLLECTION_ROOT = ".plainbase"
private const val COLLECTION_DIR = "discussions"
private val ID = DiscussionId.require(ID_TEXT)
private val COMMENT = EntryName.Comment(CommentId.require(COMMENT_TEXT))
private const val COMMENT_NAME = "01900000-0000-7000-8000-000000000002.md"
private val COMMENT_BYTES = DiscussionCodec.encodeComment(
    CommentRecord(
        CommentId.require(COMMENT_TEXT), ID, Author(Actor(SubjectKey("builtin", "u-1"), "Ada"), AuthorKind.HUMAN),
        Instant.parse("2026-09-23T10:00:00.000Z"), null, null, "hello\n", FrontmatterExtras.NONE,
    ),
)
private val MARKER_BYTES = DiscussionCodec.encodeDiscussion(
    DiscussionRecord(
        ID,
        PageRef(com.plainbase.domain.page.PageId.require(PAGE_TEXT), TreePath.require("guide.md")),
        DiscussionStatus.OPEN,
        Instant.parse("2026-09-23T10:00:00.000Z"),
        Author(Actor(SubjectKey("builtin", "u-1"), "Ada"), AuthorKind.HUMAN),
        null,
        Anchor.Page("sha256:" + "a".repeat(64), null),
        null,
        FrontmatterExtras.NONE,
    ),
)
private val START_PUTS = listOf(EntryPut(COMMENT, COMMENT_BYTES), EntryPut(EntryName.Marker, MARKER_BYTES))

private fun startPuts(): List<EntryPut> = START_PUTS

private fun written(): StoreWrite.Written = StoreWrite.Written(
    mapOf(
        COMMENT to EntryVersion(CitationFactory().contentHash(COMMENT_BYTES)),
        EntryName.Marker to EntryVersion(CitationFactory().contentHash(MARKER_BYTES)),
    ),
)

private fun withDiscussionRoot(block: (Path) -> Unit) {
    val root = Files.createTempDirectory("pb-discussion-store")
    try {
        block(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}

private fun idDir(root: Path): Path = root.resolve("$RESERVED_COLLECTION_ROOT/$COLLECTION_DIR/${ID.value}")

private fun path(root: Path, name: String): Path = idDir(root).resolve(name)

private fun version(store: DiscussionStore, name: EntryName): EntryVersion =
    store.read(ROOT, ID, only = setOf(name)).shouldBeInstanceOf<EntriesRead.Present>().entries.single().version

private fun listNames(path: Path): List<String> = Files.newDirectoryStream(path).use { stream ->
    stream.map { it.fileName.toString() }.toList()
}

private fun readAtMost(path: Path, maximum: Int): ByteArray = Files.newByteChannel(
    path,
    StandardOpenOption.READ,
    LinkOption.NOFOLLOW_LINKS,
).use { channel ->
    val buffer = java.nio.ByteBuffer.allocate(maximum)
    while (buffer.hasRemaining()) {
        if (channel.read(buffer) < 0) break
    }
    buffer.flip()
    ByteArray(buffer.remaining()).also(buffer::get)
}

private fun versionFor(bytes: ByteArray, size: Long, cap: Int): EntryVersion {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
    val base = "sha256:$digest"
    return EntryVersion(if (size > cap) "$base:$size" else base)
}
