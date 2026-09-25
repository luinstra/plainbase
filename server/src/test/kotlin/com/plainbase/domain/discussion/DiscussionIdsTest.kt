@file:OptIn(ExperimentalUuidApi::class)

package com.plainbase.domain.discussion

import com.plainbase.domain.service.DiscussionIdProvider
import com.plainbase.domain.service.UuidV7DiscussionIdProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class DiscussionIdsTest : FunSpec({
    val text = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"

    test("a canonical v7 id is accepted by both factories") {
        val discussion = DiscussionId.of(text).shouldNotBeNull()
        val comment = CommentId.of(text).shouldNotBeNull()
        DiscussionId.of(Uuid.parse(text)).value shouldBe text
        CommentId.of(Uuid.parse(text)).value shouldBe text
        discussion.value shouldBe text
        comment.value shouldBe text
    }

    test("a parsed uppercase v7 is accepted and normalized") {
        val parsed = Uuid.parse(text.uppercase())
        DiscussionId.of(parsed).value shouldBe text
        CommentId.of(parsed).value shouldBe text
    }

    test("non canonical text is rejected") {
        DiscussionId.of(text.uppercase()).shouldBeNull()
        CommentId.of(text.uppercase()).shouldBeNull()
        DiscussionId.of(text.replace("-", "")).shouldBeNull()
        CommentId.of(text.replace("-", "")).shouldBeNull()
    }

    test("wrong version or variant is rejected by both factories") {
        val invalid = listOf(
            "0197a3f2-8c4d-4e91-b3a2-4f8e9d1c6b5a",
            "00000000-0000-7000-0000-000000000000",
            "0197a3f2-8c4d-7e91-c3a2-4f8e9d1c6b5a",
        )
        invalid.forEach { value ->
            DiscussionId.of(value).shouldBeNull()
            CommentId.of(value).shouldBeNull()
            shouldThrow<IllegalArgumentException> { DiscussionId.of(Uuid.parse(value)) }
            shouldThrow<IllegalArgumentException> { CommentId.of(Uuid.parse(value)) }
        }
    }

    test("discussion and comment ids are distinct types") {
        val discussionId: DiscussionId = DiscussionId.require(text)
        val commentId: CommentId = CommentId.require(text)
        discussionId.value shouldBe commentId.value
        (discussionId as Any) shouldNotBe commentId
    }

    test("the provider mints distinct valid ids") {
        val provider: DiscussionIdProvider = UuidV7DiscussionIdProvider()
        val discussions = List(1_000) { provider.nextDiscussion() }
        val comments = List(1_000) { provider.nextComment() }
        discussions.map { it.value }.toSet() shouldHaveSize 1_000
        comments.map { it.value }.toSet() shouldHaveSize 1_000
        discussions.forEach { DiscussionId.of(it.value).shouldNotBeNull() }
        comments.forEach { CommentId.of(it.value).shouldNotBeNull() }
    }
})
