package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.ScanIssue
import com.plainbase.domain.content.TreePath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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
            String(bytes, StandardCharsets.UTF_8) shouldBe "RÉUNION-CONTENT"
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
                val expectedWinner = listOf(nfdActual, nfcActual).minWithOrNull(LocalContentStore.RAW_BYTE_ORDER)!!
                winner.rawName shouldBe expectedWinner

                // P4: the winner's CONTENT is served via raw-name read-back.
                val bytes = store.read(TreePath.require(nfcName)).shouldNotBeNull()
                val expectedContent = if (expectedWinner == nfdActual) "NFD-CONTENT" else "NFC-CONTENT"
                String(bytes, StandardCharsets.UTF_8) shouldBe expectedContent

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

    test("RAW_BYTE_ORDER compares filename bytes as UNSIGNED - the B3/A4 winner rule") {
        // Independent of resolveCollision: the collision test above SHARES this comparator to pick
        // its expected winner, so a signed-Byte regression would flip both sides and escape. Pin the
        // unsigned semantics directly with a hardcoded expected sign. NFC 'é' = 0xC3 0xA9; NFD
        // 'e'+combining = 0x65 0xCC 0x81. At the first differing byte unsigned 0x65 (101) < 0xC3 (195)
        // → NFD sorts FIRST. A signed comparison would flip it (signed 0xC3 = -61 < 0x65 = 101).
        val nfc = "réunion.md" // precomposed é (0xC3 0xA9)
        val nfd = "re" + '́' + "union.md" // e + U+0301 combining acute (0x65 0xCC 0x81)
        (LocalContentStore.RAW_BYTE_ORDER.compare(nfd, nfc) < 0) shouldBe true
        (LocalContentStore.RAW_BYTE_ORDER.compare(nfc, nfd) > 0) shouldBe true
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
            store.write(path, "hello".toByteArray(StandardCharsets.UTF_8))
            store.scan()
            String(store.read(path).shouldNotBeNull(), StandardCharsets.UTF_8) shouldBe "hello"
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
            store.write(TreePath.require(nfcName), "REPLACED".toByteArray(StandardCharsets.UTF_8))

            val onDisk = Files.newDirectoryStream(tmp).use { stream -> stream.map { it.fileName.toString() }.toSet() }
            onDisk shouldHaveSize 1 // exactly one file remains — no NFC sibling was created

            // read() (still backed by the pre-write snapshot) returns the NEW bytes: write replaced
            // the indexed file in place rather than creating an unreachable sibling.
            String(store.read(TreePath.require(nfcName)).shouldNotBeNull(), StandardCharsets.UTF_8) shouldBe "REPLACED"
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
            String(bytes, StandardCharsets.UTF_8) shouldBe "NESTED-CONTENT"
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
                val expectedWinner = listOf(nfdActual, nfcActual).minWithOrNull(LocalContentStore.RAW_BYTE_ORDER)!!
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
            String(store.read(TreePath.require("real.md")).shouldNotBeNull(), StandardCharsets.UTF_8) shouldBe "real"
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
})

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
