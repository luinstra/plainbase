package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.ktor.routes.CONTENT_SECURITY_POLICY
import com.plainbase.frameworks.ktor.routes.X_CONTENT_TYPE_OPTIONS
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.response.header
import java.security.MessageDigest
import java.util.Base64

/*
 * The SPA shell's Content-Security-Policy (C1a items 3-7). Stamped on every text/html response — the shell
 * is served by TWO paths (staticResources("/", "static") and respondSpaShell), so a single content-type-
 * gated createApplicationPlugin choke point covers both and is the single source of the CSP string.
 *
 * script-src is HASH-pinned (never 'unsafe-inline', which would defeat the XSS goal): the shell's one inline
 * bootstrap <script> is hashed FROM the served bytes at startup (inlineScriptHash) — Vite can whitespace-
 * rewrite the block, so a hardcoded constant drifts (test build != runtime serve). The extractor fails
 * CLOSED (throws at startup) on anything but exactly one inline executable script.
 *
 * style-src 'self' 'unsafe-inline' is REQUIRED: @codemirror/view injects runtime styles via style-mod from
 * EditorView.theme(...), which would white-screen under a bare default-src 'self'. The directive list is the
 * STARTING point; the Playwright real-CSP smoke (frontend/e2e/csp.spec.ts) is the completeness gate — widen
 * the SPECIFIC directive on a violation, never loosen to 'unsafe-*'.
 *
 * script-src 'self' trusts EVERY same-origin URL — including a writer-uploaded `/assets/<name>.js` content
 * asset. The per-asset `sandbox` CSP that AssetRoute stamps on such an upload governs that asset's OWN document
 * navigation, NOT a `<script src>` SUBRESOURCE fetch by the shell, so it is inert against this class. Executing
 * an uploaded script still requires an independent HTML/DOM-injection primitive to plant the tag, which the
 * markdown pipeline closes (escapeHtml=true + the PB-LINK-1 scheme allowlist). The defense against uploaded-JS
 * execution is therefore that sanitizer — do NOT mistake the per-asset sandbox header for a subresource control.
 */
private val logger = KotlinLogging.logger {}

/**
 * Installs the shell-CSP plugin on this [Application], built from the inline-script hash of the embedded
 * `static/index.html`. SKIPPED (with a warning) when no frontend is bundled — a no-frontend build has no
 * shell to protect and `respondSpaShell` already 404s. Never fires in the release binary or tests (both
 * embed `static/`). Call BEFORE `routing { … }`.
 */
internal fun Application.installShellSecurityHeaders() {
    val shellHtml = bundledShellHtml()
    if (shellHtml == null) {
        logger.warn { "SPA shell (static/index.html) is not bundled; skipping the shell Content-Security-Policy plugin" }
        return
    }
    install(shellSecurityHeadersPlugin(buildShellCsp(inlineScriptHash(shellHtml))))
}

/**
 * The text/html-gated CSP stamp. Runs for EVERY response; stamps the shell headers ONLY when the outgoing
 * [OutgoingContent.contentType] is `text/html` (the reliable signal — `call.response.headers[CT]` is set
 * later in the send pipeline) AND no CSP is already set. The per-asset route sets its own sandbox CSP
 * before `respondBytes`, so the skip-if-set guard leaves it untouched.
 *
 * No path exclusion (B4): an `/api/` path is naturally `application/json` (never text/html) and an `/assets/` one is
 * js/css/octet-stream (+ its own sandbox CSP when scriptable) — both skip the gate — so a prefix exclusion
 * (`startsWith("/api"|"/assets")`) would only mis-handle a shell path like `/apiary` or `/assetsx` (served
 * the shell HTML by `staticResources`), which now CORRECTLY receives the CSP. The safe-`as?` is fail-SAFE:
 * a non-`OutgoingContent`/typeless message yields null → no stamp (the cold-200 shell tests catch a
 * regression that ever dropped it from the shell). A 304 carries no text/html body, so it skips naturally;
 * the browser reuses the stored 200's CSP (RFC 7232 §4.1).
 */
