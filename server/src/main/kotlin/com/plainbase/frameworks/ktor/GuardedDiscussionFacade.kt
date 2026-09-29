package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.discussion.Actor
import com.plainbase.domain.discussion.Anchor
import com.plainbase.domain.discussion.Author
import com.plainbase.domain.discussion.AuthorKind
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionStatus
import com.plainbase.domain.discussion.PageRef
import com.plainbase.domain.discussion.SelectionResolver
import com.plainbase.domain.discussion.SelectionResult
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.principal.DiscussionGrant
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.AbsenceUnverified
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.DiscussionAction
import com.plainbase.domain.service.DiscussionAnchorRequest
import com.plainbase.domain.service.DiscussionClaim
import com.plainbase.domain.service.DiscussionCommand
import com.plainbase.domain.service.DiscussionFacade
import com.plainbase.domain.service.DiscussionFacts
import com.plainbase.domain.service.DiscussionReads
import com.plainbase.domain.service.DiscussionRefusal
import com.plainbase.domain.service.DiscussionWriteOutcome
import com.plainbase.domain.service.DiscussionWriter
import com.plainbase.domain.service.IdResolution
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ProposalAuthorLabeler
import com.plainbase.domain.service.RootUnavailable
import com.plainbase.domain.service.RootedResource
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.protocol.DiscussionDetailDto
import com.plainbase.frameworks.protocol.DiscussionListDto
import com.plainbase.frameworks.protocol.DiscussionPreviewDto

