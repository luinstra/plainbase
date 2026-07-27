package com.plainbase.frameworks.config

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.CommitGlob
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/**
 * `agentDirectCommit` is the AGENT DIRECT-COMMIT PRIVILEGE GATE: an in-glob COMMIT-agent write lands UNREVIEWED.
 * So this suite is a security suite, and its subject is one promise:
 *
 * **NO CONFIG THAT AUTHORIZES X TODAY MAY AUTHORIZE ANYTHING DIFFERENT AFTER THE UPGRADE.**
 *
 * That promise is why the per-root globs are a structured BLOCK and not a `<root>:<pattern>` string prefix. A colon
 * is a legal `TreePath` segment character AND a legal glob character — a directory literally named `archive:2024`
 * indexes, serves, and glob-matches today — so an in-string grammar would silently RETARGET such an operator's
 * pattern the day they added a root named `archive`: revoking it in main, and granting it, unasked, inside the new
 * root. An unreviewed agent write would start landing somewhere new because of a version bump and nothing else.
 *
 * A strict-charset separator does not save it, and it is worth knowing why: `archive` is BOTH a legal root slug and
 * a legal directory name, so the collision the rule would prevent is exactly the collision it admits. There is no
 * printable separator that is unambiguous against the path charset — which is the argument for removing the
 * separator, not for escaping it.
 */
