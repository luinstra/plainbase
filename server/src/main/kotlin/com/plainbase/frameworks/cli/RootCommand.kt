package com.plainbase.frameworks.cli

import com.plainbase.bootGateFor
import com.plainbase.domain.content.StoreRead
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootShadow
import com.plainbase.domain.service.CanonicalUrlBuilder
import com.plainbase.frameworks.config.ManagedRootsFile
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.filesystem.rootIsTraversable
import com.plainbase.frameworks.markdown.FrontmatterReader
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plainbase root <add|remove|list>` - the topology CLI (multi-root C5). It writes ONE file it fully owns,
 * `DATA_DIR/roots.conf`; the operator's `plainbase.conf` is NEVER opened for writing, and that guarantee is an
 * ABSENCE OF CODE rather than a careful round-trip. Restart to apply: this does not hot-reload and must not
 * pretend it can.
 *
 * **THE RULE THIS FILE EXISTS TO OBEY: it does not know what the server's checks ARE, and it must not.**
 *
 * Six revisions of the design tried to keep a hand-written list of "the checks `serve` does", and every
 * version of that list was missing an item - twice, the missing items were inside the fix for the previously
 * missing items. A list of somebody else's checks always drifts. So this command enumerates nothing: it
 * serializes the candidate `roots.conf` to a STRING, loads that string with the SERVER'S OWN loader, runs the
 * SERVER'S OWN boot gate over the result, diffs the structured refusals against the same gate over the config
 * as it stands, and only then writes the very bytes it validated. What is validated IS what is written.
 *
 * It may therefore never NAME an individual server check - not the native-root guard, not the topology matrix,
 * not the git gate, not the wiring they need. `CliBootGateArchitectureTest` fails the build if it does,
 * because six revisions of prose did not stop anyone and a test will.
 *
 * **THE POLICY, one rule, both verbs: refuse if and only if you INTRODUCE a refusal.** A refusal already
 * present in the current config is the operator's, not ours: it WARNS and blocks nothing. `plainbase root`
 * never makes a config less bootable; it does not promise to fix one the operator already broke, and it never
 * holds an operator hostage over a fault it did not cause. That matters most for `remove` - under any stricter
 * rule the only command that can REPAIR a broken topology would decline to run because the topology is broken.
 *
 * What the CLI still OWNS, and must: the argv grammar, which makes some boot rules UNREACHABLE rather than
 * re-checked (`--history` has no `auto`, so nothing here can construct the AUTO extra that boot refuses); the
 * one boot rule it makes unreachable the OTHER way round - a BLANK path, which the loader refuses but would
 * never see, because this command absolutizes before it serializes and `Path.of("")` is the CWD; and the
 * policies boot has NO opinion on - a name already in `roots.conf` (HOCON would field-merge two entries and
 * silently repoint the path), a name that is not there, and the SHADOW refusal, which is the one place this
 * command is deliberately STRICTER than boot.
 */
object RootCommand {

    private val logger = KotlinLogging.logger {}

    private const val USAGE_EXIT = 2

    private const val USAGE = "usage: plainbase root <add <name> <path> [--editable] [--history off|native] [--force] | " +
        "remove <name> | list>"

    /** Bounded, because two `root add`s in a provisioning script are a normal thing to write. */
    private const val LOCK_WAIT_MILLIS = 5_000L
    private const val LOCK_POLL_MILLIS = 50L

    fun runAsMain(args: List<String>): Int = run(args, System.getenv())

    /**
     * The [runAsMain] body, over an injected [env].
     *
     * The ENV is the seam, not a config - deliberately, and it is Invariant W: this command MUTATES config, so it
     * must RE-READ config INSIDE the critical section. A `run(args, config)` seam (the idiom `adopt`/`reindex`/
     * `admin` use, and correct for them, because none of them writes config) would hand it a snapshot taken
     * BEFORE the lock, which is precisely the check-then-act this design exists to eliminate.
     *
     * The catch is `reindex`/`adopt`'s, verbatim: an UNEXPECTED failure is a diagnostic (the facade, never
     * `println`) and exit 1, not a stack trace dumped at an operator. This is the first tool in the codebase that
     * writes an operator's config file, and it does not get to be the one that greets them with a JVM trace - the
     * realistic path being a control character in a `--force`'d path, which `hoconQuote` refuses by design.
     */
    fun run(args: List<String>, env: Map<String, String>): Int =
        try {
            execute(args, env)
        } catch (e: Exception) {
            logger.error(e) { "root failed" }
            1
        }

    private fun execute(args: List<String>, env: Map<String, String>): Int {
        // PRE-LOCK: pure argument parsing. It reads no config, touches no filesystem and decides nothing about
        // the topology - so there is nothing to carry across the lock boundary, and no check out here has any
        // authority. (A "validate, then lock, then re-check the NAME" protocol is a check-then-act with a window
        // exactly wide enough: two individually-valid adds whose paths NEST compose into a topology that nothing
        // ever validated, because the lock windows never overlap and a name check is not a topology check.)
        val request = parse(args) ?: return USAGE_EXIT
        if (request is RootArgs.List) return list(env)

        // The ONLY pre-lock state read, and it reads no config FILE: DATA_DIR LOCATES every config file and so is
        // the one field that can never come from one - it is resolved from env/default, never file-derived. This
        // is therefore the same value config.dataDir will hold, by construction rather than by luck. It locates
        // the lock and is authoritative for nothing else.
        val dataDir = PlainbaseConfig.dataDirFrom(env)
        val lock = awaitRootsLock(dataDir)
        if (lock == null) {
            System.err.println(
                "root: another `plainbase root` command is holding ${dataDir.resolve(DataDirLock.ROOTS_LOCK_FILE_NAME)}",
            )
            return 1
        }
        return lock.use {
            // EVERY read and EVERY check from here down happens under the lock, over state read under it. ONE read
            // of the config, ONE snapshot: the managed roots come off it, never from a second parse of roots.conf
            // (which `root add` replaces atomically, so two reads of it are two observations of a changing file
            // rather than one observation of it).
            val config = load("root", env) ?: return@use 1
            val managed = config.roots.list.filter { it.name in config.roots.managed }
            when (request) {
                is RootArgs.Add -> add(config, env, managed, request)
                is RootArgs.Remove -> remove(config, env, managed, request)
                RootArgs.List -> error("unreachable: list never takes the lock")
            }
        }
    }

    /** The config as it stands, through the loader's own error funnel (a clean `<command>: <msg>` line + null). */
    private fun load(command: String, env: Map<String, String>): PlainbaseConfig? =
        PlainbaseConfig.loadForCommand(command, resolve = { PlainbaseConfig.fromEnvAndFile(env) })

    /**
     * `root add <name> <path>`. A refusal writes NO CONFIG, and under this ordering that is structural rather
     * than careful: the only config write is the last step, and nothing before it puts candidate bytes on disk.
     * There is no temp file to leak and no cleanup to forget on a refusal path.
     *
     * (The lock marker is exempt, deliberately: acquiring `roots.lock` creates the DATA_DIR and the lock file,
     * because that is what an advisory lock IS. The guarantee is about the ARTIFACT - on a refusal, no candidate
     * bytes and no `roots.conf` bytes reach disk.)
     */
    private fun add(config: PlainbaseConfig, env: Map<String, String>, managed: List<Root>, request: RootArgs.Add): Int {
        // CLI-OWNED, and scoped to the MANAGED set on purpose. Boot has no opinion on re-adding a name this file
        // already holds: HOCON would field-merge the two entries and silently repoint the path, so the gate would
        // happily pass a config that quietly changed where a root points. Idempotence is NOT silently re-adding -
        // a re-add with a different path is a topology change the operator must state.
        //
        // A name declared in the OPERATOR's plainbase.conf deliberately does NOT get a check here: it falls
        // through to the gate, whose candidate load raises the two-file overlap refusal with the loader's own
        // message. One rule, one home, one message - and a hand-written duplicate of it here would FAIL
        // RootCommandTest rather than pass it.
        managed.firstOrNull { it.name == request.name }?.let { existing ->
            System.err.println("root add: root '${request.name.value}' already exists (path: ${existing.localPath})")
            return 1
        }
        // Never store a relative path: the server's CWD is not the operator's, and the DECLARED path is what gets
        // served (ADR-0011 D8), so a relative one would resolve differently under systemd.
        val path = Path.of(request.path).toAbsolutePath().normalize()
        val newRoot = Root(
            name = request.name,
            backend = RootBackend.Local(path),
            editable = request.editable,
            history = request.history,
        )
        // APPENDED, and that is Invariant R rather than an implementation detail: rank decides the cross-root
        // duplicate-id winner and the LOWEST index wins, so a newcomer always ranks last and therefore LOSES
        // against every incumbent. `root add` is an operator convenience and it must not be able to take a page
        // id away from a root that was already serving it. (Never rebuild the list as `listOf(main) + extras +
        // new` - that hoist forces main to rank 0 and silently reassigns every shared id to main's page.)
        val hocon = gate(config, env, "add", managed + newRoot) ?: return 1

        // CLI-OWNED, and the ONE place this command is deliberately STRICTER than boot (boot only WARNs, per
        // ADR-0011 D3's accepted tradeoff - a refusal there would let an author creating a top-level folder
        // through the product's own UI brick the next restart). It runs AFTER the gate, so the cheap
        // deterministic refusals fire before the corpus scan - and it MAY run after the gate because it can only
        // refuse HARDER: it never approves what the gate rejected, and it never touches the candidate bytes.
        if (!request.force) {
            when (val shadow = shadowScan(config, request.name)) {
                is Shadow.Segments -> {
                    System.err.println(
                        "root add: '${request.name.value}' is already a top-level segment of the main root " +
                            "(${shadow.paths.joinToString(", ")}). Adding it would silently re-point every circulating " +
                            "link through /docs/${request.name.value}/... into the NEW root - main's own entries under " +
                            "that segment would then be reachable only by permalink. Rename the main-root entry, pick " +
                            "another root name, or pass --force to accept the collision. (This scan cannot see a " +
                            "`redirect_from` alias row - the boot WARN is the backstop for those - nor a folder someone " +
                            "creates tomorrow.)",
                    )
                    return 1
                }
                // Fail CLOSED. A main root that cannot be read says NOTHING about what the new name would shadow,
                // and reporting "shadows nothing" from a tree we could not read is the root-down-as-absent lie the
                // classified read exists to make unsayable.
                Shadow.MainDown -> {
                    System.err.println(
                        "root add: the main root at ${config.roots.main.localPath} is not readable right now, so the " +
                            "shadow check cannot run and this add would be accepted BLIND. Restore the path (a missing " +
                            "mount, a permission drop), or pass --force to add without the check.",
                    )
                    return 1
                }
                // Fail CLOSED for the SAME reason, one page down: an absence this scan cannot verify never becomes a
                // fact (C1), and here the fact it would silently become is "that page's slug shadows nothing".
                is Shadow.Unverified -> {
                    System.err.println(
                        "root add: main's page ${shadow.path} was listed by the scan and could not be read, so the shadow " +
                            "check ran against a view that is not main and would be accepted on incomplete evidence. " +
                            "Re-run (it self-heals if the page was simply being written), or pass --force to add without " +
                            "the check.",
                    )
                    return 1
                }
                Shadow.None -> Unit
            }
        }
        // An add always leaves at least one managed root, so the delete arm `remove` needs cannot arise here.
        ManagedRootsFile.writeAtomically(config.managedRootsPath, requireNotNull(hocon.text))
        println(
            "added root '${request.name.value}' -> $path " +
                "(editable = ${request.editable}, history = ${request.history.name.lowercase()})",
        )
        println("written to ${config.managedRootsPath}")
        println("restart the server to apply.")
        return 0
    }

    /** `root remove <name>` - same lock, same gate, same one policy. */
    private fun remove(config: PlainbaseConfig, env: Map<String, String>, managed: List<Root>, request: RootArgs.Remove): Int {
        // CLI-OWNED, both of them. The second is less a policy than an honest admission: this command cannot
        // edit plainbase.conf, and pretending otherwise would mean corrupting an operator's hand-written file.
        if (config.roots.list.none { it.name == request.name }) {
            System.err.println("root remove: no such root '${request.name.value}'")
            return 1
        }
        if (request.name !in config.roots.managed) {
            System.err.println(
                "root remove: root '${request.name.value}' is declared in plainbase.conf, which `plainbase root` never " +
                    "writes - remove it there yourself.",
            )
            return 1
        }
        // A FILTER, which preserves the survivors' relative order and so preserves Invariant R.
        val hocon = gate(config, env, "remove", managed.filterNot { it.name == request.name }) ?: return 1
        // Removing the LAST managed root DELETES roots.conf rather than leaving an empty `roots {}` husk: that
        // returns the install to byte-identical legacy behavior instead of stranding it in the strict EXPLICIT
        // matrix over a file with nothing in it. The gate already ran over the artifact the DELETE actually
        // produces - "there is no roots.conf" - so the artifact validated IS the artifact promoted, even when
        // promoting it means unlinking a file.
        if (hocon.text == null) {
            ManagedRootsFile.delete(config.managedRootsPath)
            println("removed root '${request.name.value}' (the last CLI-managed root; ${config.managedRootsPath} deleted)")
        } else {
            ManagedRootsFile.writeAtomically(config.managedRootsPath, hocon.text)
            println("removed root '${request.name.value}' from ${config.managedRootsPath}")
        }
        // The consequences this command is legally obliged to name. The middle one is the boot refusal the gate
        // structurally CANNOT evaluate - it reads the app DB, which may not be opened without the DATA_DIR lock,
        // and this command deliberately does not take it - so it is PRINTED instead. A silent exclusion is how an
        // operator meets an unexplained refusal on their next restart.
        println("restart the server to apply. Then:")
        println(
            "  - its pages keep their id_map / url_alias / page_checkpoint rows and become DETACHED; re-adding the " +
                "same name revives them, subject to the ADR-0011 D2 supersede rules.",
        )
        println(
            "  - IF THAT ROOT HELD EVERY PAGE BINDING IN THIS DATA_DIR, THE NEXT BOOT WILL REFUSE TO SERVE - it reads " +
                "as a DATA_DIR belonging to a different deployment. Re-add the root, or follow that refusal's remedy.",
        )
        println(
            "  - re-adding it later APPENDS its rank, so it would then LOSE any cross-root duplicate-id contest it " +
                "wins today (remove + add is the documented rename path).",
        )
        return 0
    }

    /**
     * `root list`. It loads config, probes the filesystem, and takes NO lock and opens NO database.
     *
     * **Lockless is safe for ONE structural reason: there is exactly ONE read.** Provenance comes off the same
     * snapshot as the topology, never from a second parse of `roots.conf` and never from comparing paths. (The
     * tempting argument - "safe because the writer is atomic, so a reader sees either the whole old file or the
     * whole new one" - is true of EACH read and says nothing about a PAIR of them. Two atomic reads of a
     * mutating file are not one atomic read: a `list` racing an `add` would otherwise print a topology from read
     * #1 annotated with a provenance from read #2.)
     *
     * It never claims to know LIVE availability: root status is in-memory and sticky until restart, so a
     * separate process cannot know it. The on-disk probe is what THIS process can see; `/healthz` is what the
     * server knows.
     */
    private fun list(env: Map<String, String>): Int {
        val config = load("root list", env) ?: return 1
        val headers = listOf("NAME", "PATH", "WRITES", "HISTORY", "DECLARED IN", "ON DISK")
        val rows = config.roots.list.map { root ->
            val path = root.localPath
            listOf(
                root.name.value,
                path?.toString() ?: "(object)",
                if (root.editable) "editable" else "read-only",
                root.history.name.lowercase(),
                provenanceOf(config, root),
                onDisk(path),
            )
        }
        val widths = headers.indices.map { column -> (rows + listOf(headers)).maxOf { it[column].length } }
        (listOf(headers) + rows).forEach { row ->
            println(row.mapIndexed { column, cell -> cell.padEnd(widths[column]) }.joinToString("  ").trimEnd())
        }
        println()
        println("`ON DISK` is what this process can see right now - it is NOT serving state.")
        println("For live per-root availability, ask the running server: GET http://${config.host}:${config.port}/healthz")
        return 0
    }

    /**
     * The `ON DISK` cell, and `present` means what the SERVER means by it: [rootIsTraversable] is the one liveness
     * predicate - a readable, SEARCHABLE directory - shared with boot's availability probe and the runtime's.
     * A `Files.isDirectory` of its own would call a root with no read bit `present` and then leave the operator to
     * discover the 503 themselves, which is the CLI disagreeing with the server about the only fact this column
     * reports.
     *
     * Only the EXPLANATION of a false is decided here; the verdict is never re-derived. Naming the permission case
     * is the point - it is the state an operator cannot see and would otherwise chase into the server logs.
     */
    private fun onDisk(path: Path?): String = when {
        path == null -> "n/a" // object-backed: there is no local tree for this process to probe
        rootIsTraversable(path) -> "present"
        Files.isDirectory(path) -> "NOT READABLE"
        else -> "NOT PRESENT"
    }

    /** Provenance, as a pure function of the ONE snapshot the loader built - never a second read, never a path compare. */
    private fun provenanceOf(config: PlainbaseConfig, root: Root): String = when {
        root.name in config.roots.managed -> PlainbaseConfig.MANAGED_ROOTS_FILE
        // The only root that is neither CLI-managed nor hand-declared is a SYNTHESIZED main - an absent `roots {}`
        // block contributes nothing else - so `mainDeclared` already answers this and no main-by-name test is needed.
        !config.roots.mainDeclared -> "CONTENT_DIR"
        else -> "plainbase.conf"
    }

    /** The validated artifact: the exact bytes to promote, or null text for "there is no roots.conf" (the delete). */
    private data class Artifact(val text: String?)

    /**
     * **THE GATE. This is the whole command.**
     *
     * 1. Serialize the candidate managed roots to a STRING. This exact string is what gets written - nothing
     *    else is ever serialized - so "the artifact I validated" and "the artifact I wrote" are the same value.
     *    A serializer bug (a mis-quoted path, a dropped `history` key) is caught, because the round trip IS the
     *    check.
     * 2. Load it with the REAL loader, which raises every refusal the config BUILD can raise. **A loader refusal
     *    is ALWAYS ours**, and that is a proof rather than an assumption: the current config LOADED a moment ago
     *    (a config you cannot read is not a config you may edit), so if the candidate does not load, the fault
     *    was introduced by the text we just serialized. No baseline needed - refuse.
     * 3. Run the REAL boot gate over the candidate AND over the config as it stands, and diff the structured
     *    refusals BY KEY. A key in the candidate and not the baseline is one WE introduced -> refuse, write
     *    nothing. A key in BOTH is pre-existing -> WARN, and proceed.
     *
     * **The order of 2 and 3 is load-bearing, not stylistic.** The candidate must LOAD before any gate runs: an
     * object-mode install refuses a `roots {}` block at LOAD, and that is the only thing keeping the gate - and
     * therefore the app DB - out of object mode. Do not fuse them, and do not evaluate the baseline first.
     *
     * **The diff is over KEYS, never over prose**, and both halves of that matter. A message names paths, so an
     * unrelated add can change its text; and the legacy and explicit arms of the topology matrix word the SAME
     * fault differently, so a string diff would call a pre-existing DATA_DIR collision NEW and refuse an add
     * that introduced nothing - trapping exactly the operator this policy exists to protect.
     */
    private fun gate(config: PlainbaseConfig, env: Map<String, String>, verb: String, candidateRoots: List<Root>): Artifact? {
        val text = if (candidateRoots.isEmpty()) null else ManagedRootsFile.serialize(candidateRoots)
        val candidate = PlainbaseConfig.loadForCommand(
            "root $verb",
            resolve = { PlainbaseConfig.fromEnvAndCandidateRoots(text, env) },
        ) ?: return null
        val candidateRefusals = bootGateFor(candidate).refusals
        val baselineKeys = bootGateFor(config).refusals.map { it.key }.toSet()

        val introduced = candidateRefusals.filterNot { it.key in baselineKeys }
        if (introduced.isNotEmpty()) {
            introduced.forEach { System.err.println("root $verb: ${it.message}") }
            return null // NOTHING was written
        }
        // Pre-existing refusals are the operator's, not ours. Say so, loudly, and carry on. stderr rather than
        // the logging facade because this is the command's operator-facing contract, on the same channel as the
        // refusals above - a warning an operator does not see is not a warning.
        candidateRefusals.forEach {
            System.err.println("root $verb: WARNING: this config already refuses to boot, and this command did not cause it: ${it.message}")
        }
        // The other half of what boot SAYS about a topology, and it is not optional: a mistyped path is not a
        // refusal (an extra root may legitimately be an unmounted volume), so a `root add notes /srv/dosc` that
        // printed only refusals would exit 0 with nothing but cheerful news about a root that will 503. `serve`
        // prints these; a CLI that validated half the server's surface would be back to keeping its own list of
        // which half matters.
        candidate.rootsWarnings().forEach { System.err.println("root $verb: WARNING: $it") }
        return Artifact(text)
    }

    /** The shadow scan's outcomes - and the two REFUSALS are why it is not a nullable list (D5/C1). */
    private sealed interface Shadow {
        data object None : Shadow

        data class Segments(val paths: List<String>) : Shadow

        /** Main is not readable, so NOTHING may be concluded about what the new name shadows. */
        data object MainDown : Shadow

        /**
         * A page main's own walk had just listed could not be READ (C1). The scan's view of main is therefore not
         * main, and this check cannot be completed - so it is not completed, rather than completed wrongly.
         *
         * The CLI has no index to ask (no DATA_DIR lock, no database - deliberately), so it cannot tell a page that
         * was deleted mid-scan from one that is momentarily unreadable. That is exactly an `AbsenceUnknown`, and the
         * rule is the same here as everywhere: it never becomes a fact. It matters because the unread page's `slug:`
         * override is the one thing that could have shadowed the new root name, and silently dropping it turns a
         * REFUSAL into an approval - the one check boot does not repeat.
         */
        data class Unverified(val path: String) : Shadow
    }

    /**
     * Main's top-level segments that [name] would shadow (D-C5-6).
     *
     * Fed from a plain content scan - no database, no DATA_DIR lock, no index build - through the SAME
     * composition the indexer uses, so no second slugification exists to drift. It sees exactly what the indexer
     * sees: the same ignore rules the server wires, and the same DATA_DIR exclusion.
     *
     * The frontmatter read is CLASSIFIED, and it is the reason [Shadow] has three arms: a bare null read cannot
     * tell a page that was deleted mid-scan from a main root that vanished under us, and here the two are
     * opposite answers - the first removes one slug from a set, the second invalidates the whole set. A downed
     * main scanning as "shadows nothing" would let a shadowing topology through the ONE check boot does not
     * repeat.
     *
     * **The gaps are real, and the refusal text names them rather than letting silence pass for a guarantee:** a
     * `redirect_from` alias row lives in the DB and outlives the frontmatter that minted it, so no filesystem
     * scan can find one (the BOOT warn catches those, which is the strongest reason that warn exists at all);
     * and nothing catches a folder created through the product's own UI five minutes later - ADR-0011 D3's
     * explicitly accepted tradeoff. An object-mode main has no local tree to scan, and is moot anyway: the gate
     * refuses an object-mode candidate at LOAD.
     */
    private fun shadowScan(config: PlainbaseConfig, name: RootName): Shadow {
        val mainPath = config.roots.main.localPath ?: return Shadow.None
        val store = LocalContentStore(root = mainPath, ignoreRules = IgnoreRules(), exclusions = listOf(config.dataDir))
        val scan = store.scan()
        val pages = scan.files.filter { it.path.name.endsWith(".md") }.map { file ->
            val slugOverride = when (val read = store.readClassified(file.path)) {
                is StoreRead.Bytes -> FrontmatterReader().parse(read.bytes).scalar("slug")
                // A page the walk listed a moment ago and cannot read now. See [Shadow.Unverified]: the missing
                // `slug:` override is precisely what could have shadowed the new name, so the scan REFUSES rather
                // than quietly answering "shadows nothing" off a view it knows is incomplete.
                StoreRead.NoBytes -> return Shadow.Unverified(file.path.value)
                StoreRead.RootDown -> return Shadow.MainDown
            }
            CanonicalUrlBuilder.PageInput(path = file.path, rawName = file.rawName, slugOverride = slugOverride)
        }
        val urls = CanonicalUrlBuilder.build(root = RootName.MAIN, pages = pages, folders = scan.folders)
        // A same-role slug-collision LOSER carries a null url path, but its WINNER carries the identical segment,
        // so no loser can remove a segment from the set. Ignoring them is correct, not a shortcut.
        val index = RootShadow.topLevelIndex(
            urlPaths = urls.byPage.values.mapNotNull { it.urlPath } +
                CanonicalUrlBuilder.folderUrlPaths(scan.folders).values.filterNotNull(),
            contentPaths = scan.files.map { it.path } + scan.folders.map { it.path },
        )
        val offenders = index[name.value]?.map { it.value }?.distinct()?.sorted()
        return if (offenders == null) Shadow.None else Shadow.Segments(offenders)
    }

    /**
     * A BOUNDED poll, not a bare failure: `tryLock` is non-blocking and returns null on contention, and two
     * `root add`s in a provisioning script are a normal thing to write - serializing them is free. The retry
     * lives HERE and not in [DataDirLock], so `serve`'s "held means someone else owns this DATA_DIR, refuse now"
     * semantics stay exactly as they are.
     */
    private fun awaitRootsLock(dataDir: Path): DataDirLock? {
        val deadline = System.nanoTime() + LOCK_WAIT_MILLIS * 1_000_000
        while (true) {
            DataDirLock.tryAcquire(dataDir, DataDirLock.ROOTS_LOCK_FILE_NAME)?.let { return it }
            if (System.nanoTime() >= deadline) return null
            Thread.sleep(LOCK_POLL_MILLIS)
        }
    }

    private fun parse(argv: List<String>): RootArgs? {
        val request = when (argv.firstOrNull()) {
            "add" -> parseAdd(argv.drop(1))
            "remove" -> parseRemove(argv.drop(1))
            "list" -> if (argv.size == 1) RootArgs.List else usage()
            else -> usage()
        } ?: return null
        // D-C5-2, and it is the ONE main-by-name comparison in this file (ledgered in RootWiringArchitectureTest).
        // Main's protection is STRUCTURAL everywhere else - no code path here can write main's name into any file
        // - but argv is TEXT, and text cannot be made to fail typecheck. So it needs a runtime refusal, here, once,
        // shared by both mutating verbs.
        val mutating = request as? RootArgs.Mutating
        if (mutating != null && mutating.name == RootName.MAIN) {
            System.err.println(
                "root: 'main' is never CLI-managed. Its directory comes from CONTENT_DIR, or from a roots {} block you " +
                    "wrote yourself in plainbase.conf - freezing the CONTENT_DIR value one command happened to see " +
                    "would silently repoint main on every container that boots with a different one.",
            )
            return null
        }
        return request
    }

    private fun parseAdd(argv: List<String>): RootArgs? {
        val positional = mutableListOf<String>()
        var editable = false
        var force = false
        // `off|native` ONLY. This is NOT a duplicate of the boot rule that refuses `auto` on an extra: it means
        // no code path in this CLI can CONSTRUCT an AUTO extra at all. A smaller input space, not a second check.
        var history = HistoryMode.OFF
        var index = 0
        while (index < argv.size) {
            when (val token = argv[index]) {
                "--editable" -> editable = true
                "--force" -> force = true
                "--history" -> {
                    val raw = argv.getOrNull(index + 1)
                    history = when (raw?.lowercase()) {
                        "off" -> HistoryMode.OFF
                        "native" -> HistoryMode.NATIVE
                        else -> {
                            System.err.println("root add: --history accepts off|native (got '${raw.orEmpty()}')")
                            return null
                        }
                    }
                    index++
                }
                else -> {
                    if (token.startsWith("--")) {
                        System.err.println("root add: unknown flag '$token'")
                        return null
                    }
                    positional += token
                }
            }
            index++
        }
        if (positional.size != 2) return usage()
        val name = RootName.of(positional[0]) ?: run {
            System.err.println("root add: '${positional[0]}' is not a valid root name (a lowercase slug [a-z0-9][a-z0-9-]*, max 32 chars)")
            return null
        }
        // BLANK is refused HERE, before anything absolutizes it - the loader's identical guard cannot save us,
        // because by the time a path reaches roots.conf this command has already made it absolute. `Path.of("")`
        // is the process working directory, so `root add docs "$DOCS_DIR"` with DOCS_DIR unset would otherwise
        // serve and index whatever the CLI happened to be run from (under systemd, `/`) - and, with --editable,
        // hand an agent write access to it.
        val path = positional[1].takeIf { it.isNotBlank() } ?: run {
            System.err.println("root add: the path is empty - an unset shell variable expands to nothing, and the CWD is not a root")
            return usage()
        }
        return RootArgs.Add(name = name, path = path, editable = editable, history = history, force = force)
    }

    private fun parseRemove(argv: List<String>): RootArgs? {
        if (argv.size != 1) return usage()
        val name = RootName.of(argv[0]) ?: run {
            System.err.println("root remove: '${argv[0]}' is not a valid root name (a lowercase slug [a-z0-9][a-z0-9-]*, max 32 chars)")
            return null
        }
        return RootArgs.Remove(name)
    }

    private fun usage(): RootArgs? {
        System.err.println(USAGE)
        return null
    }
}

/** The parsed argv. Both mutating verbs carry a name, so the `main` refusal is ONE comparison rather than two. */
internal sealed interface RootArgs {

    sealed interface Mutating : RootArgs {
        val name: RootName
    }

    data class Add(
        override val name: RootName,
        val path: String,
        val editable: Boolean,
        val history: HistoryMode,
        val force: Boolean,
    ) : Mutating

    data class Remove(override val name: RootName) : Mutating

    data object List : RootArgs
}
