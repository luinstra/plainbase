package com.plainbase.frameworks.objectstore

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The S3 `encoding-type=url` whole-key wire decode: `%2F` -> `/` (the wire encodes separators too),
 * `%20` -> space, `+` stays literal, strict UTF-8. Decode ONLY; the safety funnel is the downstream
 * `TreePath` validation (C4), not this helper.
 */
class S3WireKeyTest : FunSpec({

    test("decodes a whole key whose separators and hostile bytes are percent-encoded (%2F -> '/')") {
        S3WireKey.decode("dir%2Funicode%20%26%24key.md") shouldBe "dir/unicode &\$key.md"
    }

    test("a literal '+' stays '+', never a space (S3 emits %20, never '+')") {
        S3WireKey.decode("a+b%20c.md") shouldBe "a+b c.md"
    }

    test("a percent-encoded multibyte UTF-8 sequence round-trips") {
        // %E3%82%AC is U+30AC (Japanese 'ga'); %2F is a separator.
        S3WireKey.decode("notes%2F%E3%82%AC.md") shouldBe "notes/ガ.md"
    }

    test("an unescaped key with no percent-escapes is returned verbatim") {
        S3WireKey.decode("plain/key.md") shouldBe "plain/key.md"
    }

    test("a malformed escape refuses with ObjectStoreException (a malformed LIST response)") {
        shouldThrow<ObjectStoreException> { S3WireKey.decode("bad%2") }
    }

    test("a lone non-UTF-8 escape refuses (strict UTF-8, never U+FFFD)") {
        shouldThrow<ObjectStoreException> { S3WireKey.decode("bad%E9key") }
    }
})
