package com.plainbase.domain.root

import com.plainbase.domain.page.PageId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll

/** The root-name grammar cannot overlap the page-id grammar used first by the shared permalink dispatcher. */
class RootNameIdOverlapTest : FunSpec({

    test("the deterministic 32-hex overlap witness is rejected") {
        RootName.of("a".repeat(32)).shouldBeNull()
    }

    // Two independent rules reject a digit-leading hex string (the shape rule and the page-id guard) and only
    // ONE rejects a letter-leading one, so the deterministic witness above is what keeps the guard honest.
    test("every generated 32-hex page-id shape is rejected as a root name") {
        checkAll(Arb.stringPattern("[0-9a-f]{32}")) { hex32 ->
            RootName.of(hex32).shouldBeNull()
        }
    }

    test("every accepted root name is rejected by the page-id parser") {
        checkAll(Arb.string(0..40)) { raw ->
            if (RootName.of(raw) != null) PageId.of(raw).shouldBeNull()
        }
    }
})
