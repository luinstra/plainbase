package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Byte-fidelity (W4 §6 #3, fix F / P0-2): the committed blob is EXACTLY the hook bytes, filter-free and
 * disk-independent. `hash-object --no-filters -w --stdin` (no `--path`) means NO attribute lookup fires,
 * so CRLF, a hostile clean filter, an LFS pattern, and `working-tree-encoding` are all mooted by
 * construction — and the bytes committed are the hook bytes even when disk bytes differ (poison). Read
 * back through both `git show HEAD:path` and the JGit tree blob.
 */
class GitByteFidelityTest : FunSpec({

    val page = TreePath.require("page.md")

    fun committedBytes(root: java.nio.file.Path) = openOracle(root).use { repo ->
        val head = repo.headCommits().first()
        repo.blobBytes(head, "page.md")
    }

    test("CRLF survives to the committed blob") {
        withGitRepoHome { root, exec, home ->
            // A hostile .gitattributes that would mangle EOL/encoding IF an attribute lookup fired.
            Files.writeString(root.resolve(".gitattributes"), "*.md text eol=lf working-tree-encoding=UTF-16\n")
            val crlf = "line one\r\nline two\r\n".toByteArray()
            providerOver(exec, root, home).commit(page, crlf)
            committedBytes(root)!! shouldBe crlf
        }
    }

    test("a hostile clean filter does not alter the committed blob") {
        withGitRepoHome { root, exec, home ->
            // Configure a clean filter that would uppercase everything, plus an attribute binding it.
            exec.run(listOf("config", "filter.evil.clean", "tr a-z A-Z"))
            Files.writeString(root.resolve(".gitattributes"), "*.md filter=evil\n")
            val bytes = "lowercase content\n".toByteArray()
            providerOver(exec, root, home).commit(page, bytes)
            committedBytes(root)!! shouldBe bytes // never uppercased — no attribute lookup ran
        }
    }

    test("an LFS pattern does not alter the committed blob") {
        withGitRepoHome { root, exec, home ->
            Files.writeString(root.resolve(".gitattributes"), "*.md filter=lfs diff=lfs merge=lfs -text\n")
            val bytes = "real content not an lfs pointer\n".toByteArray()
            providerOver(exec, root, home).commit(page, bytes)
            committedBytes(root)!! shouldBe bytes
        }
    }

    test("working-tree-encoding=UTF-16 does not alter the committed blob") {
        withGitRepoHome { root, exec, home ->
            Files.writeString(root.resolve(".gitattributes"), "*.md working-tree-encoding=UTF-16\n")
            val utf8 = "utf-8 bytes stay utf-8\n".toByteArray()
            providerOver(exec, root, home).commit(page, utf8)
            committedBytes(root)!! shouldBe utf8 // never re-encoded to UTF-16
        }
    }

    test("the committed blob is the hook bytes when disk bytes differ (poison)") {
        withGitRepoHome { root, exec, home ->
            // Disk holds DIFFERENT bytes than the hook passes — the commit must capture the HOOK bytes
            // (staged from stdin), proving it never re-reads disk.
            Files.writeString(root.resolve("page.md"), "POISON disk content\n")
            val hookBytes = "the real hook bytes\n".toByteArray()
            providerOver(exec, root, home).commit(page, hookBytes)
            committedBytes(root)!! shouldBe hookBytes
        }
    }
})