class GuardedDiscussionFacade(
    private val policy: PolicyService,
    private val writer: DiscussionWriter,
    private val reads: DiscussionReads,
    private val registry: RootRegistry,
    private val availability: RootAvailability,
    private val resolver: PageRootResolver,
    private val absence: AbsenceClassifier,
    private val indexBuilder: IndexBuilder,
    private val stores: (RootName) -> ContentStore,
    private val labeler: ProposalAuthorLabeler,
    private val citations: CitationFactory = CitationFactory(),
    private val projection: DiscussionReadProjection? = null,
) : DiscussionFacade {
    fun pageList(principal: Principal, pageId: PageId, pin: RootName?, after: DiscussionId?, limit: Int): DiscussionListDto {
        val snapshot = indexBuilder.current
        val resolution = pin?.let { resolver.resolvePinned(it, pageId) } ?: resolver.resolve(pageId)
        val root = (resolution as? IdResolution.One)?.root
        val topology = policy.checkDiscussionRead(principal, RootedResource(root, "${pageId.value}/discussions"))
        if (pin != null && registry.byName(pin) == null) throw DiscussionReadRefused(400, "invalid_root")
        val owner = when (resolution) {
            is IdResolution.One -> resolution.root
            is IdResolution.Ambiguous -> throw DiscussionReadRefused(409, "ambiguous_page_id")
            IdResolution.None -> throw DiscussionReadRefused(404, "page_not_found")
        }
        if (topology != null) return DiscussionListDto(emptyList(), null, false, unavailableReason(owner))
        val page = page(snapshot, owner, pageId) ?: throw DiscussionReadRefused(404, "page_not_found")
        return checkNotNull(projection).pageList(owner, page, snapshot, after, limit)
    }

    fun rootList(principal: Principal, root: RootName, after: DiscussionId?, limit: Int, state: String?): DiscussionListDto {
        val snapshot = indexBuilder.current
        val topology = policy.checkDiscussionRead(principal, RootedResource(root, "discussions"))
        if (registry.byName(root) == null) throw DiscussionReadRefused(400, "invalid_root")
        if (topology != null) return DiscussionListDto(emptyList(), null, false, unavailableReason(root))
        return checkNotNull(projection).rootList(root, snapshot, after, limit, state)
    }

    fun detail(principal: Principal, id: DiscussionId, pin: RootName?, after: CommentId?, limit: Int): DiscussionDetailDto {
        val snapshot = indexBuilder.current
        if (pin != null && registry.byName(pin) != null) {
            val topology = policy.checkDiscussionRead(principal, RootedResource(pin, "discussion/${id.value}"))
            if (topology != null) return DiscussionDetailDto(null, emptyList(), null, false, unavailableReason(pin))
        }
        val target = target(id, pin, null)
        policy.checkDiscussionRead(principal, RootedResource(target.root, "discussion/${id.value}"))
        target.unknownRoots.firstOrNull { !availability.current().isAvailable(it) }?.let(::requireAvailable)
        target.refusal?.let { throw DiscussionReadRefused(it.status, it.code) }
        return checkNotNull(projection).detail(
            target.root ?: throw DiscussionReadRefused(503, "content_unreadable"), id, snapshot, after, limit,
        )
    }

    fun preview(
        principal: Principal,
        pageId: PageId,
        pin: RootName?,
        request: DiscussionAnchorRequest.Quote,
    ): DiscussionPreviewDto {
        val snapshot = indexBuilder.current
        val resolution = pin?.let { resolver.resolvePinned(it, pageId) } ?: resolver.resolve(pageId)
        val owner = (resolution as? IdResolution.One)?.root
        policy.checkDiscussionRead(principal, RootedResource(owner, "${pageId.value}/discussions"), preview = true)
        val root = previewRoot(resolution, pin)
        val (page, bytes) = previewPageBytes(root, pageId, snapshot)
        val answer = when (val resolved = resolveAnchor(page, bytes, request, snapshot)) {
            is AnchorResolution.Resolved -> resolved.anchor as Anchor.Quote
            is AnchorResolution.Refused -> throw DiscussionReadRefused(resolved.refusal.status, resolved.refusal.code)
        }
        return DiscussionPreviewDto(
            answer.contentHash, answer.capture.byteStart, answer.capture.byteEnd,
            answer.capture.selection.wire, answer.capture.quote,
        )
    }

    private fun previewRoot(resolution: IdResolution, pin: RootName?): RootName {
        if (pin != null && registry.byName(pin) == null) throw DiscussionReadRefused(400, "invalid_root")
        return when (resolution) {
            is IdResolution.One -> resolution.root
            is IdResolution.Ambiguous -> throw DiscussionReadRefused(409, "ambiguous_page_id")
            IdResolution.None -> throw DiscussionReadRefused(404, "page_not_found")
        }
    }

    private fun previewPageBytes(root: RootName, pageId: PageId, snapshot: PageIndex): Pair<IndexedPage, ByteArray> {
        val page = page(snapshot, root, pageId) ?: throw DiscussionReadRefused(404, "page_not_found")
        val bytes = when (val read = readPage(page)) {
            is ContentRead.Bytes -> read.bytes
            ContentRead.ConfirmedAbsent -> throw DiscussionReadRefused(404, "page_not_found")
            ContentRead.AbsenceUnknown -> throw AbsenceUnverified(root, pageId.value)
            ContentRead.RootDown -> throw RootUnavailable(root, UnavailableCause.VANISHED)
        }
        return page to bytes
    }

    private fun unavailableReason(root: RootName): String =
        if (registry.byName(root)?.editable == false) "read_only_root" else "object_storage"

    override fun start(
        principal: Principal,
        pageId: PageId,
        root: RootName?,
        anchor: DiscussionAnchorRequest,
        body: String,
    ): DiscussionWriteOutcome {
        val snapshot = indexBuilder.current
        val resolution = root?.let { resolver.resolvePinned(it, pageId) } ?: resolver.resolve(pageId)
        val chosen = (resolution as? IdResolution.One)?.root
        val resource = RootedResource(chosen, "${pageId.value}/discussions")
        val grant = policy.checkDiscussion(principal, DiscussionAction.START, DiscussionFacts.Missing, resource)
        chosen?.let(::requireAvailable)
        if (root != null && registry.byName(root) == null) return refused(400, "invalid_root")
        val owner = when (resolution) {
            is IdResolution.One -> resolution.root
            is IdResolution.Ambiguous -> return refused(409, "ambiguous_page_id")
            IdResolution.None -> return refused(404, "page_not_found")
        }
        val page = page(snapshot, owner, pageId) ?: return refused(404, "page_not_found")
        val bytes = when (val read = readPage(page)) {
            is ContentRead.Bytes -> read.bytes
            ContentRead.ConfirmedAbsent -> return refused(404, "page_not_found")
            ContentRead.AbsenceUnknown -> throw AbsenceUnverified(owner, pageId.value)
            ContentRead.RootDown -> throw RootUnavailable(owner, UnavailableCause.VANISHED)
        }
        val resolved = when (val answer = resolveAnchor(page, bytes, anchor, snapshot)) {
            is AnchorResolution.Resolved -> answer.anchor
            is AnchorResolution.Refused -> return DiscussionWriteOutcome.Refused(answer.refusal)
        }
        return writer.write(grant, DiscussionCommand.Start(owner, author(principal), PageRef(pageId, page.path), resolved, body))
    }

    override fun comment(principal: Principal, id: DiscussionId, root: RootName?, body: String): DiscussionWriteOutcome =
        mutate(principal, id, root, DiscussionAction.COMMENT, "discussion/${id.value}/comment") { owner, actor ->
            DiscussionCommand.AddComment(owner, actor, id, body)
        }

    override fun edit(
        principal: Principal,
        id: DiscussionId,
        root: RootName?,
        commentId: CommentId,
        body: String,
    ): DiscussionWriteOutcome =
        mutate(principal, id, root, DiscussionAction.EDIT, "discussion/${id.value}/comment/${commentId.value}", commentId) { owner, actor ->
            DiscussionCommand.EditComment(owner, actor, id, commentId, body)
        }

    override fun retract(principal: Principal, id: DiscussionId, root: RootName?, commentId: CommentId): DiscussionWriteOutcome =
        mutate(principal, id, root, DiscussionAction.RETRACT, "discussion/${id.value}/comment/${commentId.value}", commentId) {
                owner,
            actor,
            ->
            DiscussionCommand.RetractComment(owner, actor, id, commentId)
        }

    override fun resolve(principal: Principal, id: DiscussionId, root: RootName?): DiscussionWriteOutcome =
        mutate(principal, id, root, DiscussionAction.RESOLVE, "discussion/${id.value}") { owner, actor ->
            DiscussionCommand.SetStatus(owner, actor, id, DiscussionStatus.RESOLVED)
        }

    override fun reopen(principal: Principal, id: DiscussionId, root: RootName?): DiscussionWriteOutcome =
        mutate(principal, id, root, DiscussionAction.REOPEN, "discussion/${id.value}") { owner, actor ->
            DiscussionCommand.SetStatus(owner, actor, id, DiscussionStatus.OPEN)
        }

    override fun reattach(
        principal: Principal,
        id: DiscussionId,
        root: RootName?,
        anchor: DiscussionAnchorRequest.Quote,
    ): DiscussionWriteOutcome {
        val target = target(id, root, null)
        val grant = policy.checkDiscussion(
            principal, DiscussionAction.REATTACH, target.facts, RootedResource(target.root, "discussion/${id.value}"),
        )
        target.root?.let(::requireAvailable)
        target.unknownRoots.firstOrNull { !availability.current().isAvailable(it) }?.let(::requireAvailable)
        target.refusal?.let { return DiscussionWriteOutcome.Refused(it) }
        val owner = target.root ?: return refused(503, "content_unreadable")
        factsRefusal(DiscussionAction.REATTACH, target.facts, grant)?.let { return it }
        val pageId = (target.facts as? DiscussionFacts.Known)?.pageId ?: return refused(503, "content_unreadable")
        val snapshot = indexBuilder.current
        val page = page(snapshot, owner, pageId) ?: return refused(404, "page_not_found")
        val bytes = when (val read = readPage(page)) {
            is ContentRead.Bytes -> read.bytes
            ContentRead.ConfirmedAbsent -> return refused(404, "page_not_found")
            ContentRead.AbsenceUnknown -> throw AbsenceUnverified(owner, pageId.value)
            ContentRead.RootDown -> throw RootUnavailable(owner, UnavailableCause.VANISHED)
        }
        val resolved = when (val answer = resolveAnchor(page, bytes, anchor, snapshot)) {
            is AnchorResolution.Resolved -> answer.anchor as Anchor.Quote
            is AnchorResolution.Refused -> return DiscussionWriteOutcome.Refused(answer.refusal)
        }
        return writer.write(grant, DiscussionCommand.Reattach(owner, author(principal), id, resolved))
    }

    override fun purge(principal: Principal, id: DiscussionId, root: RootName?, commentId: CommentId): DiscussionWriteOutcome =
        mutate(principal, id, root, DiscussionAction.PURGE, "discussion/${id.value}/comment/${commentId.value}", commentId) {
                owner,
            actor,
            ->
            DiscussionCommand.PurgeComment(owner, actor, id, commentId)
        }

    private fun mutate(
        principal: Principal,
        id: DiscussionId,
        pin: RootName?,
        operation: DiscussionAction,
        resource: String,
        commentId: CommentId? = null,
        command: (RootName, Author) -> DiscussionCommand,
    ): DiscussionWriteOutcome {
        val target = target(id, pin, commentId)
        val grant = policy.checkDiscussion(principal, operation, target.facts, RootedResource(target.root, resource))
        target.root?.let(::requireAvailable)
        target.unknownRoots.firstOrNull { !availability.current().isAvailable(it) }?.let(::requireAvailable)
        target.refusal?.let { return DiscussionWriteOutcome.Refused(it) }
        val owner = target.root ?: return refused(503, "content_unreadable")
        if (operation != DiscussionAction.PURGE) factsRefusal(operation, target.facts, grant)?.let { return it }
        val next = command(owner, author(principal))
        return writer.write(grant, next)
    }

    private fun target(id: DiscussionId, pin: RootName?, commentId: CommentId?): Target {
        if (pin != null) {
            val declared = registry.byName(pin) ?: return Target(null, DiscussionFacts.Unknown, DiscussionRefusal(400, "invalid_root"))
            if (!declared.editable || declared.backend !is RootBackend.Local) {
                return Target(pin, DiscussionFacts.Unknown, null)
            }
            return when (val claim = reads.claim(pin, id, commentId)) {
                is DiscussionClaim.Present -> Target(pin, claim.facts, null)
                DiscussionClaim.Absent -> Target(null, DiscussionFacts.Missing, DiscussionRefusal(404, "discussion_not_found"))
                DiscussionClaim.Unknown -> Target(null, DiscussionFacts.Unknown, DiscussionRefusal(503, "content_unreadable"), listOf(pin))
            }
        }
        val claims = registry.roots.filter { it.editable && it.backend is RootBackend.Local }
            .map { it.name to reads.claim(it.name, id, commentId) }
        val present = claims.filter { it.second is DiscussionClaim.Present }
        val unknown = claims.filter { it.second == DiscussionClaim.Unknown }.map { it.first }
        if (unknown.isNotEmpty()) {
            return Target(null, DiscussionFacts.Unknown, DiscussionRefusal(503, "content_unreadable"), unknown)
        }
        if (present.size > 1) return Target(null, DiscussionFacts.Unknown, DiscussionRefusal(409, "ambiguous_discussion_id"))
        if (present.isEmpty()) return Target(null, DiscussionFacts.Missing, DiscussionRefusal(404, "discussion_not_found"))
        val (root, claim) = present.single()
        return Target(root, (claim as DiscussionClaim.Present).facts, null)
    }

    private fun factsRefusal(operation: DiscussionAction, facts: DiscussionFacts, grant: DiscussionGrant): DiscussionWriteOutcome.Refused? =
        when (facts) {
            DiscussionFacts.Missing -> refused(404, "discussion_not_found")
            DiscussionFacts.Unknown -> refused(503, "content_unreadable")
            is DiscussionFacts.Known -> when (facts.state) {
                "unreadable" -> refused(409, "discussion_unreadable")
                "incomplete" -> refused(404, "discussion_not_found")
                "ok" -> if (grant.ownershipRequired && grant.reliedOn.author == null && grant.reliedOn.starter == null) {
                    if (operation == DiscussionAction.EDIT || operation == DiscussionAction.RETRACT) {
                        refused(404, "comment_not_found")
                    } else {
                        refused(503, "content_unreadable")
                    }
                } else {
                    null
                }
                else -> refused(503, "content_unreadable")
            }
        }

    private fun page(snapshot: PageIndex, root: RootName, id: PageId): IndexedPage? {
        absence.requireVerifiedAbsence(root, id, snapshot)
        return snapshot.pageAt(RootedPageId(root, id))
    }

    private fun readPage(page: IndexedPage): ContentRead = absence.read(stores(page.root), RootedPath(page.root, page.path))

    private fun resolveAnchor(
        page: IndexedPage,
        bytes: ByteArray,
        request: DiscussionAnchorRequest,
        snapshot: PageIndex,
    ): AnchorResolution {
        if (citations.contentHash(bytes) != request.contentHash) {
            return AnchorResolution.Refused(DiscussionRefusal(409, "page_changed"))
        }
        val commit = page.commit.takeIf { page.contentHash == request.contentHash }
        return when (request) {
            is DiscussionAnchorRequest.Page -> AnchorResolution.Resolved(Anchor.Page(request.contentHash, commit))
            is DiscussionAnchorRequest.Quote -> {
                val rendered = FlexmarkRenderer(snapshot.view(page.root)).render(page.path, bytes)
                when (val selected = SelectionResolver.resolve(bytes, rendered.blocks, rendered.headings, request.selection)) {
                    is SelectionResult.Resolved ->
                        AnchorResolution.Resolved(Anchor.Quote(request.contentHash, commit, selected.capture))
                    is SelectionResult.Refused -> AnchorResolution.Refused(DiscussionRefusal(422, selected.refusal.code))
                }
            }
        }
    }

    private fun author(principal: Principal): Author {
        val label = labeler.resolve(principal)
        val kind = when (principal) {
            is Principal.Human -> AuthorKind.HUMAN
            is Principal.Agent -> AuthorKind.AGENT
            Principal.Anonymous -> AuthorKind.ANONYMOUS
        }
        return Author(Actor(SubjectKey.of(principal), label.label), kind)
    }

    private fun requireAvailable(root: RootName) {
        val unavailable = availability.current().unavailable[root] ?: return
        throw RootUnavailable(root, unavailable.cause)
    }

    private data class Target(
        val root: RootName?,
        val facts: DiscussionFacts,
        val refusal: DiscussionRefusal?,
        val unknownRoots: List<RootName> = emptyList(),
    )

    private sealed interface AnchorResolution {
        data class Resolved(val anchor: Anchor) : AnchorResolution
        data class Refused(val refusal: DiscussionRefusal) : AnchorResolution
    }

    private fun refused(status: Int, code: String) = DiscussionWriteOutcome.Refused(DiscussionRefusal(status, code))
}
