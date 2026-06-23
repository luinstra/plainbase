package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.RawByteOrder
import com.plainbase.domain.content.ScanIssue
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.principal.grantForTests
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Acceptance tests for the java.nio [LocalContentStore] (chunk 1).
 *
 * JVM-only Kotest — NOT @Tag("native"): `LocalContentStore` is plain java.nio and behaves
 * identically on JVM and native; its temp-dir integration tests do not belong in the
 * closed-world native image (the native gate stays as-is).
 *
 * Non-ASCII literals are written editor-proof so a tool that NFC-normalizes the source cannot
 * silently collapse the NFD/NFC distinction (which would let the Linux half of a collision test
 * evaporate into the APFS branch with no failure). The NFC form uses the precomposed literal
 * `"réunion.md"`; the NFD form is built by concatenating a base string with a LONE combining-mark
 * char literal (`"re" + '́' + "union.md"`) — a lone combining mark in a char literal cannot
 * be NFC-folded. Per FIXTURES.md, NFD/collision cases are CREATED at runtime in temp dirs — the
 * committed NFD fixture is never trusted as a test invariant.
 */
class LocalContentStoreTest : FunSpec({

    // ---- Criterion 1: scan the committed fixture corpus, ignore .git/dotfiles --------------

    test("scanning demo-docs finds exactly the committed .md set plus the asset and sidecar") {
        val store = LocalContentStore(Fixtures.demoDocs)
        val result = store.scan()

        val mdCount = result.files.count { it.path.name.endsWith(".md") }
        mdCount shouldBe 41 // PINNED: the committed .md corpus

        // PINNED total: 41 .md + the diagram.svg asset + its .meta.yaml sidecar = 43.
        result.files shouldHaveSize 43

        val paths = result.files.map { it.path.value }.toSet()
        paths shouldContain "infra/assets/diagram.svg"
        paths shouldContain "infra/assets/diagram.svg.meta.yaml"

        // _folder.yaml is metadata, never a content file.
        result.files.none { it.path.name == "_folder.yaml" } shouldBe true
    }

    test("dot-prefixed entries and .git directories are excluded from the scan") {
        val tmp = Files.createTempDirectory("pb-ignore")
        try {
            Files.writeString(tmp.resolve("page.md"), "# page")
            Files.writeString(tmp.resolve(".DS_Store"), "junk")
            Files.writeString(tmp.resolve(".gitignore"), "*.tmp")
            Files.createDirectory(tmp.resolve(".git"))
            Files.writeString(tmp.resolve(".git").resolve("HEAD"), "ref: refs/heads/main")
            Files.createDirectory(tmp.resolve(".obsidian"))
            Files.writeString(tmp.resolve(".obsidian").resolve("workspace.json"), "{}")

            val result = LocalContentStore(tmp).scan()

            result.files.map { it.path.value } shouldContainExactly listOf("page.md")
            result.folders.none { it.path.value.startsWith(".") } shouldBe true
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("a nested DATA_DIR is excluded from the scan: app-owned databases are never indexed or served") {
        val tmp = Files.createTempDirectory("pb-nested-data")
        try {
            Files.writeString(tmp.resolve("page.md"), "# page")
            val data = Files.createDirectory(tmp.resolve("data"))
            Files.writeString(data.resolve("plainbase.db"), "not for the index")
            Files.writeString(data.resolve("search.db"), "not for the index")

            val result = LocalContentStore(tmp, exclusions = listOf(data)).scan()

            result.files.map { it.path.value } shouldContainExactly listOf("page.md")
            result.folders shouldHaveSize 0
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("content.ignore globs exclude matching paths") {
        val tmp = Files.createTempDirectory("pb-glob")
        try {
            Files.writeString(tmp.resolve("keep.md"), "keep")
            Files.createDirectory(tmp.resolve("drafts"))
            Files.writeString(tmp.resolve("drafts").resolve("wip.md"), "wip")

            val store = LocalContentStore(tmp, IgnoreRules(ignoreGlobs = listOf("drafts", "drafts/**")))
            val result = store.scan()

            result.files.map { it.path.value } shouldContainExactly listOf("keep.md")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ---- Criterion 2: NFD name scans to NFC, reads back via the retained raw name (P4) ------

    test("an NFD-named file scans to an NFC TreePath and reads back via the retained raw name") {
        val tmp = Files.createTempDirectory("pb-nfd")
        try {
            val nfdName = "re" + '\u0301' + "union.md" // e + U+0301 COMBINING ACUTE — NFD (65 cc 81), editor-proof
            val nfcName = "réunion.md" // precomposed U+00E9 — NFC
            val created = createWithRawName(tmp, nfdName, "RÉUNION-CONTENT")

            // If the platform normalized on create (APFS/HFS+), the on-disk name is already NFC;
            // either way the scanned TreePath must be NFC.
            val store = LocalContentStore(tmp)
            val result = store.scan()

            val file = result.files.single()
            file.path.value shouldBe nfcName // scanned TreePath is NFC regardless of on-disk form
            file.rawName shouldBe created // raw name retained exactly as it landed on disk

            // P4: read via the NFC TreePath reaches the bytes through the RETAINED raw name.
            val bytes = store.read(TreePath.require(nfcName))
            bytes.shouldNotBeNull()
            String(bytes, Charsets.UTF_8) shouldBe "RÉUNION-CONTENT"
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ---- Regression: a legal control-char (`\t`) filename must SCAN, not crash the rebuild --------
    // A `\t`/`\n` is a legal POSIX/macOS filename byte. The shared TreePath gate must only reject NUL —
    // an over-broad `isISOControl()` reject on TreePath.isValidSegment made scan() (→ TreePath.childOf
    // → resolveChild → require(isValidSegment)) THROW on such a file, crashing the rebuild/startup. This
    // pins that the scan indexes it. (Stricter create-input control-char rejection lives at the route.)
    test("a file whose name contains a legal control char (tab) scans without throwing the rebuild") {
        val tmp = Files.createTempDirectory("pb-ctrl-name")
        try {
            val tabName = "ta" + '\t' + "b.md" // a TAB in the name — legal on POSIX/macOS, not a NUL
            val landed = runCatching { createWithRawName(tmp, tabName, "TAB-CONTENT") }.getOrNull()
                ?: return@test // some filesystems refuse a tab in a name at create time — nothing to assert

            val store = LocalContentStore(tmp)
            val result = store.scan() // must NOT throw

            val file = result.files.single { it.path.name.endsWith(".md") }
            file.rawName shouldBe landed
            store.read(file.path).shouldNotBeNull().let { String(it, Charsets.UTF_8) shouldBe "TAB-CONTENT" }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ---- Criterion 3: NFC path collision — raw-byte-order winner, P4 read-back, issue -------

    test("NFD and NFC byte-forms colliding: byte-order winner indexed, content served, issue recorded") {
        val tmp = Files.createTempDirectory("pb-collision")
        try {
            val nfdName = "re" + '\u0301' + "union.md" // NFD bytes: 72 65 cc 81 ... — U+0301 (cc 81), editor-proof
            val nfcName = "réunion.md" // NFC bytes: 72 c3 a9 ...        — 'é' (c3 a9)
            val nfdActual = createWithRawName(tmp, nfdName, "NFD-CONTENT")
            val nfcActual = createWithRawName(tmp, nfcName, "NFC-CONTENT")

            val store = LocalContentStore(tmp)
            val result = store.scan()

            // PROBE the filesystem regime: did both byte-forms land as distinct files?
            val onDisk = Files.newDirectoryStream(tmp).use { stream -> stream.map { it.fileName.toString() }.toSet() }

            if (onDisk.size == 2) {
                // Normalization-preserving FS (Linux ext4): both exist → collision resolution.
                result.files shouldHaveSize 1
                val winner = result.files.single()
                winner.path.value shouldBe nfcName // single NFC TreePath

                // Winner = raw bytes sort first. NFC 'é' = c3 a9; NFD 'e'+ '́' = 65 cc 81.
                // At index 1: 0xc3 (195) vs 0x65 (101) → NFC name's bytes sort LATER, so the NFD
                // name ("réunion.md") is the byte-order winner.
                val expectedWinner = listOf(nfdActual, nfcActual).minWithOrNull(RawByteOrder)!!
                winner.rawName shouldBe expectedWinner

                // P4: the winner's CONTENT is served via raw-name read-back.
                val bytes = store.read(TreePath.require(nfcName)).shouldNotBeNull()
                val expectedContent = if (expectedWinner == nfdActual) "NFD-CONTENT" else "NFC-CONTENT"
                String(bytes, Charsets.UTF_8) shouldBe expectedContent

                val collision = result.issues.filterIsInstance<ScanIssue.PathCollision>().single()
                collision.path.value shouldBe nfcName
                setOf(collision.winnerRawName, collision.loserRawName) shouldBe setOf(nfdActual, nfcActual)
                collision.winnerRawName shouldBe expectedWinner
            } else {
                // Normalization-on-create FS (APFS/HFS+): the second create hit the same file.
                onDisk shouldHaveSize 1
                result.files shouldHaveSize 1
                result.issues.filterIsInstance<ScanIssue.PathCollision>() shouldHaveSize 0
                val winner = result.files.single()
                winner.path.value shouldBe nfcName
                // The single file is readable via its NFC TreePath.
                store.read(TreePath.require(nfcName)).shouldNotBeNull()
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("RawByteOrder compares filename bytes as UNSIGNED - the B3/A4 winner rule") {
        // Independent of resolveCollision: the collision test above SHARES this comparator to pick
        // its expected winner, so a signed-Byte regression would flip both sides and escape. Pin the
        // unsigned semantics directly with a hardcoded expected sign. NFC 'é' = 0xC3 0xA9; NFD
        // 'e'+combining = 0x65 0xCC 0x81. At the first differing byte unsigned 0x65 (101) < 0xC3 (195)
        // → NFD sorts FIRST. A signed comparison would flip it (signed 0xC3 = -61 < 0x65 = 101).
        val nfc = "réunion.md" // precomposed é (0xC3 0xA9)
        val nfd = "re" + '́' + "union.md" // e + U+0301 combining acute (0x65 0xCC 0x81)
        (RawByteOrder.compare(nfd, nfc) < 0) shouldBe true
        (RawByteOrder.compare(nfc, nfd) > 0) shouldBe true
    }

    // ---- Criterion 4: _folder.yaml from the fixtures and a runtime slug round-trip ----------

    test("the guides _folder.yaml yields (title=Guides, order=1, slug=null)") {
        val result = LocalContentStore(Fixtures.demoDocs).scan()
        val guides = result.folders.single { it.path.value == "guides" }
        val meta = guides.meta.shouldNotBeNull()
        meta.title shouldBe "Guides"
        meta.order shouldBe 1
        meta.slug shouldBe null
    }

    test("a runtime _folder.yaml with a slug parses and round-trips") {
        val tmp = Files.createTempDirectory("pb-folder-slug")
        try {
            val sub = Files.createDirectory(tmp.resolve("section"))
            Files.writeString(sub.resolve("_folder.yaml"), "title: My Section\norder: 3\nslug: my-section\n")
            Files.writeString(sub.resolve("page.md"), "# page")

            val result = LocalContentStore(tmp).scan()
            val folder = result.folders.single { it.path.value == "section" }
            val meta = folder.meta.shouldNotBeNull()
            meta.title shouldBe "My Section"
            meta.order shouldBe 3
            meta.slug shouldBe "my-section"
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ---- Write path: atomic write round-trips ----------------------------------------------

    test("write then read round-trips, creating parent directories") {
        val tmp = Files.createTempDirectory("pb-write")
        try {
            val store = LocalContentStore(tmp)
            val path = TreePath.require("a/b/note.md")
            store.write(path, "hello".toByteArray(Charsets.UTF_8))
            store.scan()
            String(store.read(path).shouldNotBeNull(), Charsets.UTF_8) shouldBe "hello"
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // BLOCKING-fix proof: write() must resolve through the retained raw name so an existing
    // NFD-named file is REPLACED, not shadowed by a new NFC-named sibling (P4 for writes).
    test("writing via the NFC TreePath overwrites the indexed NFD-named file, not a new sibling") {
        val tmp = Files.createTempDirectory("pb-overwrite")
        try {
            val nfdName = "re" + '\u0301' + "union.md" // NFD on-disk name (65 cc 81), editor-proof
            val nfcName = "réunion.md" // precomposed NFC TreePath value
            createWithRawName(tmp, nfdName, "ORIGINAL")

            val store = LocalContentStore(tmp)
            store.scan()

            // PROBE the FS regime: on a normalization-preserving FS the on-disk name stays NFD;
            // on APFS/HFS+ it landed NFC. Either way there is exactly ONE file before and after.
            store.write(TreePath.require(nfcName), "REPLACED".toByteArray(Charsets.UTF_8))

            val onDisk = Files.newDirectoryStream(tmp).use { stream -> stream.map { it.fileName.toString() }.toSet() }
            onDisk shouldHaveSize 1 // exactly one file remains — no NFC sibling was created

            // read() (still backed by the pre-write snapshot) returns the NEW bytes: write replaced
            // the indexed file in place rather than creating an unreachable sibling.
            String(store.read(TreePath.require(nfcName)).shouldNotBeNull(), Charsets.UTF_8) shouldBe "REPLACED"
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // Ancestor dirs resolution (P4 on interior segments): an NFD-named DIRECTORY must be reached
    // when reading a nested file via its NFC TreePath.
    test("a file under an NFD-named directory reads back via the NFC TreePath") {
        val tmp = Files.createTempDirectory("pb-nfd-dir")
        try {
            val nfdDir = "re" + '\u0301' + "union" // NFD directory name, editor-proof
            val nfcDir = "réunion" // precomposed NFC segment
            val dir = Files.createDirectory(tmp.resolve(nfdDir))
            Files.writeString(dir.resolve("note.md"), "NESTED-CONTENT")

            val store = LocalContentStore(tmp)
            store.scan()

            val bytes = store.read(TreePath.require("$nfcDir/note.md")).shouldNotBeNull()
            String(bytes, Charsets.UTF_8) shouldBe "NESTED-CONTENT"
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ---- Tolerance: non-UTF-8 _folder.yaml must not abort the scan -------------------------

    test("a non-UTF-8 _folder.yaml is tolerated: scan completes and the folder meta is null") {
        val tmp = Files.createTempDirectory("pb-bad-meta")
        try {
            val sub = Files.createDirectory(tmp.resolve("section"))
            // 0xFF 0xFE is not valid UTF-8 — Files.readString(UTF_8) throws MalformedInputException.
            Files.write(sub.resolve("_folder.yaml"), byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00))
            Files.writeString(sub.resolve("page.md"), "# page")

            val result = LocalContentStore(tmp).scan()

            // Scan completed (did not throw) and indexed the page.
            result.files.map { it.path.value } shouldContainExactly listOf("section/page.md")
            val folder = result.folders.single { it.path.value == "section" }
            folder.meta.shouldBeNull()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ---- Symlink policy: links are skipped with a warning, never followed -------------------

    test("a symlink is skipped (not followed) by the scan") {
        val tmp = Files.createTempDirectory("pb-symlink")
        try {
            Files.writeString(tmp.resolve("real.md"), "real")
            val outside = Files.createTempDirectory("pb-symlink-target")
            Files.writeString(outside.resolve("escaped.md"), "escaped")
            try {
                Files.createSymbolicLink(tmp.resolve("link.md"), outside.resolve("escaped.md"))
                Files.createSymbolicLink(tmp.resolve("linkdir"), outside)
            } catch (_: IOException) {
                // Platform/permissions disallow symlink creation — nothing to assert here.
                return@test
            }

            val result = LocalContentStore(tmp).scan()

            // Only the real file is indexed; neither the file symlink nor the dir symlink appears.
            result.files.map { it.path.value } shouldContainExactly listOf("real.md")
            result.folders.none { it.path.value == "linkdir" } shouldBe true
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ---- list() / stat() coverage ----------------------------------------------------------

    test("list returns the immediate children of a directory (folders then files)") {
        val tmp = Files.createTempDirectory("pb-list")
        try {
            Files.writeString(tmp.resolve("top.md"), "top")
            val sub = Files.createDirectory(tmp.resolve("sub"))
            Files.writeString(sub.resolve("child.md"), "child")

            val store = LocalContentStore(tmp)
            store.scan()

            val rootEntries = store.list(null).map { it.path.value }
            rootEntries shouldContainExactly listOf("sub", "top.md") // folders first, then files
            store.list(TreePath.require("sub")).map { it.path.value } shouldContainExactly listOf("sub/child.md")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("list resolves an NFC/NFD collision the same way scan does (winner only, no issue surfaced)") {
        val tmp = Files.createTempDirectory("pb-list-collision")
        try {
            val nfdName = "re" + '\u0301' + "union.md" // NFD, editor-proof
            val nfcName = "réunion.md"
            val nfdActual = createWithRawName(tmp, nfdName, "NFD")
            val nfcActual = createWithRawName(tmp, nfcName, "NFC")

            val store = LocalContentStore(tmp)
            store.scan()

            val onDisk = Files.newDirectoryStream(tmp).use { stream -> stream.map { it.fileName.toString() }.toSet() }
            val entries = store.list(null).map { it.path.value }

            if (onDisk.size == 2) {
                // Both byte-forms exist (Linux ext4): list() collapses them to the single winner.
                entries shouldContainExactly listOf(nfcName)
                val expectedWinner = listOf(nfdActual, nfcActual).minWithOrNull(RawByteOrder)!!
                (store.list(null).single() as com.plainbase.domain.content.ContentFile).rawName shouldBe expectedWinner
            } else {
                // APFS/HFS+: a single file landed.
                entries shouldContainExactly listOf(nfcName)
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("stat reports directory, regular-file size, and null for an unindexed path") {
        val tmp = Files.createTempDirectory("pb-stat")
        try {
            Files.writeString(tmp.resolve("page.md"), "12345")
            Files.createDirectory(tmp.resolve("folder"))

            val store = LocalContentStore(tmp)
            store.scan()

            val fileStat = store.stat(TreePath.require("page.md")).shouldNotBeNull()
            fileStat.isDirectory shouldBe false
            fileStat.sizeBytes shouldBe 5L

            val dirStat = store.stat(TreePath.require("folder")).shouldNotBeNull()
            dirStat.isDirectory shouldBe true
            dirStat.sizeBytes shouldBe 0L

            store.stat(TreePath.require("missing.md")).shouldBeNull()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ---- Security: indexed-only visibility — ignored/skipped paths are unreachable -----------
    // read/stat/list answer ONLY from the retained scan snapshot, so an entry the scan skipped
    // (dotfile, .git, .obsidian) is invisible at EVERY access, not just the enumeration loop.
    // TreePath.require(".git/config") constructs fine: TreePath only blocks ../absolute, so a
    // dot-prefixed path is reachable as an argument — the membership gate is what closes it.

    test("read/stat/list on a gitignored .git path return null/empty (never the on-disk bytes)") {
        val tmp = Files.createTempDirectory("pb-ignored-git")
        try {
            Files.writeString(tmp.resolve("page.md"), "# page")
            Files.createDirectory(tmp.resolve(".git"))
            Files.writeString(tmp.resolve(".git").resolve("config"), "SECRET-GIT-CONFIG")

            val store = LocalContentStore(tmp)
            store.scan()

            // The file exists on disk but is NOT indexed: read must not surface its bytes.
            store.read(TreePath.require(".git/config")).shouldBeNull()
            store.stat(TreePath.require(".git/config")).shouldBeNull()
            store.stat(TreePath.require(".git")).shouldBeNull()
            // list of an unindexed directory is empty; list(null) never surfaces the .git dir.
            store.list(TreePath.require(".git")).shouldBeEmpty()
            store.list(null).map { it.path.value } shouldContainExactly listOf("page.md")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("read/stat/list on a .obsidian dir and a dotfile return null/empty") {
        val tmp = Files.createTempDirectory("pb-ignored-dot")
        try {
            Files.writeString(tmp.resolve("page.md"), "# page")
            Files.writeString(tmp.resolve(".DS_Store"), "junk")
            Files.createDirectory(tmp.resolve(".obsidian"))
            Files.writeString(tmp.resolve(".obsidian").resolve("workspace.json"), "{}")

            val store = LocalContentStore(tmp)
            store.scan()

            store.read(TreePath.require(".DS_Store")).shouldBeNull()
            store.stat(TreePath.require(".DS_Store")).shouldBeNull()
            store.read(TreePath.require(".obsidian/workspace.json")).shouldBeNull()
            store.stat(TreePath.require(".obsidian")).shouldBeNull()
            store.list(TreePath.require(".obsidian")).shouldBeEmpty()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ---- Security: symlinks are never readable, even out of the content root -----------------

    test("read of a symlink pointing OUT of root returns null, not the target bytes") {
        val tmp = Files.createTempDirectory("pb-symlink-read")
        val outside = Files.createTempDirectory("pb-symlink-read-target")
        try {
            Files.writeString(tmp.resolve("real.md"), "real")
            val secret = outside.resolve("secret.md")
            Files.writeString(secret, "OUT-OF-ROOT-SECRET")
            try {
                Files.createSymbolicLink(tmp.resolve("link.md"), secret)
            } catch (_: IOException) {
                return@test // platform/permissions disallow symlinks — nothing to assert
            }

            val store = LocalContentStore(tmp)
            store.scan()

            // The symlink was skipped by scan, so it is not indexed: read returns null — NOT the
            // out-of-root target bytes. Assert genuinely-null, not "happens to equal something".
            store.read(TreePath.require("link.md")).shouldBeNull()
            store.stat(TreePath.require("link.md")).shouldBeNull()
            // Sanity: the real in-root file IS readable, proving the gate is selective.
            String(store.read(TreePath.require("real.md")).shouldNotBeNull(), Charsets.UTF_8) shouldBe "real"
        } finally {
            tmp.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    test("read through a DIRECTORY symlink (evil -> outside) returns null") {
        val tmp = Files.createTempDirectory("pb-symlink-dir")
        val outside = Files.createTempDirectory("pb-symlink-dir-target")
        try {
            Files.writeString(tmp.resolve("real.md"), "real")
            Files.createDirectory(outside.resolve("etc"))
            Files.writeString(outside.resolve("etc").resolve("passwd.md"), "OUT-OF-ROOT-PASSWD")
            try {
                Files.createSymbolicLink(tmp.resolve("evil"), outside)
            } catch (_: IOException) {
                return@test
            }

            val store = LocalContentStore(tmp)
            store.scan()

            // The directory symlink was skipped, so nothing under it is indexed: a read THROUGH it
            // resolves to nothing visible.
            store.read(TreePath.require("evil/etc/passwd.md")).shouldBeNull()
            store.stat(TreePath.require("evil/etc/passwd.md")).shouldBeNull()
            store.stat(TreePath.require("evil")).shouldBeNull()
            store.list(TreePath.require("evil")).shouldBeEmpty()
        } finally {
            tmp.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    test("a symlinked _folder.yaml is not followed: folder meta is null, no out-of-root content read") {
        val tmp = Files.createTempDirectory("pb-symlink-meta")
        val outside = Files.createTempDirectory("pb-symlink-meta-target")
        try {
            val sub = Files.createDirectory(tmp.resolve("section"))
            Files.writeString(sub.resolve("page.md"), "# page")
            val outsideMeta = outside.resolve("evil-folder.yaml")
            Files.writeString(outsideMeta, "title: OUT-OF-ROOT\norder: 99\n")
            try {
                Files.createSymbolicLink(sub.resolve("_folder.yaml"), outsideMeta)
            } catch (_: IOException) {
                return@test
            }

            val result = LocalContentStore(tmp).scan()

            // Scan completed and indexed the page; the symlinked sidecar was skipped, not read.
            result.files.map { it.path.value } shouldContainExactly listOf("section/page.md")
            val folder = result.folders.single { it.path.value == "section" }
            folder.meta.shouldBeNull() // out-of-root meta never parsed
        } finally {
            tmp.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    // ---- Regression: createExclusive never exposes a 0-byte target (P2 concurrent-rebuild race) -------
    // A watcher rebuild() runs concurrently with createExclusive (it takes only the IndexBuilder monitor),
    // so it could scan() a 0-byte reservation between createFile and the content move, publishing a ghost
    // empty page. The createLink-with-content path eliminates that window: the target only ever appears
    // with its FULL bytes. A concurrent poller watches the target throughout many creates and asserts it
    // is NEVER observed as a 0-byte regular file. Race-direction-safe: a miss just lowers sensitivity, it
    // can never false-fail; under the old reserve-then-move it would catch the empty window.
    //
    // The no-empty-window assertion is GATED on hardlink support: where hardlinks are unsupported,
    // createExclusive legitimately falls back to reserve-then-move (which DOES expose the window), so the
    // assertion would be a false failure. The full-content-landing assertions hold on BOTH paths and stay
    // unconditional. ext4/APFS (and CI) support hardlinks, so the meaningful guard runs in the common case.
    test("createExclusive never exposes the target as a 0-byte file (the no-empty-window invariant)") {
        val tmp = Files.createTempDirectory("pb-create-atomic")
        val hasher: (ByteArray) -> String = { it.size.toString() } // content-only test; hash value unused
        try {
            val hardlinksSupported = probeHardlinkSupport(tmp)
            val store = LocalContentStore(tmp)
            val content = ("# Atomic\n\n" + "x".repeat(8192) + "\n").toByteArray() // big enough to not write instantly
            repeat(40) { n ->
                val target = tmp.resolve("page-$n.md")
                // Local var published safely by the poller.join() happens-before edge (no @Volatile needed).
                var sawEmpty = false
                val poller = Thread {
                    while (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { /* spin until it appears */ }
                    // The instant it appears it must already hold content — never a 0-byte regular file.
                    if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) == 0L) sawEmpty = true
                }
                poller.start()
                val result = store.createExclusive(TreePath.require("page-$n.md"), content, hasher)
                poller.join()
                result.shouldBeInstanceOf<CreateResult.Created>()
                if (hardlinksSupported) sawEmpty shouldBe false // no window on the createLink path
                Files.readAllBytes(target).size shouldBe content.size // landed with the full content (both paths)
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ---- Regression: a symlinked content ROOT does not break a top-level asset write (codex-review) --
    // writeAssetExclusive's parent-exists check must FOLLOW links: rejectionReason (run first) already
    // rejects symlinked ancestors BELOW root and outside-root escapes, so a symlinked content ROOT is
    // legitimate. A NOFOLLOW_LINKS check here falsely sees the symlinked root as "not a directory" and
    // returns ParentMissing (→ a 404 for a page that exists). The content root is a symlink to a real
    // dir; a top-level asset write must land as Created with the bytes on disk.
    test("writeAssetExclusive into a symlinked content ROOT succeeds (parent check follows links)") {
        val realRoot = Files.createTempDirectory("pb-symroot-real")
        val linkParent = Files.createTempDirectory("pb-symroot-link")
        val hasher: (ByteArray) -> String = { it.size.toString() }
        try {
            // The store's root IS a symlink pointing at a real directory (a legitimate deployment).
            val symRoot = linkParent.resolve("root")
            try {
                Files.createSymbolicLink(symRoot, realRoot)
            } catch (_: IOException) {
                return@test // platform/permissions disallow symlinks — nothing to assert
            }
            val store = LocalContentStore(symRoot)
            val bytes = "binary-asset".toByteArray()

            // A top-level asset whose parent IS the symlinked root: must be Created, not ParentMissing.
            store.writeAssetExclusive(grantForTests(), TreePath.require("diagram.png"), bytes, hasher)
                .shouldBeInstanceOf<CreateResult.Created>()
            // Bytes landed on disk under the (real) root.
            Files.readAllBytes(realRoot.resolve("diagram.png")) shouldBe bytes
        } finally {
            linkParent.toFile().deleteRecursively()
            realRoot.toFile().deleteRecursively()
        }
    }

    // ---- Regression: a regular-FILE parent is ParentMissing, not Rejected (FIX F / codex-review) -----
    // The parent-is-a-directory check must run BEFORE rejectionReason so a parent that is a regular FILE
    // (the page's folder path replaced by a file on disk) maps to ParentMissing (→ 404, the documented
    // contract — the page's folder is gone), NOT rejectionReason's "file-not-dir ancestor" rule which
    // would classify it as Rejected (→ 400). Nothing is written; the reorder must not let a child slip
    // under a non-directory parent.
    test("writeAssetExclusive into a child of a regular-FILE parent is ParentMissing, not Rejected; nothing written") {
        val root = Files.createTempDirectory("pb-asset-file-parent")
        val hasher: (ByteArray) -> String = { it.size.toString() }
        try {
            // `guides` is a regular FILE where the page folder would be (an external clobber on disk).
            Files.write(root.resolve("guides"), "not a directory".toByteArray())
            val store = LocalContentStore(root)
            store.scan()

            store.writeAssetExclusive(grantForTests(), TreePath.require("guides/diagram.png"), "binary".toByteArray(), hasher)
                .shouldBeInstanceOf<CreateResult.ParentMissing>()
            // Nothing landed: the file-parent is untouched, no child created under it.
            Files.readAllBytes(root.resolve("guides")) shouldBe "not a directory".toByteArray()
            Files.exists(root.resolve("guides/diagram.png"), LinkOption.NOFOLLOW_LINKS) shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ---- Regression: create-reject set == scan skip set on reserved names (no drift) ----------------
    // The "scan skips segment X but the create gate allows it → ghost page" class (round-2 dotfiles,
    // round-9 `_folder.yaml`) is closed by ONE shared name-skip predicate. This pins the two predicates
    // AGREE on every reserved case: for each scan-skipped name, scan() does NOT index a file of that name
    // AND createExclusive of a path under that segment is Rejected. A future scan-skip addition that
    // doesn't also reject creates would fail here.
    test("createExclusive rejects exactly the segment names scan skips (_folder.yaml, dotfile, ignore glob)") {
        val tmp = Files.createTempDirectory("pb-skip-parity")
        val hasher: (ByteArray) -> String = { it.size.toString() }
        try {
            // A store whose ignore globs add a `drafts` skip on top of the always-skipped dotfiles +
            // `_folder.yaml` sidecar — the full name-skip set the create gate must mirror.
            val store = LocalContentStore(tmp, IgnoreRules(ignoreGlobs = listOf("drafts", "drafts/**")))
            val content = "# x\n".toByteArray()

            // Each reserved SEGMENT name and a real on-disk file using it (proving scan skips that name).
            val skippedSegments = listOf("_folder.yaml", ".secret", "drafts")
            for (seg in skippedSegments) {
                // A file literally named the reserved segment at the root → scan must NOT index it.
                Files.write(tmp.resolve(seg), content)
                // A create whose PARENT folder is that reserved segment → must be Rejected (would ghost).
                store.createExclusive(TreePath.require("$seg/page.md"), content, hasher)
                    .shouldBeInstanceOf<CreateResult.Rejected>()
            }

            val indexed = store.scan().files.map { it.path.value }.toSet()
            // Scan indexed NONE of the reserved-name files (it skips them) — parity with the rejects above.
            skippedSegments.none { it in indexed } shouldBe true
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ---- Regression: exclusions AT/ABOVE root are no-ops; only inside-root exclusions reject ---------
    // The create-containment gate must mirror scan's EFFECTIVE exclusions (those strictly inside root).
    // Scan/watch ignore an exclusion at or above root (root is the scan boundary), so the create gate
    // must too — else, in the PlainbaseConfig-legal layout where DATA_DIR is a strict ANCESTOR of root,
    // every create's target `startsWith(DATA_DIR)` and gets rejected (round-12 P2).
    test("a DATA_DIR that is a strict ANCESTOR of root does not block creates (effective-exclusion parity)") {
        val data = Files.createTempDirectory("pb-ancestor-data")
        val hasher: (ByteArray) -> String = { it.size.toString() }
        try {
            val root = Files.createDirectory(data.resolve("content")) // root is INSIDE the DATA_DIR
            // The app's own DB files live in the ancestor DATA_DIR — never inside root, so scan never sees
            // them and the ancestor exclusion is a no-op.
            Files.write(data.resolve("plainbase.db"), "not content".toByteArray())
            val store = LocalContentStore(root, exclusions = listOf(data))

            // A create into the content root SUCCEEDS — the ancestor DATA_DIR must not reject it.
            store.createExclusive(TreePath.require("page.md"), "# hi\n".toByteArray(), hasher)
                .shouldBeInstanceOf<CreateResult.Created>()
        } finally {
            data.toFile().deleteRecursively()
        }
    }

    test("a DATA_DIR strictly INSIDE root still rejects a create targeting under it (no over-correction)") {
        val tmp = Files.createTempDirectory("pb-nested-data-create")
        val hasher: (ByteArray) -> String = { it.size.toString() }
        try {
            val data = Files.createDirectory(tmp.resolve("data")) // a genuinely nested DATA_DIR
            val store = LocalContentStore(tmp, exclusions = listOf(data))

            // A create UNDER the nested DATA_DIR is still rejected — don't over-correct into allowing it.
            store.createExclusive(TreePath.require("data/page.md"), "# hi\n".toByteArray(), hasher)
                .shouldBeInstanceOf<CreateResult.Rejected>()
            // A create elsewhere in root still works.
            store.createExclusive(TreePath.require("ok.md"), "# ok\n".toByteArray(), hasher)
                .shouldBeInstanceOf<CreateResult.Created>()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
})

/**
 * Probes once whether [dir]'s filesystem supports hardlinks (the createLink no-empty-window path); a
 * filesystem that throws [UnsupportedOperationException]/[FileSystemException] makes createExclusive fall
 * back to reserve-then-move, which legitimately has the 0-byte window.
 */
private fun probeHardlinkSupport(dir: Path): Boolean {
    val src = Files.createTempFile(dir, ".probe-src", ".tmp")
    val link = dir.resolve(".probe-link")
    return try {
        Files.createLink(link, src)
        true
    } catch (_: UnsupportedOperationException) {
        false
    } catch (_: java.nio.file.FileSystemException) {
        false
    } catch (_: IOException) {
        false
    } finally {
        Files.deleteIfExists(link)
        Files.deleteIfExists(src)
    }
}

/**
 * Creates a file named [name] (UTF-8 bytes) directly via the filesystem and returns the name
 * the file ACTUALLY landed under — which may differ from [name] when the filesystem normalizes
 * on create. Callers must read the on-disk truth back rather than assuming [name] persisted.
 */
private fun createWithRawName(dir: Path, name: String, content: String): String {
    val target = dir.resolve(name)
    Files.writeString(target, content)
    // Read the on-disk name back: APFS/HFS+ may have stored a normalized form.
    val landed = Files.newDirectoryStream(dir).use { stream ->
        stream.map { it.fileName.toString() }.first { Files.readString(dir.resolve(it)) == content }
    }
    return landed
}