internal fun shellSecurityHeadersPlugin(shellCsp: String) =
    createApplicationPlugin("ShellSecurityHeaders") {
        onCallRespond { call, message ->
            if (call.response.headers[CONTENT_SECURITY_POLICY] != null) return@onCallRespond
            val contentType = (message as? OutgoingContent)?.contentType
            if (contentType?.withoutParameters() == ContentType.Text.Html) {
                call.response.header(CONTENT_SECURITY_POLICY, shellCsp)
                call.response.header("Referrer-Policy", "strict-origin-when-cross-origin")
                call.response.header(X_CONTENT_TYPE_OPTIONS, "nosniff")
            }
        }
    }

/**
 * The full shell CSP (item 6 directive completeness; item 7 `frame-ancestors 'self'` is the single
 * clickjacking source of truth — NO `X-Frame-Options`, whose `DENY` would contradict same-origin framing).
 * `img-src … https:` is REQUIRED (markdown bodies render external `<img src="https://…">`); `data:` covers
 * Vite-inlined CSS assets; bare `http:` is deliberately omitted (mixed-content-blocked on https anyway).
 */
internal fun buildShellCsp(scriptHash: String): String = listOf(
    "default-src 'self'",
    "script-src 'self' '$scriptHash'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: https:",
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "frame-ancestors 'self'",
).joinToString("; ")

/**
 * Computes `sha256-<base64>` over the EXACT bytes of the shell's single inline executable `<script>` body
 * (everything between the open tag's `>` and `</script>`, verbatim — the browser hashes the same). Matches
 * all `<script>` elements, keeps only INLINE EXECUTABLE ones (no `src=`; type absent/`module`/
 * `text/javascript`, so the module `src=` script and any `application/json`/importmap block are skipped),
 * and FAILS CLOSED if the count is not exactly 1 — a future Vite inline must break the build loudly, never
 * silently add a script the single-hash CSP would then block. JDK stdlib only (native-safe).
 */
internal fun inlineScriptHash(shellHtml: String): String {
    val inlineBodies = SCRIPT_TAG.findAll(shellHtml)
        .filter { isInlineExecutable(it.groups["attrs"]!!.value) }
        .map { it.groups["body"]!!.value }
        .toList()
    require(inlineBodies.size == 1) {
        "Expected exactly one inline executable <script> in the SPA shell to hash-pin, found ${inlineBodies.size} " +
            "— the single-hash CSP gate fails closed rather than ship a wrong/incomplete script-src"
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(inlineBodies.single().toByteArray(Charsets.UTF_8))
    return "sha256-" + Base64.getEncoder().encodeToString(digest)
}

private val SCRIPT_TAG = Regex("<script(?<attrs>[^>]*)>(?<body>.*?)</script>", RegexOption.DOT_MATCHES_ALL)

// (?:^|\s) not \b — a word boundary also fires inside hyphenated attrs (data-src=, data-type=); anchor to
// start-or-whitespace so only a real standalone src=/type= attribute matches. Non-capturing so the TYPE_ATTR
// value stays group 1.
private val SRC_ATTR = Regex("""(?:^|\s)src\s*=""", RegexOption.IGNORE_CASE)
private val TYPE_ATTR = Regex("""(?:^|\s)type\s*=\s*["']?([^"'\s>]*)""", RegexOption.IGNORE_CASE)

/**
 * An inline executable script: no `src=`, and a `type` that is absent OR empty/whitespace-only (both classic
 * executable per the HTML spec) OR `module` OR a JS MIME alias. Anything else (`importmap`, `application/json`,
 * `speculationrules`, …) is a data block, not executable, and is skipped.
 */
private fun isInlineExecutable(attrs: String): Boolean {
    if (SRC_ATTR.containsMatchIn(attrs)) return false
    val type = TYPE_ATTR.find(attrs)?.groupValues?.get(1)?.trim()?.lowercase() ?: return true
    return type.isEmpty() || type == "module" || type in JS_MIME_TYPES
}

private val JS_MIME_TYPES = setOf("text/javascript", "application/javascript", "text/ecmascript", "application/ecmascript")

/** The embedded SPA shell HTML (`static/index.html`), or null in a no-frontend build. */
internal fun bundledShellHtml(): String? =
    ShellResource::class.java.classLoader.getResourceAsStream("static/index.html")?.use { it.readBytes().toString(Charsets.UTF_8) }

private object ShellResource