class DirectCommitGlobConfigTest : FunSpec({

    fun <T> withConf(conf: String, block: (Map<String, String>) -> T): T {
        val data = Files.createTempDirectory("pb-glob-conf")
        return try {
            Files.writeString(data.resolve("plainbase.conf"), conf)
            block(mapOf("DATA_DIR" to data.toString()))
        } finally {
            data.toFile().deleteRecursively()
        }
    }

    val twoRoots = """roots { main { path = "/roots/m" }, archive { path = "/roots/a" } }"""

    // ---- THE retarget pin: the row this whole design exists for -----------------------------------

    test("the RETARGET pin: a colon-bearing MAIN glob stays a MAIN glob even once a root of that name exists") {
        withConf(
            """
            $twoRoots
            auth { agentDirectCommit { globs = ["archive:2024/**"] } }
            """.trimIndent(),
        ) { env ->
            val globs = PlainbaseConfig.fromEnvAndFile(env).agentDirectCommitGlobs()

            withClue("the pattern did NOT move: it still authorizes the colon-bearing FOLDER in MAIN") {
                globs.single().root shouldBe RootName.PRIMARY
                globs.single().matches(TreePath.require("archive:2024/plan.md")).shouldBeTrue()
            }
            withClue("and it grants NOTHING inside the `archive` ROOT - the escalation an in-string grammar would have created") {
                globs.any { it.root == RootName.require("archive") }.shouldBeFalse()
                globs.none { it.matches(TreePath.require("2024/plan.md")) }.shouldBeTrue()
            }
        }
    }

    test("a colon is a LITERAL in the pattern grammar - a glob is never split on anything but the path separator") {
        val glob = CommitGlob.parse("archive:2024/**")
        glob.matches(TreePath.require("archive:2024/plan.md")).shouldBeTrue()
        glob.matches(TreePath.require("2024/plan.md")).shouldBeFalse()
    }

    // ---- back-compat: a pre-C4 config loads to the identical grant set ----------------------------

    test("a PRE-C4 config (globs list only, no block) loads to the identical CommitGlob set, every entry rooted at main") {
        withConf(
            """
            $twoRoots
            auth { agentDirectCommit { globs = ["notes/**", "guides/*.md"] } }
            """.trimIndent(),
        ) { env ->
            val globs = PlainbaseConfig.fromEnvAndFile(env).agentDirectCommitGlobs()
            globs.map { it.root } shouldContainExactly listOf(RootName.PRIMARY, RootName.PRIMARY)
            globs[0].matches(TreePath.require("notes/a.md")).shouldBeTrue()
            globs[1].matches(TreePath.require("guides/a.md")).shouldBeTrue()
        }
    }

    test("the per-root BLOCK is the ONLY way to grant an extra root, and it is opt-in") {
        withConf(
            """
            $twoRoots
            auth { agentDirectCommit { globs = ["notes/**"], roots { archive = ["2024/**"] } } }
            """.trimIndent(),
        ) { env ->
            val globs = PlainbaseConfig.fromEnvAndFile(env).agentDirectCommitGlobs()
            globs.single { it.root == RootName.PRIMARY }.matches(TreePath.require("notes/a.md")).shouldBeTrue()
            globs.single { it.root == RootName.require("archive") }.matches(TreePath.require("2024/plan.md")).shouldBeTrue()
        }
    }

    test("roots.main alone is a complete main grant source rather than an empty legacy fallback") {
        withConf(
            """
            $twoRoots
            auth { agentDirectCommit { roots { main = ["drafts/**"], archive = ["2024/**"] } } }
            """.trimIndent(),
        ) { env ->
            val globs = PlainbaseConfig.fromEnvAndFile(env).agentDirectCommitGlobs()

            globs.single { it.root == RootName.PRIMARY }.matches(TreePath.require("drafts/plan.md")).shouldBeTrue()
            globs.single { it.root == RootName.require("archive") }.matches(TreePath.require("2024/plan.md")).shouldBeTrue()
        }
    }

    test("the env var still overrides MAIN's file list and leaves an EXTRAS-only block intact (the LEGAL combination)") {
        withConf(
            """
            $twoRoots
            auth { agentDirectCommit { globs = ["notes/**"], roots { archive = ["2024/**"] } } }
            """.trimIndent(),
        ) { env ->
            val config = PlainbaseConfig.fromEnvAndFile(env + ("PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS" to "from-env/**"))
            val globs = config.agentDirectCommitGlobs()

            withClue("env-wins over the FILE key, exactly as it always has") {
                globs.single { it.root == RootName.PRIMARY }.matches(TreePath.require("from-env/a.md")).shouldBeTrue()
            }
            withClue("extras are FILE-ONLY by construction (there is no env path to a roots block at all), so the block survives") {
                globs.single { it.root == RootName.require("archive") }.matches(TreePath.require("2024/plan.md")).shouldBeTrue()
            }
        }
    }

    // ---- main's list has exactly ONE key, and it has THREE possible spellings ---------------------

    test("`globs` + `roots.main` together REFUSE at boot, naming BOTH keys - never a union, never a silent winner") {
        withConf(
            """
            $twoRoots
            auth { agentDirectCommit { globs = ["notes/**"], roots { main = ["other/**"] } } }
            """.trimIndent(),
        ) { env ->
            val error = shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
            error.message!! shouldContain "agentDirectCommit.globs"
            error.message!! shouldContain "agentDirectCommit.roots.main"
        }
    }

    test("the ENV VAR + `roots.main` together ALSO refuse - the env var is a THIRD spelling of the same one key") {
        // The pair is what proves the refusal is about the CONFLICT and not about the env var: a union would silently
        // WIDEN what an agent may commit unreviewed, and a winner would silently DROP an authorization the operator
        // wrote. Neither is acceptable on an authorization surface, so "unspecified" is not an option.
        withConf(
            """
            $twoRoots
            auth { agentDirectCommit { roots { main = ["other/**"] } } }
            """.trimIndent(),
        ) { env ->
            val error = shouldThrow<IllegalArgumentException> {
                PlainbaseConfig.fromEnvAndFile(env + ("PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS" to "from-env/**"))
            }
            error.message!! shouldContain "PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS"
            error.message!! shouldContain "agentDirectCommit.roots.main"
        }
    }

    test("the SAME config WITHOUT roots.main loads, with the env var as main's list (the refusal is about the conflict)") {
        withConf(
            """
            $twoRoots
            auth { agentDirectCommit { roots { archive = ["2024/**"] } } }
            """.trimIndent(),
        ) { env ->
            val globs = PlainbaseConfig.fromEnvAndFile(env + ("PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS" to "from-env/**"))
                .agentDirectCommitGlobs()
            globs.single { it.root == RootName.PRIMARY }.matches(TreePath.require("from-env/a.md")).shouldBeTrue()
        }
    }

    // ---- a glob that authorizes nothing must not sit there LOOKING like it does -------------------

    test("an UNKNOWN root key in the block refuses at boot, naming the key") {
        withConf(
            """
            $twoRoots
            auth { agentDirectCommit { roots { nosuchroot = ["**"] } } }
            """.trimIndent(),
        ) { env ->
            val error = shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
            withClue("a glob for a root that does not exist authorizes nothing - and an operator who wrote it believes it does") {
                error.message!! shouldContain "auth.agentDirectCommit.roots.nosuchroot"
            }
        }
    }

    test("an ILLEGAL-SLUG block key refuses with the parseRoot message") {
        withConf(
            """
            $twoRoots
            auth { agentDirectCommit { roots { "Not A Root" = ["**"] } } }
            """.trimIndent(),
        ) { env ->
            val error = shouldThrow<IllegalArgumentException> { PlainbaseConfig.fromEnvAndFile(env) }
            error.message!! shouldContain "not a valid root name"
        }
    }

    test("a direct-commit glob on a NON-editable root WARNS - the editable gate denies first, so it can grant nothing") {
        withConf(
            """
            roots { main { path = "/roots/m" }, archive { path = "/roots/a", editable = false } }
            auth { agentDirectCommit { roots { archive = ["**"] } } }
            """.trimIndent(),
        ) { env ->
            val warning = PlainbaseConfig.fromEnvAndFile(env).rootsWarnings().single { it.contains("agentDirectCommit") }
            withClue("silently doing nothing is how an operator ends up believing an agent has write access it does not have") {
                warning shouldContain "archive"
                warning shouldContain "editable = false"
            }
        }
    }

    test("the same warning covers a non-editable MAIN - whose globs live in their OWN key, not the by-root block") {
        withConf(
            """
            roots { main { path = "/roots/m", editable = false }, archive { path = "/roots/a" } }
            auth { agentDirectCommit { globs = ["**"] } }
            """.trimIndent(),
        ) { env ->
            val warning = PlainbaseConfig.fromEnvAndFile(env).rootsWarnings().single { it.contains("agentDirectCommit") }
            withClue("main is the root every glob was written for, so a by-root-map walk would miss the likeliest trap of all") {
                warning shouldContain "main"
                warning shouldContain "editable = false"
            }
        }
    }
})
