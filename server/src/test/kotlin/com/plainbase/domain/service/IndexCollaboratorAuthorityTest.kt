package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.FrontmatterParser
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.render.MarkdownRenderer
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.repository.Supersession
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootedPath
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import com.plainbase.domain.root.ObservationId as Oid

/**
 * A checked-in source/reflection fence around the narrow source reader seam. The expected inventories are deliberate
 * literals: this test must report an unreviewed declaration or path instead of learning it from the tree it audits.
 */
class IndexCollaboratorAuthorityTest : FunSpec({
    val serviceRoot = mainKotlinRoot().resolve("com/plainbase/domain/service")
    val productionRoot = mainKotlinRoot()
    val serviceFiles = kotlinFiles(serviceRoot)

    test("lexical scanner characterization") {
        val raw = "\"\"\""
        val source = listOf(
            "// class FakeLine",
            "/* class FakeBlock /* class FakeNested */ */",
            "class `Real Name`<T> {",
            "    val escaped = \"class FakeString \\\"${'$'}{className}\"",
            "    val character = '}'",
            "    val raw = $raw class FakeRaw ${'$'}{className} $raw",
            "    companion object",
            "    object : Runnable { override fun run() = Unit }",
            "    class Inner<T> { val value = T::class }",
            "}",
            "fun topLevel(value: String) = value",
            "val topProperty = \"not a declaration\"",
            "typealias Alias = RealName",
            "class Repeated { class Same }",
            "class Another { class Same }",
        ).joinToString("\n")
        val result = KotlinSourceScanner.scan("snippet.kt", source)
        withClue("scanner tokens: ${result.tokens.joinToString { it.text }}") {
            result.declarations.map { it.qualified } shouldBe listOf(
                "Real Name",
                "Real Name.Companion",
                "Real Name.Inner",
                "Repeated",
                "Repeated.Same",
                "Another",
                "Another.Same",
            )
        }
        result.topLevelRecords shouldBe setOf(
            "snippet.kt|fun topLevel",
            "snippet.kt|val topProperty",
            "snippet.kt|typealias Alias",
        )
        result.tokens.map { it.text }.count { it == "FakeLine" || it == "FakeBlock" || it == "FakeRaw" } shouldBe 0
        result.tokens.map { it.text }.contains("className") shouldBe true
    }

    test("service-path inventory") {
        val actual = serviceFiles.map { serviceRoot.relativize(it).toString() }
        withClue("service path inventory drift") {
            actual.toSet() shouldBe EXPECTED_SERVICE_PATHS
            actual.groupingBy { it }.eachCount() shouldBe EXPECTED_SERVICE_PATHS.groupingBy { it }.eachCount()
        }
    }

    test("service-declaration inventory") {
        val sourceIndex = KotlinSourceIndex(
            serviceFiles.map { path ->
                KotlinSourceUnit(serviceRoot.relativize(path).toString(), Files.readString(path))
            },
        )
        val actual = sourceIndex.discoveredDeclarations()
        val expected = EXPECTED_SERVICE_DECLARATIONS.toList()
        val missing = expected.toSet() - actual.toSet()
        val extra = actual.toSet() - expected.toSet()
        withClue("qualified service declarations drift; missing=$missing; extra=$extra") {
            actual.toSet() shouldBe expected.toSet()
            actual.groupingBy { it }.eachCount() shouldBe expected.groupingBy { it }.eachCount()
        }
    }

    test("service non-class declaration inventory") {
        val sourceIndex = KotlinSourceIndex(
            serviceFiles.map { path ->
                KotlinSourceUnit(serviceRoot.relativize(path).toString(), Files.readString(path))
            },
        )
        val actual = sourceIndex.discoveredNonClassDeclarations()
        val expected = EXPECTED_SERVICE_NON_CLASS_DECLARATIONS.toList()
        val missing = expected.toSet() - actual.toSet()
        val extra = actual.toSet() - expected.toSet()
        withClue("top-level functions, properties and aliases drift; missing=$missing; extra=$extra") {
            actual.toSet() shouldBe expected.toSet()
            actual.groupingBy { it }.eachCount() shouldBe expected.groupingBy { it }.eachCount()
        }
    }

    test("source-index semantic characterization") {
        val indexingDefinitions = KotlinSourceUnit(
            "com/plainbase/domain/service/IndexingDefinitions.kt",
            "package com.plainbase.domain.service\nclass Draft\nclass SourceScan\nclass Identity",
        )
        val foreignSource = KotlinSourceUnit(
            "com/foreign/Foreign.kt",
            "package com.foreign\nclass SourceScan",
        )
        val foreignConsumer = KotlinSourceUnit(
            "com/other/ForeignConsumer.kt",
            "package com.other\nimport com.foreign.SourceScan\nfun consume(value: SourceScan) = value",
        )
        withClue("foreign explicit import must resolve to its defining declaration") {
            indexingReferences(foreignConsumer, listOf(foreignSource, foreignConsumer)) shouldBe emptyList()
        }

        val genericShadow = KotlinSourceUnit(
            "com/other/GenericShadow.kt",
            "package com.other\nimport com.plainbase.domain.service.SourceScan\n" +
                "class Box<SourceScan>\nfun use(value: SourceScan) = value",
        )
        val followingFunctionOffset = genericShadow.source.lastIndexOf("SourceScan")
        withClue("class type-parameter symbols must shadow only their own declaration") {
            indexingReferences(genericShadow, listOf(indexingDefinitions, genericShadow)) shouldBe listOf(
                "com/other/GenericShadow.kt:${genericShadow.indexed.lineOf(followingFunctionOffset)}:" +
                    "$followingFunctionOffset:SourceScan -> com.plainbase.domain.service.SourceScan",
            )
        }

        val valueTypeNamespace = KotlinSourceUnit(
            "com/other/ValueTypeNamespace.kt",
            "package com.other\nimport com.plainbase.domain.service.SourceScan\n" +
                "fun check() { val SourceScan = 1; val typed: SourceScan = SourceScan }",
        )
        val typeOffset = valueTypeNamespace.source.indexOf("SourceScan", valueTypeNamespace.source.indexOf("typed"))
        withClue("value and type namespaces must resolve independently") {
            indexingReferences(valueTypeNamespace, listOf(indexingDefinitions, valueTypeNamespace)) shouldBe listOf(
                "com/other/ValueTypeNamespace.kt:${valueTypeNamespace.indexed.lineOf(typeOffset)}:" +
                    "$typeOffset:SourceScan -> com.plainbase.domain.service.SourceScan",
            )
        }

        val localTypeScope = KotlinSourceUnit(
            "com/other/LocalTypeScope.kt",
            "package com.other\nimport com.plainbase.domain.service.Draft\n" +
                "fun first() { class Draft; fun nested(value: Draft) = value }\n" +
                "fun second(value: Draft) = value",
        )
        val secondOffset = localTypeScope.source.lastIndexOf("Draft")
        withClue("a local class must not shadow a later sibling function") {
            indexingReferences(localTypeScope, listOf(indexingDefinitions, localTypeScope)) shouldBe listOf(
                "com/other/LocalTypeScope.kt:${localTypeScope.indexed.lineOf(secondOffset)}:" +
                    "$secondOffset:Draft -> com.plainbase.domain.service.Draft",
            )
        }
    }

    test("source-index exact independent fixtures") {
        fun finding(unit: KotlinSourceUnit, index: Int, spelling: String, target: String): String =
            "${unit.path}:${unit.indexed.lineOf(unit.tokens[index].offset)}:${unit.tokens[index].offset}:$spelling -> $target"

        val indexingTarget = "com.plainbase.domain.service.SourceScan"
        val indexingDefinitions = KotlinSourceUnit(
            "com/plainbase/domain/service/IndexingDefinitions.kt",
            "package com.plainbase.domain.service\nclass Draft\nclass SourceScan\nclass Identity",
        )
        val samePackage = KotlinSourceUnit(
            "com/other/SamePackage.kt",
            "package com.plainbase.domain.service\nfun consume(value: SourceScan) = value",
        )
        val samePackageIndex = KotlinSourceIndex(listOf(indexingDefinitions, samePackage))
        val samePackageOffset = samePackage.source.lastIndexOf("SourceScan")
        val samePackageToken = samePackage.tokens.indexOfLast { it.offset == samePackageOffset }
        samePackageIndex.resolveReference(samePackage, samePackageToken).identity shouldBe indexingTarget
        samePackageIndex.indexingReferences(samePackage) shouldBe listOf(
            finding(samePackage, samePackageToken, "SourceScan", indexingTarget),
        )

        val explicitAlias = KotlinSourceUnit(
            "com/other/ExplicitAlias.kt",
            "package com.other\nimport com.plainbase.domain.service.SourceScan as Scan\nfun consume(value: Scan) = value",
        )
        val explicitAliasIndex = KotlinSourceIndex(listOf(indexingDefinitions, explicitAlias))
        val explicitAliasOffset = explicitAlias.source.lastIndexOf("Scan")
        val explicitAliasToken = explicitAlias.tokens.indexOfLast { it.offset == explicitAliasOffset }
        explicitAliasIndex.resolveReference(explicitAlias, explicitAliasToken).identity shouldBe indexingTarget
        explicitAliasIndex.indexingReferences(explicitAlias) shouldBe listOf(
            finding(explicitAlias, explicitAliasToken, "Scan", indexingTarget),
        )

        val starImport = KotlinSourceUnit(
            "com/other/StarImport.kt",
            "package com.other\nimport com.plainbase.domain.service.*\nfun consume(value: SourceScan) = value",
        )
        val starImportIndex = KotlinSourceIndex(listOf(indexingDefinitions, starImport))
        val starImportOffset = starImport.source.lastIndexOf("SourceScan")
        val starImportToken = starImport.tokens.indexOfLast { it.offset == starImportOffset }
        starImportIndex.resolveReference(starImport, starImportToken).identity shouldBe indexingTarget
        starImportIndex.indexingReferences(starImport) shouldBe listOf(
            finding(starImport, starImportToken, "SourceScan", indexingTarget),
        )

        val aliasProducer = KotlinSourceUnit(
            "com/other/AliasProducer.kt",
            "package com.other\nimport com.plainbase.domain.service.SourceScan\ntypealias Mid = SourceScan",
        )
        val aliasConsumer = KotlinSourceUnit(
            "com/other/AliasConsumer.kt",
            "package com.other\nimport com.other.Mid as Scan\ntypealias Outer = Scan",
        )
        val aliasThirdConsumer = KotlinSourceUnit(
            "com/other/AliasThirdConsumer.kt",
            "package com.other\nimport com.other.Outer as Input\nfun consume(value: Input) = value",
        )
        val aliasIndex = KotlinSourceIndex(listOf(indexingDefinitions, aliasProducer, aliasConsumer, aliasThirdConsumer))
        val aliasThirdOffset = aliasThirdConsumer.source.lastIndexOf("Input")
        val aliasThirdToken = aliasThirdConsumer.tokens.indexOfLast { it.offset == aliasThirdOffset }
        withClue("third consumer must resolve through the defining files: ${aliasIndex.indexingReferences(aliasThirdConsumer)}") {
            aliasIndex.resolveReference(aliasThirdConsumer, aliasThirdToken).identity shouldBe indexingTarget
            aliasIndex.indexingReferences(aliasThirdConsumer) shouldBe listOf(
                finding(aliasThirdConsumer, aliasThirdToken, "Input", indexingTarget),
            )
        }

        val cycleFirst = KotlinSourceUnit(
            "com/other/CycleAliases.kt",
            "package com.other\ntypealias First = Second\ntypealias Second = First",
        )
        val cycleConsumer = KotlinSourceUnit(
            "com/other/CycleConsumer.kt",
            "package com.other\nfun consume(value: First) = value",
        )
        val cycleIndex = KotlinSourceIndex(listOf(cycleFirst, cycleConsumer))
        val cycleOffset = cycleConsumer.source.lastIndexOf("First")
        val cycleToken = cycleConsumer.tokens.indexOfLast { it.offset == cycleOffset }
        val cycleFinding = "${cycleConsumer.path}:${cycleConsumer.indexed.lineOf(cycleConsumer.tokens[cycleToken].offset)}:" +
            "${cycleConsumer.tokens[cycleToken].offset}:First -> alias cycle " +
            "com.other.First -> com.other.Second -> com.other.First"
        withClue("alias cycle must remain a location-specific diagnostic: ${cycleIndex.indexingReferences(cycleConsumer)}") {
            cycleIndex.resolveReference(cycleConsumer, cycleToken).cycle shouldBe listOf(
                "com.other.First", "com.other.Second", "com.other.First",
            )
            cycleIndex.indexingReferences(cycleConsumer) shouldBe listOf(cycleFinding)
        }

        val foreign = KotlinSourceUnit("com/foreign/Source.kt", "package com.foreign\nclass SourceScan")
        val samePackageCandidate = KotlinSourceUnit(
            "com/plainbase/domain/service/LocalCandidate.kt",
            "package com.plainbase.domain.service\nclass SourceScan",
        )
        val foreignPriority = KotlinSourceUnit(
            "com/other/ForeignPriority.kt",
            "package com.plainbase.domain.service\nimport com.foreign.SourceScan\nfun consume(value: SourceScan) = value",
        )
        val foreignIndex = KotlinSourceIndex(listOf(foreign, samePackageCandidate, foreignPriority))
        val foreignOffset = foreignPriority.source.lastIndexOf("SourceScan")
        val foreignToken = foreignPriority.tokens.indexOfLast { it.offset == foreignOffset }
        foreignIndex.resolveReference(foreignPriority, foreignToken).identity shouldBe "com.foreign.SourceScan"
        foreignIndex.indexingReferences(foreignPriority) shouldBe emptyList()

        val adoption = KotlinSourceUnit(
            "com/other/AdoptionFixture.kt",
            """
            package com.plainbase.domain.service
            class AdoptionPass {
                private class Draft
                fun inside(value: Draft) = value
                fun qualified(value: AdoptionPass.Draft) = value
                fun forbidden(value: com.plainbase.domain.service.Draft) = value
            }
            fun outside(value: Draft) = value
            """.trimIndent(),
        )
        val adoptionIndex = KotlinSourceIndex(listOf(indexingDefinitions, adoption))
        val draftTarget = "com.plainbase.domain.service.Draft"
        val forbiddenOffset = adoption.source.indexOf("com.plainbase.domain.service.Draft")
        val outsideOffset = adoption.source.lastIndexOf("Draft")
        val forbiddenToken = adoption.tokens.last { it.offset == forbiddenOffset }
        val outsideToken = adoption.tokens.last { it.offset == outsideOffset }
        val adoptionFindings = listOf(
            finding(adoption, adoption.tokens.indexOf(forbiddenToken), "com.plainbase.domain.service.Draft", draftTarget),
            finding(adoption, adoption.tokens.indexOf(outsideToken), "Draft", draftTarget),
        )
        withClue("nested owner and fully-qualified references must resolve independently: ${adoptionIndex.indexingReferences(adoption)}") {
            adoptionIndex.indexingReferences(adoption) shouldBe adoptionFindings
        }

        val generic = KotlinSourceUnit(
            "com/other/GenericFixtures.kt",
            """
            package com.other
            import com.plainbase.domain.service.SourceScan
            class Box<SourceScan> { fun own(value: SourceScan) = value }
            fun <SourceScan> generic(value: SourceScan) = value
            fun after(value: SourceScan) = value
            """.trimIndent(),
        )
        val genericIndex = KotlinSourceIndex(listOf(indexingDefinitions, generic))
        val genericOffset = generic.source.lastIndexOf("SourceScan")
        val genericToken = generic.tokens.last { it.offset == genericOffset }
        genericIndex.indexingReferences(generic) shouldBe listOf(
            finding(generic, generic.tokens.indexOf(genericToken), "SourceScan", indexingTarget),
        )

        val literal = KotlinSourceUnit(
            "com/other/ClassLiteral.kt",
            "package com.other\nimport com.plainbase.domain.service.SourceScan\n" +
                "fun show() = \"${'$'}{SourceScan::class.simpleName}\"",
        )
        val literalIndex = KotlinSourceIndex(listOf(indexingDefinitions, literal))
        val literalOffset = literal.source.lastIndexOf("SourceScan")
        val literalToken = literal.tokens.last { it.offset == literalOffset }
        literalIndex.discoveredDeclarations().filter { it.startsWith("${literal.path}|") } shouldBe emptyList()
        literalIndex.indexingReferences(literal) shouldBe listOf(
            finding(literal, literal.tokens.indexOf(literalToken), "SourceScan", indexingTarget),
        )
        literal.indexed.scopes.any { scope ->
            scope.kind == SourceScopeKind.INTERPOLATION && scope.startOffset == literal.source.indexOf('{')
        } shouldBe true

        val multiline = KotlinSourceUnit(
            "com/other/MultilineSignature.kt",
            """
            package com.other
            private typealias HiddenObservation = com.plainbase.domain.root.ObservationId
            public fun multiline(
                input: HiddenObservation,
            ): String = input.toString()
            public fun bodyOnly(): String = com.plainbase.domain.root.ObservationId::class.java.name
            """.trimIndent(),
        )
        val authorityDefinitions = KotlinSourceUnit(
            "com/plainbase/domain/root/AuthorityDefinitions.kt",
            "package com.plainbase.domain.root\nclass ObservationId\nclass BindingEpoch",
        )
        val multilineIndex = KotlinSourceIndex(listOf(authorityDefinitions, multiline))
        val hiddenObservationOffset = multiline.source.lastIndexOf("HiddenObservation")
        val hiddenObservationToken = multiline.tokens.last { it.offset == hiddenObservationOffset }
        multilineIndex.publicSignatureAuthorityViolations() shouldBe setOf(
            "${multiline.path}:${multiline.indexed.lineOf(hiddenObservationOffset)}:multiline:HiddenObservation -> " +
                "com.plainbase.domain.root.ObservationId",
        )
        multilineIndex.resolveReference(multiline, multiline.tokens.indexOf(hiddenObservationToken)).identity shouldBe
            "com.plainbase.domain.root.ObservationId"

        val companionAlias = KotlinSourceUnit(
            "com/other/CompanionAlias.kt",
            """
            package com.other
            private typealias HiddenEpoch = com.plainbase.domain.root.BindingEpoch
            class CompanionFixture {
                companion object {
                    val exposed: HiddenEpoch? = null
                }
            }
            """.trimIndent(),
        )
        val companionIndex = KotlinSourceIndex(listOf(authorityDefinitions, companionAlias))
        val hiddenEpochOffset = companionAlias.source.lastIndexOf("HiddenEpoch")
        companionIndex.publicSignatureAuthorityViolations() shouldBe setOf(
            "${companionAlias.path}:${companionAlias.indexed.lineOf(hiddenEpochOffset)}:" +
                "CompanionFixture.Companion.exposed:HiddenEpoch -> com.plainbase.domain.root.BindingEpoch",
        )

        val privateControls = KotlinSourceUnit(
            "com/other/PrivateControls.kt",
            """
            package com.other
            class Exposure(
                private val hidden: com.plainbase.domain.root.ObservationId,
            ) {
                public val exposed: com.plainbase.domain.root.ObservationId? = null
                private val privateExposed: com.plainbase.domain.root.ObservationId? = null
            }
            private class Unreachable {
                public val hidden: com.plainbase.domain.root.ObservationId? = null
            }
            """.trimIndent(),
        )
        val privateControlsIndex = KotlinSourceIndex(listOf(authorityDefinitions, privateControls))
        val exposedOffset = privateControls.source.indexOf("ObservationId", privateControls.source.indexOf("public val exposed"))
        privateControlsIndex.publicSignatureAuthorityViolations(setOf("com.other.Exposure")) shouldBe setOf(
            "${privateControls.path}:${privateControls.indexed.lineOf(exposedOffset)}:" +
                "Exposure.exposed:com.plainbase.domain.root.ObservationId -> com.plainbase.domain.root.ObservationId",
        )
    }

    test("source-index alias, generic, and scoped type references remain discriminating") {
        fun finding(unit: KotlinSourceUnit, index: Int, spelling: String, target: String): String =
            "${unit.path}:${unit.indexed.lineOf(unit.tokens[index].offset)}:${unit.tokens[index].offset}:$spelling -> $target"

        val indexingDefinitions = KotlinSourceUnit(
            "com/plainbase/domain/service/IndexingDefinitions.kt",
            "package com.plainbase.domain.service\nclass Draft\nclass SourceScan\nclass Identity",
        )
        val constructorUse = KotlinSourceUnit(
            "com/other/ConstructorUse.kt",
            """
            package com.other
            fun make() = com.plainbase.domain.service.Draft(file, bytes, frontmatter)
            fun samePackage() = Draft(file, bytes, frontmatter)
            fun localValue() { val Draft = 1; val copy = Draft }
            """.trimIndent(),
        )
        val samePackageConstructor = KotlinSourceUnit(
            "com/plainbase/domain/service/SamePackageConstructor.kt",
            "package com.plainbase.domain.service\nfun samePackage() = Draft(file, bytes, frontmatter)",
        )
        val constructorIndex = KotlinSourceIndex(listOf(indexingDefinitions, constructorUse, samePackageConstructor))
        val qualifiedConstructorOffset = constructorUse.source.indexOf("com.plainbase.domain.service.Draft")
        val qualifiedConstructorToken = constructorUse.tokens.last { it.offset == qualifiedConstructorOffset }
        val samePackageConstructorOffset = samePackageConstructor.source.lastIndexOf("Draft")
        val samePackageConstructorToken = samePackageConstructor.tokens.last { it.offset == samePackageConstructorOffset }
        constructorIndex.resolveExpressionReference(
            samePackageConstructor,
            samePackageConstructor.tokens.indexOf(samePackageConstructorToken),
        ).identity shouldBe "com.plainbase.domain.service.Draft"
        val localValueOffset = constructorUse.source.lastIndexOf("Draft")
        val localValueToken = constructorUse.tokens.last { it.offset == localValueOffset }
        constructorIndex.resolveExpressionReference(
            constructorUse,
            constructorUse.tokens.indexOf(localValueToken),
        ).namespace shouldBe SourceNamespace.VALUE
        constructorIndex.resolveExpressionReference(
            constructorUse,
            constructorUse.tokens.indexOf(localValueToken),
        ).identity shouldBe "com.other.localValue.Draft"
        withClue("expression constructors must resolve independently and retain exact locations") {
            val constructorReferences = constructorIndex.indexingReferences(constructorUse) +
                constructorIndex.indexingReferences(samePackageConstructor)
            constructorReferences shouldBe listOf(
                finding(
                    constructorUse,
                    constructorUse.tokens.indexOf(qualifiedConstructorToken),
                    "com.plainbase.domain.service.Draft",
                    "com.plainbase.domain.service.Draft",
                ),
                finding(
                    samePackageConstructor,
                    samePackageConstructor.tokens.indexOf(samePackageConstructorToken),
                    "Draft",
                    "com.plainbase.domain.service.Draft",
                ),
            )
        }

        val aliasProducer = KotlinSourceUnit(
            "com/producer/Aliases.kt",
            """
            package com.producer
            import com.plainbase.domain.service.SourceScan
            typealias Inputs = List<SourceScan>
            typealias Box<T> = List<T>
            """.trimIndent(),
        )
        val aliasConsumer = KotlinSourceUnit(
            "com/consumer/AliasConsumer.kt",
            """
            package com.consumer
            import com.producer.Inputs
            import com.producer.Box
            fun consume(value: Inputs) = value
            fun consumeBox(value: Box<com.plainbase.domain.service.SourceScan>) = value
            """.trimIndent(),
        )
        val aliasIndex = KotlinSourceIndex(listOf(indexingDefinitions, aliasProducer, aliasConsumer))
        val inputsOffset = aliasConsumer.source.lastIndexOf("Inputs")
        val boxNameOffset = aliasConsumer.source.lastIndexOf("Box")
        val boxOffset = aliasConsumer.source.indexOf("com.plainbase.domain.service.SourceScan")
        val inputsToken = aliasConsumer.tokens.last { it.offset == inputsOffset }
        val boxNameToken = aliasConsumer.tokens.last { it.offset == boxNameOffset }
        aliasIndex.resolveReference(aliasConsumer, aliasConsumer.tokens.indexOf(inputsToken)).allIdentities shouldBe
            setOf("com.plainbase.domain.service.SourceScan")
        aliasIndex.resolveReference(aliasConsumer, aliasConsumer.tokens.indexOf(boxNameToken)).allIdentities shouldBe
            setOf("com.plainbase.domain.service.SourceScan")
        val producerOffset = aliasProducer.source.lastIndexOf("SourceScan")
        aliasIndex.indexingReferences(aliasProducer) shouldBe listOf(
            finding(
                aliasProducer,
                aliasProducer.tokens.last { it.offset == producerOffset }.let(aliasProducer.tokens::indexOf),
                "SourceScan",
                "com.plainbase.domain.service.SourceScan",
            ),
        )
        withClue("generic alias consumers must retain contained indexing identities") {
            aliasIndex.indexingReferences(aliasConsumer) shouldBe listOf(
                finding(
                    aliasConsumer,
                    aliasConsumer.tokens.indexOf(inputsToken),
                    "Inputs",
                    "com.plainbase.domain.service.SourceScan",
                ),
                finding(
                    aliasConsumer,
                    aliasConsumer.tokens.indexOf(boxNameToken),
                    "Box",
                    "com.plainbase.domain.service.SourceScan",
                ),
                finding(
                    aliasConsumer,
                    aliasConsumer.tokens.last { it.offset == boxOffset }.let(aliasConsumer.tokens::indexOf),
                    "com.plainbase.domain.service.SourceScan",
                    "com.plainbase.domain.service.SourceScan",
                ),
            )
        }

        val starA = KotlinSourceUnit("a/Draft.kt", "package a\nclass Draft")
        val starB = KotlinSourceUnit("b/Draft.kt", "package b\nclass Draft")
        val ambiguous = KotlinSourceUnit(
            "com/consumer/Ambiguous.kt",
            "package com.consumer\nimport a.*\nimport b.*\nfun use(value: Draft) = value",
        )
        val ambiguousIndex = KotlinSourceIndex(listOf(starA, starB, ambiguous))
        val ambiguousOffset = ambiguous.source.lastIndexOf("Draft")
        val ambiguousToken = ambiguous.tokens.last { it.offset == ambiguousOffset }
        withClue("two star candidates must remain an explicit ambiguity") {
            ambiguousIndex.resolveReference(ambiguous, ambiguous.tokens.indexOf(ambiguousToken)).apply {
                identity shouldBe null
                ambiguity shouldBe listOf("a.Draft", "b.Draft")
            }
            ambiguousIndex.indexingReferences(ambiguous) shouldBe listOf(
                "${ambiguous.path}:${ambiguous.indexed.lineOf(ambiguousToken.offset)}:${ambiguousToken.offset}:" +
                    "Draft -> ambiguous a.Draft, b.Draft",
            )
        }

        val unresolved = KotlinSourceUnit(
            "com/unrelated/Unresolved.kt",
            "package com.unrelated\nfun use(value: Draft) = value",
        )
        val unresolvedIndex = KotlinSourceIndex(listOf(indexingDefinitions, unresolved))
        withClue("an unrelated bare name without a declaration or import must stay unresolved") {
            unresolvedIndex.indexingReferences(unresolved) shouldBe emptyList()
        }

        val lexicalOwner = KotlinSourceUnit(
            "com/other/LexicalOwner.kt",
            "package com.other\nclass Owner { class Draft; fun use(value: Owner.Draft) = value }",
        )
        val lexicalOwnerIndex = KotlinSourceIndex(listOf(indexingDefinitions, lexicalOwner))
        val lexicalOwnerOffset = lexicalOwner.source.lastIndexOf("Owner.Draft")
        val lexicalOwnerToken = lexicalOwner.tokens.last { it.offset == lexicalOwnerOffset }
        lexicalOwnerIndex.resolveReference(lexicalOwner, lexicalOwner.tokens.indexOf(lexicalOwnerToken)).identity shouldBe
            "com.other.Owner.Draft"

        val companionFixture = KotlinSourceUnit(
            "com/other/Companions.kt",
            """
            package com.other
            class Owner { companion object Factory { class Nested } }
            class Default { companion object { class Nested } }
            """.trimIndent(),
        )
        withClue("named and default companion source identities must be preserved") {
            KotlinSourceIndex(listOf(companionFixture)).discoveredDeclarations() shouldBe listOf(
                "com/other/Companions.kt|Owner",
                "com/other/Companions.kt|Owner.Factory",
                "com/other/Companions.kt|Owner.Factory.Nested",
                "com/other/Companions.kt|Default",
                "com/other/Companions.kt|Default.Companion",
                "com/other/Companions.kt|Default.Companion.Nested",
            )
        }

        val bodylessFixture = KotlinSourceUnit(
            "scope-d2.kt",
            "class Outer { class Bodyless; fun f() { class Local } }",
        )
        val localDeclaration = bodylessFixture.indexed.declarations.single { it.qualifiedName == "Outer.f.Local" }
        localDeclaration.ownerQualifiedName shouldBe "Outer.f"
        val repeatedLocals = KotlinSourceIndex(
            listOf(KotlinSourceUnit("scope-multiple.kt", "class Outer { fun f() { class Local }; fun g() { class Local } }")),
        ).discoveredDeclarations()
        repeatedLocals shouldBe listOf("scope-multiple.kt|Outer", "scope-multiple.kt|Outer.f.Local", "scope-multiple.kt|Outer.g.Local")
        classifySourceJvmMapping(
            bodylessFixture,
            localDeclaration,
            this::class.java.classLoader,
        ) shouldBe SourceJvmClassification(
            sourceQualifiedName = "Outer.f.Local",
            executableOwner = "Outer.f",
            emittedBinaryName = null,
            loadable = false,
            diagnostic = "no stable emitted JVM name for function-local Outer.f.Local",
        )

        val signatureDefinitions = listOf(
            KotlinSourceUnit(
                "com/plainbase/domain/root/AuthorityDefinitions.kt",
                "package com.plainbase.domain.root\nclass ObservationId\nclass BindingEpoch",
            ),
        )
        fun signatureViolations(path: String, source: String): Set<String> =
            KotlinSourceIndex(signatureDefinitions + KotlinSourceUnit(path, source)).publicSignatureAuthorityViolations()
        val explicitPropertySource = """
            package com.other
            class ExplicitProperty(public val token: com.plainbase.domain.root.ObservationId)
            class PlainProperty(val token: com.plainbase.domain.root.ObservationId)
            class PrivateTail(val safe: String, private val hidden: com.plainbase.domain.root.ObservationId)
            """.trimIndent()
        val explicitPropertyUnit = KotlinSourceUnit("explicit-property.kt", explicitPropertySource)
        val explicitProperty = signatureViolations("explicit-property.kt", explicitPropertySource)
        val explicitOffset = explicitPropertySource.indexOf("ObservationId")
        val plainOffset = explicitPropertySource.indexOf("ObservationId", explicitOffset + 1)
        withClue("constructor property findings must identify the exact public member: $explicitProperty") {
            explicitProperty shouldBe setOf(
                "explicit-property.kt:${explicitPropertyUnit.indexed.lineOf(explicitOffset)}:" +
                    "ExplicitProperty.token:com.plainbase.domain.root.ObservationId -> com.plainbase.domain.root.ObservationId",
                "explicit-property.kt:${explicitPropertyUnit.indexed.lineOf(plainOffset)}:" +
                    "PlainProperty.token:com.plainbase.domain.root.ObservationId -> com.plainbase.domain.root.ObservationId",
            )
        }
        val defaultSource = """
                package com.other
                class DefaultOnly {
                    fun safe(value: String = com.plainbase.domain.root.ObservationId::class.java.name): String = value
                }
                class DeclaredToken {
                    fun exposed(value: com.plainbase.domain.root.ObservationId): String = value.toString()
                }
                """.trimIndent()
        val defaultUnit = KotlinSourceUnit("default-only.kt", defaultSource)
        val defaultViolations = signatureViolations("default-only.kt", defaultSource)
        val declaredOffset = defaultSource.lastIndexOf("ObservationId")
        withClue("default expressions are not declared signature types: $defaultViolations") {
            defaultViolations shouldBe setOf(
                "default-only.kt:${defaultUnit.indexed.lineOf(declaredOffset)}:" +
                    "DeclaredToken.exposed:com.plainbase.domain.root.ObservationId -> com.plainbase.domain.root.ObservationId",
            )
            defaultViolations.none { it.contains("DefaultOnly") } shouldBe true
        }
        val receiverSource = """
                package com.other
                class ReceiverExposure { fun com.plainbase.domain.root.ObservationId.example(): String = toString() }
                class SafeReceiver { fun String.example(): String = this }
                """.trimIndent()
        val receiverUnit = KotlinSourceUnit("receiver.kt", receiverSource)
        val receiverViolations = signatureViolations("receiver.kt", receiverSource)
        val receiverOffset = receiverSource.indexOf("ObservationId")
        withClue("extension receiver type is an independent declared signature region: $receiverViolations") {
            receiverViolations shouldBe setOf(
                "receiver.kt:${receiverUnit.indexed.lineOf(receiverOffset)}:" +
                    "ReceiverExposure.example:com.plainbase.domain.root.ObservationId -> " +
                    "com.plainbase.domain.root.ObservationId",
            )
        }
    }

    test("callback type regions preserve consumer scope and alias resolution") {
        fun finding(unit: KotlinSourceUnit, index: Int, spelling: String, target: String): String =
            "${unit.path}:${unit.indexed.lineOf(unit.tokens[index].offset)}:${unit.tokens[index].offset}:$spelling -> $target"

        val indexingDefinitions = KotlinSourceUnit(
            "com/plainbase/domain/service/IndexingDefinitions.kt",
            "package com.plainbase.domain.service\nclass SourceScan",
        )
        val direct = KotlinSourceUnit(
            "com/other/DirectCallbackConsumer.kt",
            "package com.other\nimport com.plainbase.domain.service.SourceScan\n" +
                "fun use(callback: (SourceScan) -> Unit) = Unit",
        )
        val directIndex = KotlinSourceIndex(listOf(indexingDefinitions, direct))
        val directOffset = direct.source.lastIndexOf("SourceScan")
        val directToken = direct.tokens.last { it.offset == directOffset }
        withClue("direct callback parameter is a consumer reference") {
            directIndex.resolveReference(direct, direct.tokens.indexOf(directToken)).identity shouldBe
                "com.plainbase.domain.service.SourceScan"
            directIndex.indexingReferences(direct) shouldBe listOf(
                finding(direct, direct.tokens.indexOf(directToken), "SourceScan", "com.plainbase.domain.service.SourceScan"),
            )
        }

        val importedAlias = KotlinSourceUnit(
            "com/other/AliasedCallbackConsumer.kt",
            "package com.other\nimport com.plainbase.domain.service.SourceScan as Scan\n" +
                "fun use(callback: (Scan) -> Unit) = Unit",
        )
        val importedAliasIndex = KotlinSourceIndex(listOf(indexingDefinitions, importedAlias))
        val importedAliasOffset = importedAlias.source.lastIndexOf("Scan")
        val importedAliasToken = importedAlias.tokens.last { it.offset == importedAliasOffset }
        withClue("import aliases inside callback parameters remain confined") {
            importedAliasIndex.indexingReferences(importedAlias) shouldBe listOf(
                finding(importedAlias, importedAlias.tokens.indexOf(importedAliasToken), "Scan", "com.plainbase.domain.service.SourceScan"),
            )
        }

        val aliasProducer = KotlinSourceUnit(
            "com/producer/CallbackAliases.kt",
            "package com.producer\nimport com.plainbase.domain.service.SourceScan\n" +
                "typealias Handler = (SourceScan) -> Unit\n" +
                "typealias GenericHandler<T> = (input: T) -> Unit",
        )
        val aliasConsumer = KotlinSourceUnit(
            "com/consumer/CallbackAliasConsumer.kt",
            "package com.consumer\nimport com.producer.Handler\n" +
                "fun use(callback: Handler) = Unit",
        )
        val aliasIndex = KotlinSourceIndex(listOf(indexingDefinitions, aliasProducer, aliasConsumer))
        val handlerOffset = aliasConsumer.source.lastIndexOf("Handler")
        val handlerToken = aliasConsumer.tokens.last { it.offset == handlerOffset }
        withClue("the alias consumer must be rejected independently of the producer") {
            aliasIndex.resolveReference(aliasConsumer, aliasConsumer.tokens.indexOf(handlerToken)).allIdentities shouldBe
                setOf("com.plainbase.domain.service.SourceScan")
            aliasIndex.indexingReferences(aliasConsumer) shouldBe listOf(
                finding(aliasConsumer, aliasConsumer.tokens.indexOf(handlerToken), "Handler", "com.plainbase.domain.service.SourceScan"),
            )
        }

        val genericConsumer = KotlinSourceUnit(
            "com/consumer/GenericCallbackAliasConsumer.kt",
            "package com.consumer\nimport com.producer.GenericHandler\n" +
                "import com.plainbase.domain.service.SourceScan\n" +
                "fun use(callback: GenericHandler<SourceScan?>?) = Unit",
        )
            val genericIndex = KotlinSourceIndex(listOf(indexingDefinitions, aliasProducer, genericConsumer))
            val genericSourceOffset = genericConsumer.source.lastIndexOf("SourceScan")
            val genericSourceToken = genericConsumer.tokens.last { it.offset == genericSourceOffset }
            withClue("generic callback aliases preserve nullable contained types and labels") {
                genericIndex.indexingReferences(genericConsumer) shouldBe listOf(
                    finding(
                        genericConsumer,
                        genericConsumer.tokens.indexOf(genericConsumer.tokens.last { it.text == "GenericHandler" }),
                        "GenericHandler",
                        "com.plainbase.domain.service.SourceScan",
                    ),
                    finding(
                        genericConsumer,
                        genericConsumer.tokens.indexOf(genericSourceToken),
                    "SourceScan",
                        "com.plainbase.domain.service.SourceScan",
                    ),
            )
        }

        val namedNullable = KotlinSourceUnit(
            "com/other/NamedNullableCallback.kt",
            "package com.other\nimport com.plainbase.domain.service.SourceScan\n" +
                "fun use(callback: (input: SourceScan?) -> Unit) = Unit",
        )
        val namedNullableIndex = KotlinSourceIndex(listOf(indexingDefinitions, namedNullable))
        val namedNullableOffset = namedNullable.source.lastIndexOf("SourceScan")
        val namedNullableToken = namedNullable.tokens.last { it.offset == namedNullableOffset }
        namedNullableIndex.indexingReferences(namedNullable) shouldBe listOf(
            finding(
                namedNullable,
                namedNullable.tokens.indexOf(namedNullableToken),
                "SourceScan",
                "com.plainbase.domain.service.SourceScan",
            ),
        )

        val foreignDefinition = KotlinSourceUnit("com/safe/SourceScan.kt", "package com.safe\nclass SourceScan")
        val foreignConsumer = KotlinSourceUnit(
            "com/safe/ForeignCallbackConsumer.kt",
            "package com.safe\nimport com.safe.SourceScan\nfun use(callback: (SourceScan) -> Unit) = Unit",
        )
        withClue("an explicitly imported foreign callback type is safe") {
            KotlinSourceIndex(listOf(indexingDefinitions, foreignDefinition, foreignConsumer))
                .indexingReferences(foreignConsumer) shouldBe emptyList()
        }

        val nestedConsumer = KotlinSourceUnit(
            "com/other/NestedCallbackConsumer.kt",
            "package com.other\nclass Owner { class SourceScan; " +
                "fun use(callback: (SourceScan) -> Unit) = Unit }",
        )
        withClue("a legitimate nested callback type keeps lexical precedence") {
            KotlinSourceIndex(listOf(indexingDefinitions, nestedConsumer)).indexingReferences(nestedConsumer) shouldBe emptyList()
        }

        val ordinaryValueConsumer = KotlinSourceUnit(
            "com/other/OrdinaryCallbackArgument.kt",
            "package com.other\nfun sink(value: Any) = Unit\n" +
                "fun use() { val SourceScan = 1; sink(SourceScan) }",
        )
        withClue("ordinary callback arguments are not type regions") {
            KotlinSourceIndex(listOf(indexingDefinitions, ordinaryValueConsumer))
                .indexingReferences(ordinaryValueConsumer) shouldBe emptyList()
        }
    }

    test("source-index callback regions and erased constructor results stay bounded") {
        val indexingDefinitions = KotlinSourceUnit(
            "com/plainbase/domain/service/IndexingDefinitions.kt",
            "package com.plainbase.domain.service\nclass Draft\nclass SourceScan\nclass Identity",
        )
        fun tokenAt(unit: KotlinSourceUnit, offset: Int): Int = unit.tokens.indexOfFirst { it.offset == offset }

        val recursiveAlias = KotlinSourceUnit(
            "com/other/RecursiveAlias.kt",
            "package com.other\ntypealias A = List<A>\nfun consume(value: A) = value",
        )
        val recursiveAliasIndex = KotlinSourceIndex(listOf(indexingDefinitions, recursiveAlias))
        val consumerAliasOffset = recursiveAlias.source.lastIndexOf("A")
        val consumerAliasToken = tokenAt(recursiveAlias, consumerAliasOffset)
        val recursiveResolution = runCatching {
            recursiveAliasIndex.resolveReference(recursiveAlias, consumerAliasToken)
        }
        withClue("generic alias cycles must carry their active expansion stack") {
            recursiveResolution.isSuccess shouldBe true
            recursiveResolution.getOrThrow().cycle shouldBe listOf("com.other.A", "com.other.A")
        }

        val valueScope = KotlinSourceUnit(
            "com/other/ValueScope.kt",
            """
            package com.other
            import com.plainbase.domain.service.Draft
            fun first(Draft: () -> Unit) { Draft() }
            fun second() = Draft(file, bytes, frontmatter)
            """.trimIndent(),
        )
        val valueScopeIndex = KotlinSourceIndex(listOf(indexingDefinitions, valueScope))
        val firstCallOffset = valueScope.source.indexOf("Draft()")
        val secondCallOffset = valueScope.source.lastIndexOf("Draft")
        val firstCallToken = tokenAt(valueScope, firstCallOffset)
        val secondCallToken = tokenAt(valueScope, secondCallOffset)
        val firstParameter = valueScope.indexed.declarations.single {
            it.kind == SourceDeclarationKind.PARAMETER && it.name == "Draft"
        }
        withClue("a value parameter is visible in its owning function") {
            valueScopeIndex.resolveExpressionReference(valueScope, firstCallToken).identity shouldBe firstParameter.identity
            valueScopeIndex.resolveExpressionReference(valueScope, firstCallToken).namespace shouldBe SourceNamespace.VALUE
        }
        withClue("a sibling function must resolve the imported indexing constructor independently") {
            valueScopeIndex.resolveExpressionReference(valueScope, secondCallToken).identity shouldBe
                "com.plainbase.domain.service.Draft"
            valueScopeIndex.resolveExpressionReference(valueScope, secondCallToken).namespace shouldBe SourceNamespace.TYPE
            valueScopeIndex.indexingReferences(valueScope) shouldBe listOf(
                "${valueScope.path}:${valueScope.indexed.lineOf(valueScope.tokens[secondCallToken].offset)}:" +
                    "${valueScope.tokens[secondCallToken].offset}:Draft -> com.plainbase.domain.service.Draft",
            )
        }

        val qualifiedOwners = KotlinSourceUnit(
            "p/QualifiedOwners.kt",
            "package p\nclass Owner { class Draft }\n" +
                "class Outer { class Owner { class Draft }; fun use(value: Owner.Draft) = value }",
        )
        val qualifiedOwnersIndex = KotlinSourceIndex(listOf(qualifiedOwners))
        val qualifiedOwnerOffset = qualifiedOwners.source.lastIndexOf("Owner.Draft")
        val qualifiedOwnerToken = tokenAt(qualifiedOwners, qualifiedOwnerOffset)
        withClue("qualified leading symbols select the deepest lexical owner") {
            qualifiedOwnersIndex.resolveReference(qualifiedOwners, qualifiedOwnerToken).identity shouldBe
                "p.Outer.Owner.Draft"
        }

        val realStableUnit = KotlinSourceUnit(
            "com/plainbase/domain/service/IndexBuilder.kt",
            Files.readString(serviceRoot.resolve("IndexBuilder.kt")),
        )
        val realStableDeclaration = realStableUnit.indexed.declarations.single { it.qualifiedName == "IndexBuilder.Source" }
        val realStableClassification = classifySourceJvmMapping(realStableUnit, realStableDeclaration, this::class.java.classLoader)
        withClue("stable package/member mapping preserves package dots") {
            realStableClassification.emittedBinaryName shouldBe
                "com.plainbase.domain.service.IndexBuilder\$Source"
            realStableClassification.loadable shouldBe true
        }
        val localJvmUnit = KotlinSourceUnit(
            "com/other/LocalJvm.kt",
            """
            package com.other
            class Outer {
                fun f() {
                    class Local { class Nested }
                }
                val holder = run { class InitializerLocal; InitializerLocal() }
            }
            """.trimIndent(),
        )
        val localJvmClassifications = localJvmUnit.indexed.declarations
            .filter { it.kind in setOf(SourceDeclarationKind.CLASS, SourceDeclarationKind.INTERFACE, SourceDeclarationKind.OBJECT) }
            .associate { declaration ->
                declaration.qualifiedName to classifySourceJvmMapping(localJvmUnit, declaration, this::class.java.classLoader)
            }
        listOf("Outer.f.Local", "Outer.f.Local.Nested", "Outer.InitializerLocal").forEach { name ->
            withClue("executable-local declaration classification for $name") {
                localJvmClassifications.getValue(name).emittedBinaryName shouldBe null
                localJvmClassifications.getValue(name).loadable shouldBe false
                localJvmClassifications.getValue(name).diagnostic?.isNotEmpty() shouldBe true
            }
        }
        localJvmClassifications.getValue("Outer.f.Local").executableOwner shouldBe "Outer.f"
        localJvmClassifications.getValue("Outer.f.Local.Nested").executableOwner shouldBe "Outer.f"

        val contextualSignatureSource = """
            package com.other
            import com.plainbase.domain.root.ObservationId
            class DataParameter { fun expose(data: ObservationId): String = data.toString() }
            class OutParameter { fun expose(out: ObservationId): String = out.toString() }
            class ActualParameter { fun expose(actual: ObservationId): String = actual.toString() }
            class SafeParameter { fun expose(data: String): String = data }
        """.trimIndent()
        val contextualSignatureUnit = KotlinSourceUnit("contextual-signatures.kt", contextualSignatureSource)
        val contextualSignatureViolations = KotlinSourceIndex(
            listOf(
                KotlinSourceUnit(
                    "com/plainbase/domain/root/AuthorityDefinitions.kt",
                    "package com.plainbase.domain.root\nclass ObservationId\nclass BindingEpoch",
                ),
                contextualSignatureUnit,
            ),
        ).publicSignatureAuthorityViolations()
        val contextualOffsets = listOf("data", "out", "actual").map { name ->
            contextualSignatureSource.indexOf("ObservationId", contextualSignatureSource.indexOf("fun expose($name"))
        }
        withClue("contextual parameter identifiers are parsed by position") {
            contextualSignatureViolations shouldBe contextualOffsets.mapIndexed { index, offset ->
                val owner = listOf("DataParameter", "OutParameter", "ActualParameter")[index]
                "${contextualSignatureUnit.path}:${contextualSignatureUnit.indexed.lineOf(offset)}:" +
                    "$owner.expose:ObservationId -> com.plainbase.domain.root.ObservationId"
            }.toSet()
            contextualSignatureViolations.none { it.contains("SafeParameter") } shouldBe true
        }

        val constructorVisibilitySource = """
            package com.other
            import com.plainbase.domain.root.ObservationId
            class PublicGetter private constructor(val token: ObservationId)
            class PrivateStorage private constructor(private val token: ObservationId)
            class PlainParameter private constructor(token: ObservationId)
        """.trimIndent()
        val constructorVisibilityUnit = KotlinSourceUnit("constructor-visibility.kt", constructorVisibilitySource)
        val constructorVisibilityViolations = KotlinSourceIndex(
            listOf(
                KotlinSourceUnit(
                    "com/plainbase/domain/root/AuthorityDefinitions.kt",
                    "package com.plainbase.domain.root\nclass ObservationId\nclass BindingEpoch",
                ),
                constructorVisibilityUnit,
            ),
        ).publicSignatureAuthorityViolations()
        val publicGetterOffset = constructorVisibilitySource.indexOf(
            "ObservationId",
            constructorVisibilitySource.indexOf("class PublicGetter"),
        )
        withClue("constructor visibility is bounded to each property segment") {
            constructorVisibilityViolations shouldBe setOf(
                "${constructorVisibilityUnit.path}:${constructorVisibilityUnit.indexed.lineOf(publicGetterOffset)}:" +
                    "PublicGetter.token:ObservationId -> com.plainbase.domain.root.ObservationId",
            )
            constructorVisibilityViolations.none { it.contains("PrivateStorage") || it.contains("PlainParameter") } shouldBe true
        }
    }

    test("reader direct surface") {
        val reader = Class.forName("com.plainbase.domain.service.IndexSourceReader")
        val constructors = reader.declaredConstructors.toList()
        val expectedConstructor = listOf(
            FrontmatterParser::class.java,
            AbsenceClassifier::class.java,
            RootAvailability::class.java,
            RootLossClassifier::class.java,
        )
        val violations = mutableListOf<String>()
        if (constructors.size != 1) {
            val constructorTypes = constructors.map { constructor -> constructor.parameterTypes.map { it.name } }
            val forbidden = constructorTypes.flatten().mapNotNull { FORBIDDEN_AUTHORITY_TYPES[it] }
            val callbacks = constructorTypes.flatten().filter { it.startsWith("kotlin.jvm.functions.Function") }
            violations += "constructor count=${constructors.size}, expected=1; parameter types=$constructorTypes; " +
                "forbidden authority types=$forbidden; forbidden callback types=$callbacks"
        }
        constructors.forEachIndexed { index, constructor ->
            if (constructor.parameterTypes.toList() != expectedConstructor) {
                violations += "constructor[$index] types=${constructor.parameterTypes.toList()}, expected=$expectedConstructor"
            }
            val directForbidden = constructor.parameterTypes.mapNotNull { type ->
                type.authorityLabel()
            } + constructor.genericParameterTypes.flatMap { it.authorityNames() + it.deferredNames() }
            if (directForbidden.isNotEmpty()) {
                violations += "constructor[$index] forbidden types=$directForbidden"
            }
        }
        val instanceFields = reader.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name to it.type.name }
        val expectedInstanceFields = listOf(
            "frontmatterParser" to FrontmatterParser::class.java.name,
            "absence" to AbsenceClassifier::class.java.name,
            "availability" to RootAvailability::class.java.name,
            "rootLoss" to RootLossClassifier::class.java.name,
        )
        val sortedInstanceFields = instanceFields.sortedWith(compareBy({ it.first }, { it.second }))
        val sortedExpectedInstanceFields = expectedInstanceFields.sortedWith(compareBy({ it.first }, { it.second }))
        if (sortedInstanceFields != sortedExpectedInstanceFields) {
            violations += "instance fields=$instanceFields, expected=$expectedInstanceFields"
        }
        val expectedReaderStaticFields = mapOf(
            "Companion" to "com.plainbase.domain.service.IndexSourceReader\$Companion",
            "logger" to "io.github.oshai.kotlinlogging.KLogger",
        )
        val actualReaderStaticFields = reader.declaredFields.filter { Modifier.isStatic(it.modifiers) }
            .associate { it.name to it.type.name }
        if (actualReaderStaticFields != expectedReaderStaticFields) {
            violations += "reader static fields=$actualReaderStaticFields, expected=$expectedReaderStaticFields"
        }
        val expectedReaderStaticMethods = setOf(
            "read\$lambda\$0(java.lang.String):java.lang.Object",
            "read\$lambda\$3\$0(java.lang.String):java.lang.Object",
            "skipOnLiveFailure\$lambda\$1(com.plainbase.domain.root.Root,java.lang.String,java.lang.Exception):java.lang.Object",
            "skipAndCarry_mH5LiJY\$lambda\$0(java.lang.String,java.lang.String):java.lang.Object",
            "scan\$lambda\$2\$0(com.plainbase.domain.content.ContentFile,java.lang.String):java.lang.Object",
            "scan\$lambda\$2\$1(com.plainbase.domain.content.ContentFile,java.lang.String):java.lang.Object",
        )
        val actualReaderStaticMethods = reader.declaredMethods.filter { Modifier.isStatic(it.modifiers) }
            .map { method ->
                "${method.name}(${method.parameterTypes.joinToString(",") { it.name }}):${method.returnType.name}"
            }.toSet()
        if (actualReaderStaticMethods != expectedReaderStaticMethods) {
            violations += "reader static methods=$actualReaderStaticMethods, expected=$expectedReaderStaticMethods"
        }
        val companion = runCatching { Class.forName("com.plainbase.domain.service.IndexSourceReader\$Companion") }.getOrNull()
        if (companion == null) {
            violations += "reader companion class is missing"
        } else {
            val companionFields = companion.declaredFields.map { it.name to it.type.name }
            if (companionFields.isNotEmpty()) violations += "companion instance/static fields=$companionFields"
            val companionOperations = companion.declaredMethods.filterNot { Modifier.isPrivate(it.modifiers) }
                .map { it.name }
            if (companionOperations.isNotEmpty()) violations += "companion public operations=$companionOperations"
        }
        val instanceMethods = reader.declaredMethods.filter { !Modifier.isStatic(it.modifiers) && !Modifier.isPrivate(it.modifiers) }
        if (instanceMethods.map { it.name } != listOf("read")) {
            violations += "externally callable instance methods=${instanceMethods.map { it.name }}, expected=[read]"
        }
        val readMethods = instanceMethods.filter { it.name == "read" }
        if (readMethods.size == 1) {
            val read = readMethods.single()
            val expectedReadParameters = listOf(Root::class.java, ContentStore::class.java, HistoryProvider::class.java)
            if (read.returnType != Class.forName("com.plainbase.domain.service.SourceScan") ||
                read.parameterTypes.toList() != expectedReadParameters
            ) {
                val expectedReadType = Class.forName("com.plainbase.domain.service.SourceScan").name
                val expectedReadArguments = expectedReadParameters.joinToString { it.name }
                violations += "read signature=${read.returnType.name}(${read.parameterTypes.joinToString { it.name }}), " +
                    "expected=$expectedReadType($expectedReadArguments)"
            }
            val methodForbidden = (listOf(read.genericReturnType) + read.genericParameterTypes.toList())
                .flatMap { it.authorityNames() + it.deferredNames() }
            if (methodForbidden.isNotEmpty()) violations += "method read forbidden types=$methodForbidden"
        }
        val source = Files.readString(serviceRoot.resolve("IndexSourceReader.kt"))
        val sourceUnit = KotlinSourceUnit("IndexSourceReader.kt", source)
        if (!source.normalizedWhitespace().contains("KotlinLogging.logger(\"com.plainbase.domain.service.IndexBuilder\")")) {
            violations += "reader source missing required signature=KotlinLogging.logger(\"com.plainbase.domain.service.IndexBuilder\")"
        }
        val expectedConstructorTokens = listOf(
            "internal", "class", "IndexSourceReader", "(",
            "private", "val", "frontmatterParser", ":", "FrontmatterParser", ",",
            "private", "val", "absence", ":", "AbsenceClassifier", ",",
            "private", "val", "availability", ":", "RootAvailability", ",",
            "private", "val", "rootLoss", ":", "RootLossClassifier", ",", ")",
        )
        val expectedReadTokens = listOf(
            "fun", "read", "(", "root", ":", "Root", ",", "store", ":", "ContentStore", ",",
            "history", ":", "HistoryProvider", ")", ":", "SourceScan", "?",
        )
        violations += sourceUnit.readerSourceSchemaViolations(expectedConstructorTokens, expectedReadTokens)

        val readerSchemaFixture = """
            package com.plainbase.domain.service
            internal class IndexSourceReader(
                private val frontmatterParser: FrontmatterParser,
                private val absence: AbsenceClassifier,
                private val availability: RootAvailability,
                private val rootLoss: RootLossClassifier,
            ) {
                private fun helperRead(): Unit = Unit
                fun read(root: Root, store: ContentStore, history: HistoryProvider): SourceScan? = TODO()
            }
        """.trimIndent()
        val nullableConstructorCases = listOf(
            "frontmatterParser" to "FrontmatterParser",
            "absence" to "AbsenceClassifier",
            "availability" to "RootAvailability",
            "rootLoss" to "RootLossClassifier",
        )
        fun assertSingleSchemaMismatch(schemaViolations: List<String>, expectedPrefix: String) {
            schemaViolations.size shouldBe 1
            schemaViolations.single().startsWith(expectedPrefix) shouldBe true
        }
        nullableConstructorCases.forEach { (name, type) ->
            val nullable = readerSchemaFixture.replace(
                "private val $name: $type",
                "private val $name: $type?",
            )
            val nullableViolations = KotlinSourceUnit("nullable-$name.kt", nullable)
                .readerSourceSchemaViolations(expectedConstructorTokens, expectedReadTokens)
            withClue("nullable constructor slot $name must be rejected: $nullableViolations") {
                assertSingleSchemaMismatch(nullableViolations, "reader constructor tokens=")
            }
        }
        val nullableReadParameterCases = listOf(
            "root" to "Root",
            "store" to "ContentStore",
            "history" to "HistoryProvider",
        )
        nullableReadParameterCases.forEach { (name, type) ->
            val nullable = readerSchemaFixture.replace(
                "$name: $type",
                "$name: $type?",
            )
            val nullableViolations = KotlinSourceUnit("nullable-read-$name.kt", nullable)
                .readerSourceSchemaViolations(expectedConstructorTokens, expectedReadTokens)
            withClue("nullable read parameter $name must be rejected: $nullableViolations") {
                assertSingleSchemaMismatch(nullableViolations, "reader read tokens=")
            }
        }
        val reordered = readerSchemaFixture.replace(
            "private val frontmatterParser: FrontmatterParser,\n    private val absence: AbsenceClassifier",
            "private val absence: AbsenceClassifier,\n    private val frontmatterParser: FrontmatterParser",
        )
        withClue("reordered constructor dependencies must be rejected") {
            val reorderedViolations = KotlinSourceUnit("reordered.kt", reordered)
                .readerSourceSchemaViolations(expectedConstructorTokens, expectedReadTokens)
            assertSingleSchemaMismatch(reorderedViolations, "reader constructor tokens=")
        }
        val wrongReadNullability = readerSchemaFixture.replace(
            "fun read(root: Root, store: ContentStore, history: HistoryProvider): SourceScan?",
            "fun read(root: Root, store: ContentStore, history: HistoryProvider): SourceScan",
        )
        withClue("non-null read result must be rejected") {
            val wrongReadViolations = KotlinSourceUnit("wrong-read-nullability.kt", wrongReadNullability)
                .readerSourceSchemaViolations(expectedConstructorTokens, expectedReadTokens)
            assertSingleSchemaMismatch(wrongReadViolations, "reader read tokens=")
        }
        val wrongReadParameter = readerSchemaFixture.replace(
            "store: ContentStore",
            "store: String",
        )
        withClue("wrong read parameter type must be rejected") {
            val wrongReadViolations = KotlinSourceUnit("wrong-read-parameter.kt", wrongReadParameter)
                .readerSourceSchemaViolations(expectedConstructorTokens, expectedReadTokens)
            assertSingleSchemaMismatch(wrongReadViolations, "reader read tokens=")
        }
        val commentAndDecoyFixture = """
            package com.plainbase.domain.service
            val schemaDecoy = "private val frontmatterParser: FrontmatterParser? fun read(root: Root, store: String, history: HistoryProvider): SourceScan"
            private class Sibling(private val frontmatterParser: FrontmatterParser?) {
                private fun read(root: Root, store: String, history: HistoryProvider): SourceScan = TODO()
            }
        """.trimIndent() + "\n" + readerSchemaFixture
        withClue("comments, whitespace, strings, siblings, and private helpers must not satisfy the reader schema") {
            val decorated = commentAndDecoyFixture
                .replace(
                    "private val frontmatterParser: FrontmatterParser,",
                    "private /* dependency */ val frontmatterParser:\n        FrontmatterParser,",
                )
                .replace(
                    "fun read(root: Root, store: ContentStore, history: HistoryProvider): SourceScan?",
                    "fun /* direct member */ read(\n        root: Root, store: ContentStore, history: HistoryProvider\n    ): SourceScan?",
                )
            KotlinSourceUnit("comments-and-decoys.kt", decorated)
                .readerSourceSchemaViolations(expectedConstructorTokens, expectedReadTokens)
                .shouldBeEmpty()
        }
        val oldCorrectSchemaDecoys = """
            package com.plainbase.domain.service
            /*
                internal class IndexSourceReader(
                    private val frontmatterParser: FrontmatterParser,
                    private val absence: AbsenceClassifier,
                    private val availability: RootAvailability,
                    private val rootLoss: RootLossClassifier,
                )
                fun read(root: Root, store: ContentStore, history: HistoryProvider): SourceScan?
            */
            val oldCorrectSchemaString = "internal class IndexSourceReader(private val frontmatterParser: FrontmatterParser, " +
                "private val absence: AbsenceClassifier, private val availability: RootAvailability, " +
                "private val rootLoss: RootLossClassifier) fun read(root: Root, store: ContentStore, " +
                "history: HistoryProvider): SourceScan?"
        """.trimIndent()
        val wrongRealSchemaWithDecoys = oldCorrectSchemaDecoys + "\n" + wrongReadParameter
        withClue("old correct comment and string signatures must not hide the wrong real reader schema") {
            val wrongRealViolations = KotlinSourceUnit("wrong-real-schema-with-decoys.kt", wrongRealSchemaWithDecoys)
                .readerSourceSchemaViolations(expectedConstructorTokens, expectedReadTokens)
            assertSingleSchemaMismatch(wrongRealViolations, "reader read tokens=")
        }
        sourcePublicSignatureAuthorityViolations("IndexSourceReader.kt", Files.readString(serviceRoot.resolve("IndexSourceReader.kt")))
            .shouldBeEmpty()
        listOf(
            "package com.other\nfun publicApi(value: com.plainbase.domain.root.ObservationId) = value",
            "package com.other\ntypealias Erased = com.plainbase.domain.root.ObservationId\nfun publicApi(value: Erased) = value",
            "package com.other\nclass Holder { companion object { val publicBinding: com.plainbase.domain.root.BindingEpoch? = null } }",
        ).forEach { snippet ->
            val snippetViolations = sourcePublicSignatureAuthorityViolations("snippet.kt", snippet)
            withClue("public signature snippet: $snippet -> $snippetViolations") {
                snippetViolations.isNotEmpty() shouldBe true
            }
        }
        sourcePublicSignatureAuthorityViolations(
            "snippet.kt",
            "package com.other\nprivate typealias Erased = com.plainbase.domain.root.ObservationId\n" +
                "private class Holder(private val value: Erased)",
        ).shouldBeEmpty()
        withClue("reader direct surface diagnostics: ${violations.joinToString("; ")}") {
            violations.shouldBeEmpty()
        }
        withClue("IndexBuilder retains its reviewed coordinator field surface plus one reader") {
            val builder = Class.forName("com.plainbase.domain.service.IndexBuilder")
            val fields = builder.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.associate { it.name to it.type.name }
            fields shouldBe EXPECTED_INDEX_BUILDER_FIELDS
        }
    }

    test("identity helper direct surface") {
        val helper = Class.forName("com.plainbase.domain.service.IndexIdentityAssignments")
        val constructors = helper.declaredConstructors.toList()
        val expectedConstructor = listOf(
            com.plainbase.domain.repository.IdMapRepository::class.java,
            PageIdentityService::class.java,
            FrontmatterPatcher::class.java,
        )
        val violations = mutableListOf<String>()
        if (constructors.size != 1) {
            violations += "constructor count=${constructors.size}, expected=1"
        }
        constructors.forEachIndexed { index, constructor ->
            if (constructor.parameterTypes.toList() != expectedConstructor) {
                violations += "constructor[$index] types=${constructor.parameterTypes.toList()}, expected=$expectedConstructor"
            }
            val directForbidden = constructor.parameterTypes.drop(1).mapNotNull { type -> type.authorityLabel() } +
                constructor.genericParameterTypes.drop(1).flatMap { it.authorityNames() + it.deferredNames() }
            if (directForbidden.isNotEmpty()) violations += "constructor[$index] forbidden types=$directForbidden"
        }
        val instanceFields = helper.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name to it.type.name }
        val expectedInstanceFields = listOf(
            "idMap" to com.plainbase.domain.repository.IdMapRepository::class.java.name,
            "identity" to PageIdentityService::class.java.name,
            "patcher" to FrontmatterPatcher::class.java.name,
        )
        val sortedInstanceFields = instanceFields.sortedWith(compareBy({ it.first }, { it.second }))
        val sortedExpectedInstanceFields = expectedInstanceFields.sortedWith(compareBy({ it.first }, { it.second }))
        if (sortedInstanceFields != sortedExpectedInstanceFields) {
            violations += "instance fields=$instanceFields, expected=$expectedInstanceFields"
        }
        if (helper.declaredFields.any { Modifier.isStatic(it.modifiers) }) {
            violations += "static fields=${helper.declaredFields.filter { Modifier.isStatic(it.modifiers) }.map { it.name }}"
        }
        val expectedStaticMethods = setOf(
            "resolveIdentities\$lambda\$1(java.util.HashMap,com.plainbase.domain.root.RootedPath," +
                "com.plainbase.domain.service.IndexIdentityAssignments,java.util.Map,java.util.Set,java.util.Set," +
                "com.plainbase.domain.repository.Supersession,com.plainbase.domain.page.PageId):" +
                "com.plainbase.domain.root.RootedPath",
            "access\$getPatcher\$p(com.plainbase.domain.service.IndexIdentityAssignments):" +
                "com.plainbase.domain.service.FrontmatterPatcher",
        )
        val actualStaticMethods = helper.declaredMethods.filter { Modifier.isStatic(it.modifiers) }.map { method ->
            "${method.name}(${method.parameterTypes.joinToString(",") { it.name }}):${method.returnType.name}"
        }.toSet()
        if (actualStaticMethods != expectedStaticMethods) {
            violations += "static methods=$actualStaticMethods, expected=$expectedStaticMethods"
        }
        val instanceMethods = helper.declaredMethods.filter { !Modifier.isStatic(it.modifiers) && !Modifier.isPrivate(it.modifiers) }
        if (instanceMethods.map { it.name } != listOf("resolveIdentities")) {
            violations += "externally callable instance methods=${instanceMethods.map { it.name }}, expected=[resolveIdentities]"
        }
        val resolve = instanceMethods.singleOrNull { it.name == "resolveIdentities" }
        if (resolve != null) {
            val expectedParameters = listOf(
                "java.util.List<com.plainbase.domain.service.SourceScan>",
                "java.util.Map<com.plainbase.domain.root.RootedPath,com.plainbase.domain.root.Witness>",
                "java.util.Set<com.plainbase.domain.root.RootName>",
                "java.util.Set<com.plainbase.domain.root.RootName>",
                "java.util.List<com.plainbase.domain.model.IdentityIssue>",
            )
            if (resolve.parameterTypes.toList() != listOf(
                    Class.forName("java.util.List"),
                    Class.forName("java.util.Map"),
                    Class.forName("java.util.Set"),
                    Class.forName("java.util.Set"),
                    Class.forName("java.util.List"),
                )
            ) {
                violations += "resolve parameters=${resolve.parameterTypes.toList()}"
            }
            if (resolve.returnType != Class.forName("java.util.Map")) {
                violations += "resolve return=${resolve.returnType.name}, expected=java.util.Map"
            }
            if (resolve.genericParameterTypes.map { it.render() } != expectedParameters ||
                resolve.genericReturnType.render() !=
                "java.util.Map<com.plainbase.domain.root.RootedPath,com.plainbase.domain.service.Identity>"
            ) {
                violations += "resolve generic signature=${resolve.genericParameterTypes.map { it.render() }} -> " +
                    "${resolve.genericReturnType.render()}"
            }
        }
        withClue("identity helper direct surface: ${violations.joinToString("; ")}") {
            violations.shouldBeEmpty()
        }
        helper.enclosingClass shouldBe null
        helper.declaredClasses.toList() shouldBe emptyList()

        val source = Files.readString(serviceRoot.resolve("IndexIdentityAssignments.kt"))
        sourcePublicSignatureAuthorityViolations("IndexIdentityAssignments.kt", source).shouldBeEmpty()
        val sourceUnit = KotlinSourceUnit("IndexIdentityAssignments.kt", source)
        val expectedConstructorTokens = listOf(
            "internal", "class", "IndexIdentityAssignments", "(",
            "private", "val", "idMap", ":", "IdMapRepository", ",",
            "private", "val", "identity", ":", "PageIdentityService", ",",
            "private", "val", "patcher", ":", "FrontmatterPatcher", ",", ")",
        )
        val expectedResolveTokens = listOf(
            "fun", "resolveIdentities", "(", "scans", ":", "List", "<", "SourceScan", ">", ",",
            "witnessed", ":", "Map", "<", "RootedPath", ",", "Witness", ">", ",",
            "scannedRoots", ":", "Set", "<", "RootName", ">", ",",
            "registeredRoots", ":", "Set", "<", "RootName", ">", ",",
            "raised", ":", "MutableList", "<", "IdentityIssue", ">", ",", ")", ":", "Map", "<",
            "RootedPath", ",", "Identity", ">",
        )
        sourceUnit.readerSourceSchemaViolations(
            expectedConstructorTokens,
            expectedResolveTokens,
            className = "IndexIdentityAssignments",
            operationName = "resolveIdentities",
            diagnosticLabel = "identity",
        ).shouldBeEmpty()

        val identitySchemaFixture = """
            package com.plainbase.domain.service
            internal class IndexIdentityAssignments(
                private val idMap: IdMapRepository,
                private val identity: PageIdentityService,
                private val patcher: FrontmatterPatcher,
            ) {
                fun resolveIdentities(
                    scans: List<SourceScan>,
                    witnessed: Map<RootedPath, Witness>,
                    scannedRoots: Set<RootName>,
                    registeredRoots: Set<RootName>,
                    raised: MutableList<IdentityIssue>,
                ): Map<RootedPath, Identity> = TODO()
            }
        """.trimIndent()
        KotlinSourceUnit("identity-schema-positive.kt", identitySchemaFixture).readerSourceSchemaViolations(
            expectedConstructorTokens,
            expectedResolveTokens,
            className = "IndexIdentityAssignments",
            operationName = "resolveIdentities",
            diagnosticLabel = "identity",
        ).shouldBeEmpty()
        fun assertIdentitySchemaMismatch(schemaViolations: List<String>, expectedPrefix: String) {
            schemaViolations.size shouldBe 1
            schemaViolations.single().startsWith(expectedPrefix) shouldBe true
        }
        listOf(
            "idMap" to "IdMapRepository",
            "identity" to "PageIdentityService",
            "patcher" to "FrontmatterPatcher",
        ).forEach { (name, type) ->
            val nullable = identitySchemaFixture.replace("private val $name: $type", "private val $name: $type?")
            assertIdentitySchemaMismatch(
                KotlinSourceUnit("identity-nullable-$name.kt", nullable).readerSourceSchemaViolations(
                    expectedConstructorTokens,
                    expectedResolveTokens,
                    className = "IndexIdentityAssignments",
                    operationName = "resolveIdentities",
                    diagnosticLabel = "identity",
                ),
                "identity constructor tokens=",
            )
        }
        val missingRegistered = identitySchemaFixture.replace("registeredRoots: Set<RootName>,", "")
        assertIdentitySchemaMismatch(
            KotlinSourceUnit("identity-missing-registered.kt", missingRegistered).readerSourceSchemaViolations(
                expectedConstructorTokens,
                expectedResolveTokens,
                className = "IndexIdentityAssignments",
                operationName = "resolveIdentities",
                diagnosticLabel = "identity",
            ),
            "identity resolveIdentities tokens=",
        )
        val swappedRegistered = identitySchemaFixture
            .replace("scannedRoots", "__SCANNED_ROOTS__")
            .replace("registeredRoots", "scannedRoots")
            .replace("__SCANNED_ROOTS__", "registeredRoots")
        assertIdentitySchemaMismatch(
            KotlinSourceUnit("identity-swapped-registered.kt", swappedRegistered).readerSourceSchemaViolations(
                expectedConstructorTokens,
                expectedResolveTokens,
                className = "IndexIdentityAssignments",
                operationName = "resolveIdentities",
                diagnosticLabel = "identity",
            ),
            "identity resolveIdentities tokens=",
        )
        listOf(
            "nullable-result" to "Map<RootedPath, Identity>?",
            "wrong-result" to "Map<RootedPath, String>",
        ).forEach { (label, resultType) ->
            val wrongResult = identitySchemaFixture.replace("Map<RootedPath, Identity>", resultType)
            assertIdentitySchemaMismatch(
                KotlinSourceUnit("identity-$label.kt", wrongResult).readerSourceSchemaViolations(
                    expectedConstructorTokens,
                    expectedResolveTokens,
                    className = "IndexIdentityAssignments",
                    operationName = "resolveIdentities",
                    diagnosticLabel = "identity",
                ),
                "identity resolveIdentities tokens=",
            )
        }
        val extraCallback = identitySchemaFixture.replace(
            "private val patcher: FrontmatterPatcher,",
            "private val patcher: FrontmatterPatcher,\n        private val callback: () -> Unit,",
        )
        assertIdentitySchemaMismatch(
            KotlinSourceUnit("identity-extra-callback.kt", extraCallback).readerSourceSchemaViolations(
                expectedConstructorTokens,
                expectedResolveTokens,
                className = "IndexIdentityAssignments",
                operationName = "resolveIdentities",
                diagnosticLabel = "identity",
            ),
            "identity constructor tokens=",
        )
    }

    test("snapshot assembler direct surface") {
        val assembler = Class.forName("com.plainbase.domain.service.IndexSnapshotAssembler")
        val expectedConstructor = listOf(Function1::class.java, CitationFactory::class.java)
        val expectedConstructorGeneric = listOf(
            "kotlin.jvm.functions.Function1<? super com.plainbase.domain.page.PageIndexView,? extends " +
                "com.plainbase.domain.render.MarkdownRenderer>",
            "com.plainbase.domain.service.CitationFactory",
        )
        val expectedFields = listOf(
            "rendererFactory" to Function1::class.java.name,
            "citations" to CitationFactory::class.java.name,
        )
        val expectedFieldGeneric = mapOf(
            "rendererFactory" to "kotlin.jvm.functions.Function1<com.plainbase.domain.page.PageIndexView," +
                "com.plainbase.domain.render.MarkdownRenderer>",
            "citations" to "com.plainbase.domain.service.CitationFactory",
        )
        val expectedRendererFactoryConstructorGeneric = expectedConstructorGeneric.first()
        val expectedRendererFactoryFieldGeneric = expectedFieldGeneric.getValue("rendererFactory")
        val violations = mutableListOf<String>()
        val constructors = assembler.declaredConstructors.toList()
        if (constructors.size != 1) violations += "constructor count=${constructors.size}, expected=1"
        constructors.forEachIndexed { index, constructor ->
            if (constructor.parameterTypes.toList() != expectedConstructor) {
                violations += "constructor[$index] types=${constructor.parameterTypes.toList()}, expected=$expectedConstructor"
            }
            if (constructor.genericParameterTypes.map { it.render() } != expectedConstructorGeneric) {
                violations += "constructor[$index] generic types=${constructor.genericParameterTypes.map { it.render() }}, " +
                    "expected=$expectedConstructorGeneric"
            }
            val forbidden = constructor.genericParameterTypes.flatMapIndexed { parameterIndex, type ->
                val isExactRendererFactory =
                    parameterIndex == 0 && type.render() == expectedRendererFactoryConstructorGeneric
                type.authorityNames(forbiddenTypes = ASSEMBLER_FORBIDDEN_AUTHORITY_TYPES) +
                    type.deferredNames().let { deferred ->
                        if (isExactRendererFactory) deferred - "Function" else deferred
                    }
            }
            if (forbidden.isNotEmpty()) violations += "assembler constructor[$index] forbidden types=$forbidden"
        }
        val fields = assembler.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        val actualFields = fields.map { it.name to it.type.name }.sortedWith(compareBy({ it.first }, { it.second }))
        val sortedExpectedFields = expectedFields.sortedWith(compareBy({ it.first }, { it.second }))
        if (actualFields != sortedExpectedFields) {
            violations += "instance fields=$actualFields, expected=$sortedExpectedFields"
        }
        if (fields.any { !Modifier.isFinal(it.modifiers) }) violations += "non-final fields=${fields.map { it.name }}"
        if (assembler.declaredFields.any { Modifier.isStatic(it.modifiers) }) {
            violations += "static fields=${assembler.declaredFields.filter { Modifier.isStatic(it.modifiers) }.map { it.name }}"
        }
        fields.forEach { field ->
            val expected = expectedFieldGeneric[field.name]
            if (field.genericType.render() != expected) {
                violations += "field ${field.name} generic type=${field.genericType.render()}, expected=$expected"
            }
            val forbidden = field.genericType.authorityNames(forbiddenTypes = ASSEMBLER_FORBIDDEN_AUTHORITY_TYPES) +
                field.genericType.deferredNames().let { deferred ->
                    if (field.name == "rendererFactory" && field.genericType.render() == expectedRendererFactoryFieldGeneric) {
                        deferred - "Function"
                    } else {
                        deferred
                    }
                }
            if (forbidden.isNotEmpty()) violations += "assembler field ${field.name} forbidden types=$forbidden"
        }
        if (assembler.enclosingClass != null) violations += "enclosing class=${assembler.enclosingClass.name}"
        if (assembler.declaredClasses.isNotEmpty()) violations += "nested classes=${assembler.declaredClasses.toList()}"

        val actualStaticMethods = assembler.declaredMethods.filter { Modifier.isStatic(it.modifiers) }
            .map { method ->
                "${method.name}(${method.parameterTypes.joinToString(",") { it.name }}):${method.returnType.name}"
            }.toSet()
        if (actualStaticMethods != emptySet<String>()) {
            violations += "static methods=$actualStaticMethods, expected=[]"
        }

        val instanceMethods = assembler.declaredMethods.filter { !Modifier.isStatic(it.modifiers) && !Modifier.isPrivate(it.modifiers) }
        if (instanceMethods.map { it.name } != listOf("assemble")) {
            violations += "externally callable instance methods=${instanceMethods.map { it.name }}, expected=[assemble]"
        }
        instanceMethods.singleOrNull { it.name == "assemble" }?.let { assemble ->
            val expectedParameters = listOf(
                "java.util.List<com.plainbase.domain.service.SourceScan>",
                "java.util.Map<com.plainbase.domain.root.RootedPath,com.plainbase.domain.service.Identity>",
                "java.util.List<com.plainbase.domain.root.RootName>",
                "com.plainbase.domain.page.PageIndex",
            )
            if (assemble.returnType != PageIndex::class.java) {
                violations += "assemble return type=${assemble.returnType.name}, expected=${PageIndex::class.java.name}"
            }
            if (assemble.parameterTypes.toList() != listOf(List::class.java, Map::class.java, List::class.java, PageIndex::class.java)) {
                violations += "assemble parameter types=${assemble.parameterTypes.toList()}"
            }
            if (assemble.genericParameterTypes.map { it.render() } != expectedParameters) {
                violations += "assemble generic parameters=${assemble.genericParameterTypes.map { it.render() }}, " +
                    "expected=$expectedParameters"
            }
            if (assemble.genericReturnType.render() != "com.plainbase.domain.page.PageIndex") {
                violations += "assemble generic return=${assemble.genericReturnType.render()}"
            }
            val deferredOrAuthority = assemble.genericReturnType.deferredNames() +
                assemble.genericReturnType.authorityNames(forbiddenTypes = ASSEMBLER_FORBIDDEN_AUTHORITY_TYPES)
            if (deferredOrAuthority.isNotEmpty()) violations += "assembler assemble result types=$deferredOrAuthority"
        }
        withClue("snapshot assembler direct surface: ${violations.joinToString("; ")}") {
            violations.shouldBeEmpty()
        }

        val sourceUnit = KotlinSourceUnit("IndexSnapshotAssembler.kt", Files.readString(serviceRoot.resolve("IndexSnapshotAssembler.kt")))
        val expectedConstructorTokens = listOf(
            "internal", "class", "IndexSnapshotAssembler", "(",
            "private", "val", "rendererFactory", ":", "(", "PageIndexView", ")", "-", ">", "MarkdownRenderer", ",",
            "private", "val", "citations", ":", "CitationFactory", ",", ")",
        )
        val expectedAssembleTokens = listOf(
            "fun", "assemble", "(", "scans", ":", "List", "<", "SourceScan", ">", ",",
            "identities", ":", "Map", "<", "RootedPath", ",", "Identity", ">", ",",
            "roots", ":", "List", "<", "RootName", ">", ",",
            "previous", ":", "PageIndex", ",", ")", ":", "PageIndex",
        )
        sourceUnit.readerSourceSchemaViolations(
            expectedConstructorTokens,
            expectedAssembleTokens,
            className = "IndexSnapshotAssembler",
            operationName = "assemble",
            diagnosticLabel = "assembler",
        ).shouldBeEmpty()
        sourcePublicSignatureAuthorityViolations(
            "IndexSnapshotAssembler.kt",
            Files.readString(serviceRoot.resolve("IndexSnapshotAssembler.kt")),
            authorityFqns = ASSEMBLER_FORBIDDEN_AUTHORITY_TYPES.sourceAuthorityFqns(),
        ).shouldBeEmpty()

        val sourceFixture = """
            package com.plainbase.domain.service
            internal class IndexSnapshotAssembler(
                private val rendererFactory: (PageIndexView) -> MarkdownRenderer,
                private val citations: CitationFactory,
            ) {
                fun assemble(
                    scans: List<SourceScan>,
                    identities: Map<RootedPath, Identity>,
                    roots: List<RootName>,
                    previous: PageIndex,
                ): PageIndex = TODO()
            }
        """.trimIndent()
        fun assertAssemblerSchemaMismatch(source: String, expectedPrefix: String) {
            val mismatches = KotlinSourceUnit("assembler-fixture.kt", source).readerSourceSchemaViolations(
                expectedConstructorTokens,
                expectedAssembleTokens,
                className = "IndexSnapshotAssembler",
                operationName = "assemble",
                diagnosticLabel = "assembler",
            )
            mismatches.size shouldBe 1
            mismatches.single().startsWith(expectedPrefix) shouldBe true
        }
        KotlinSourceUnit("assembler-positive.kt", sourceFixture).readerSourceSchemaViolations(
            expectedConstructorTokens,
            expectedAssembleTokens,
            className = "IndexSnapshotAssembler",
            operationName = "assemble",
            diagnosticLabel = "assembler",
        ).shouldBeEmpty()
        listOf(
            "rendererFactory" to "(PageIndexView) -> MarkdownRenderer?",
            "citations" to "CitationFactory?",
        ).forEach { (name, type) ->
            assertAssemblerSchemaMismatch(
                sourceFixture.replace(
                    if (name == "rendererFactory") "(PageIndexView) -> MarkdownRenderer" else "CitationFactory",
                    type,
                ),
                "assembler constructor tokens=",
            )
        }
        assertAssemblerSchemaMismatch(
            sourceFixture.replace(
                "private val rendererFactory: (PageIndexView) -> MarkdownRenderer",
                "private val rendererFactory: ((PageIndexView) -> MarkdownRenderer)?",
            ),
            "assembler constructor tokens=",
        )
        assertAssemblerSchemaMismatch(
            sourceFixture.replace("(PageIndexView) -> MarkdownRenderer", "(PageIndex) -> MarkdownRenderer"),
            "assembler constructor tokens=",
        )
        assertAssemblerSchemaMismatch(
            sourceFixture.replace("(PageIndexView) -> MarkdownRenderer", "(PageIndexView) -> PageIndex"),
            "assembler constructor tokens=",
        )
        assertAssemblerSchemaMismatch(
            sourceFixture.replace(
                "private val citations: CitationFactory,",
                "private val callback: () -> Unit,\n        private val citations: CitationFactory,",
            ),
            "assembler constructor tokens=",
        )
        assertAssemblerSchemaMismatch(
            sourceFixture.replace(
                "private val rendererFactory: (PageIndexView) -> MarkdownRenderer,\n    private val citations: CitationFactory,",
                "private val citations: CitationFactory,\n    private val rendererFactory: (PageIndexView) -> MarkdownRenderer,",
            ),
            "assembler constructor tokens=",
        )
        assertAssemblerSchemaMismatch(
            sourceFixture.replace(": PageIndex = TODO()", ": PageIndex? = TODO()"),
            "assembler assemble tokens=",
        )
        assertAssemblerSchemaMismatch(
            sourceFixture.replace(": PageIndex = TODO()", ": Lazy<PageIndex> = TODO()"),
            "assembler assemble tokens=",
        )
    }

    test("passive schema") {
        val expected = mapOf(
            "com.plainbase.domain.service.Draft" to listOf(
                "file" to "com.plainbase.domain.content.ContentFile",
                "bytes" to "[B",
                "frontmatter" to "com.plainbase.domain.page.Frontmatter",
            ),
            "com.plainbase.domain.service.SourceScan" to listOf(
                "root" to "java.lang.String",
                "drafts" to "java.util.List",
                "folders" to "java.util.List",
                "assets" to "java.util.Set",
                "urls" to "com.plainbase.domain.service.CanonicalUrlBuilder\$Result",
                "commits" to "java.util.Map",
                "issues" to "java.util.List",
                "complete" to "boolean",
                "pageReadsComplete" to "boolean",
                "unread" to "java.util.Set",
            ),
            "com.plainbase.domain.service.Identity" to listOf(
                "id" to "com.plainbase.domain.page.PageId",
                "materialized" to "boolean",
            ),
        )
        val expectedGeneric = mapOf(
            "com.plainbase.domain.service.Draft" to listOf(
                "file" to "com.plainbase.domain.content.ContentFile",
                "bytes" to "[B",
                "frontmatter" to "com.plainbase.domain.page.Frontmatter",
            ),
            "com.plainbase.domain.service.SourceScan" to listOf(
                "root" to "java.lang.String",
                "drafts" to "java.util.List<com.plainbase.domain.service.Draft>",
                "folders" to "java.util.List<com.plainbase.domain.content.ContentFolder>",
                "assets" to "java.util.Set<com.plainbase.domain.content.TreePath>",
                "urls" to "com.plainbase.domain.service.CanonicalUrlBuilder\$Result",
                "commits" to "java.util.Map<com.plainbase.domain.content.TreePath,com.plainbase.domain.history.Commit>",
                "issues" to "java.util.List<com.plainbase.domain.model.IdentityIssue>",
                "complete" to "boolean",
                "pageReadsComplete" to "boolean",
                "unread" to "java.util.Set<com.plainbase.domain.content.TreePath>",
            ),
            "com.plainbase.domain.service.Identity" to listOf(
                "id" to "com.plainbase.domain.page.PageId",
                "materialized" to "boolean",
            ),
        )
        val forms = mapOf(
            "com.plainbase.domain.service.Draft" to false,
            "com.plainbase.domain.service.SourceScan" to true,
            "com.plainbase.domain.service.Identity" to false,
        )
        expected.forEach { (name, properties) ->
            val type = Class.forName(name)
            val fields = type.declaredFields.toList()
            val instanceFieldList = fields.filterNot { Modifier.isStatic(it.modifiers) }
            withClue("$name property order/types") {
                instanceFieldList.map { it.name to it.type.name } shouldBe properties
            }
            withClue("$name has no static fields") {
                fields.filter { Modifier.isStatic(it.modifiers) }.shouldBeEmpty()
            }
            withClue("$name properties are final vals") {
                instanceFieldList.all { Modifier.isFinal(it.modifiers) } shouldBe true
            }
            withClue("$name generic property schema") {
                instanceFieldList.map { it.name to it.genericType.render() } shouldBe
                    expectedGeneric.getValue(name)
            }
            withClue("$name data-class form") {
                type.isDataClass() shouldBe forms.getValue(name)
            }
            withClue("$name ordinary/data generated methods") {
                val methods = type.declaredMethods.filterNot { Modifier.isStatic(it.modifiers) }
                val expectedGetters = properties.map { (property, _) ->
                    property.replaceFirstChar { character -> character.uppercase() }
                }
                val getters = methods.filter { it.name.startsWith("get") }
                val getterProperties = getters.mapNotNull { method ->
                    expectedGetters.firstOrNull { getter -> method.name == "get$getter" || method.name.startsWith("get$getter-") }
                }
                getterProperties.toSet() shouldBe expectedGetters.toSet()
                getterProperties.size shouldBe expectedGetters.size
                val components = methods.filter { it.name.startsWith("component") }
                val copies = methods.filter { it.name.startsWith("copy") }
                val objectMethods = methods.filter { it.name in setOf("equals", "hashCode", "toString") }
                val unrecognized = methods.filter { method ->
                    method !in getters && method !in components && method !in copies && method !in objectMethods
                }
                unrecognized.map { it.name } shouldBe emptyList()
                if (forms.getValue(name)) {
                    components.size shouldBe properties.size
                    copies.size shouldBe 1
                    objectMethods.map { it.name }.toSet() shouldBe setOf("equals", "hashCode", "toString")
                } else {
                    components shouldBe emptyList()
                    copies shouldBe emptyList()
                    objectMethods shouldBe emptyList()
                }
                val generatedDefaults = type.declaredMethods.filter {
                    Modifier.isStatic(it.modifiers) &&
                        it.name.startsWith("copy") &&
                        it.name.endsWith("\$default")
                }
                generatedDefaults.size shouldBe if (forms.getValue(name)) 1 else 0
                generatedDefaults.forEach { it.isSynthetic shouldBe true }
            }
            withClue("$name constructor mirrors properties") {
                val constructors = type.declaredConstructors.toList()
                val primaryConstructors = constructors.filterNot { it.isSynthetic }
                primaryConstructors.size shouldBe 1
                primaryConstructors.singleOrNull()?.let { constructor ->
                    constructor.parameterTypes.map { it.name } shouldBe properties.map { it.second }
                    val constructorGeneric = expectedGeneric.getValue(name).map { (property, type) ->
                        if (name.endsWith("SourceScan") && property == "issues") {
                            property to "java.util.List<? extends com.plainbase.domain.model.IdentityIssue>"
                        } else {
                            property to type
                        }
                    }
                    constructor.genericParameterTypes.map { it.render() } shouldBe constructorGeneric.map { it.second }
                }
                val generatedConstructors = constructors.filter { it.isSynthetic }
                generatedConstructors.size shouldBe if (name.endsWith("SourceScan")) 1 else 0
                if (name.endsWith("SourceScan")) {
                    generatedConstructors.singleOrNull()?.let { generated ->
                        generated.parameterTypes.dropLast(1).map { it.name } shouldBe properties.map { it.second }
                        generated.parameterTypes.lastOrNull()?.name shouldBe "kotlin.jvm.internal.DefaultConstructorMarker"
                    }
                }
            }
        }
        val inputUnit = KotlinSourceUnit("IndexInputs.kt", Files.readString(serviceRoot.resolve("IndexInputs.kt")))
        val actualTokenSchemas = inputUnit.topLevelDeclarationTokenSequences()
        val expectedTokenSchemas = mapOf(
            "Draft" to listOf(
                "internal", "class", "Draft", "(", "val", "file", ":", "ContentFile", ",", "val", "bytes", ":",
                "ByteArray", ",", "val", "frontmatter", ":", "Frontmatter", ",", ")",
            ),
            "SourceScan" to listOf(
                "internal", "data", "class", "SourceScan", "(", "val", "root", ":", "RootName", ",", "val", "drafts",
                ":", "List", "<", "Draft", ">", ",", "val", "folders", ":", "List", "<", "ContentFolder", ">", ",",
                "val", "assets", ":", "Set", "<", "TreePath", ">", ",", "val", "urls", ":", "CanonicalUrlBuilder", ".",
                "Result", ",", "val", "commits", ":", "Map", "<", "TreePath", ",", "Commit", ">", ",", "val", "issues",
                ":", "List", "<", "IdentityIssue", ">", ",", "val", "complete", ":", "Boolean", ",", "val", "pageReadsComplete",
                ":", "Boolean", ",", "val", "unread", ":", "Set", "<", "TreePath", ">", ",", ")",
            ),
            "Identity" to listOf(
                "internal", "class", "Identity", "(", "val", "id", ":", "PageId", ",", "val", "materialized", ":", "Boolean", ",", ")",
            ),
        )
        withClue("IndexInputs exact tokenized declaration schemas: $actualTokenSchemas") {
            actualTokenSchemas shouldBe expectedTokenSchemas
        }
    }

    test("passive deferred surface") {
        val passiveTypes = listOf(
            Class.forName("com.plainbase.domain.service.Draft"),
            Class.forName("com.plainbase.domain.service.SourceScan"),
            Class.forName("com.plainbase.domain.service.Identity"),
        )
        val passiveSchemaViolations = passiveTypes.flatMap { type -> type.typeGraphViolations() }
        withClue("passive helper signatures must remain eager and authority-free: $passiveSchemaViolations") {
            passiveSchemaViolations.shouldBeEmpty()
        }

        val deferredCases = listOf(
            "lazy" to DeferredFixture::class.java.getMethod("lazyValue"),
            "sequence" to DeferredFixture::class.java.getMethod("sequenceValue"),
            "iterator" to DeferredFixture::class.java.getMethod("iteratorValue"),
            "callback" to DeferredFixture::class.java.getMethod("callbackValue"),
        )
        deferredCases.forEach { (label, method) ->
            val actual = method.genericReturnType.deferredNames()
            val expected = when (label) {
                "lazy" -> setOf("Lazy")
                "sequence" -> setOf("Sequence")
                "iterator" -> setOf("Iterator")
                else -> setOf("Function")
            }
            withClue("deferred fixture $label: $actual") { actual shouldBe expected }
        }
        val authorityCases = listOf(
            "inherited" to GenericAuthorityFixtureImpl::class.java,
            "wildcard" to WildcardFixture::class.java,
            "array" to ArrayFixture::class.java,
        )
        authorityCases.forEach { (label, type) ->
            val actual = type.publicApiAuthorityViolations()
            withClue("authority fixture $label: $actual") { actual shouldBe setOf("IdMapRepository") }
        }
        val combinedSurface = CombinedDeferredAuthorityFixture::class.java.declaredMethods.associate { method ->
            method.name to listOf(method.genericReturnType).flatMap { it.deferredOrAuthorityNames() }.toSet()
        }
        withClue("combined deferred/authority traversal must retain each independent type path: $combinedSurface") {
            combinedSurface shouldBe mapOf(
                "authorityOnly" to setOf("IdMapRepository"),
                "nestedAuthority" to setOf("IdMapRepository"),
                "deferredAndAuthority" to setOf("Sequence", "IdMapRepository"),
            )
        }
        withClue("the authority map contains every exact binary identity") {
            FORBIDDEN_AUTHORITY_TYPES.forEach { (binaryName, label) ->
                val loaded = Class.forName(binaryName, false, this::class.java.classLoader)
                loaded.authorityLabel() shouldBe label
            }
            Class.forName("com.plainbase.domain.service.IndexBuilder\$Published", false, this::class.java.classLoader)
                .authorityNames() shouldBe setOf("Published")
            Class.forName(
                "com.plainbase.domain.service.IndexBuilder\$PublicationListener",
                false,
                this::class.java.classLoader,
            ).authorityNames() shouldBe setOf("PublicationListener")
        }
        withClue("an unrelated nested Published class is safe in both shared classifiers") {
            BenignPublishedFixture::class.java.typeGraphViolations().shouldBeEmpty()
            BenignPublishedFixture::class.java.publicApiAuthorityViolations().shouldBeEmpty()
        }
    }

    test("dependency public authority exposure") {
        val productionIndex = KotlinSourceIndex(
            kotlinFiles(productionRoot).map { path ->
                KotlinSourceUnit(productionRoot.relativize(path).toString(), Files.readString(path))
            },
        )
        val dependencies = listOf(
            FrontmatterParser::class.java,
            AbsenceClassifier::class.java,
            RootAvailability::class.java,
            RootLossClassifier::class.java,
            Root::class.java,
            ContentStore::class.java,
            HistoryProvider::class.java,
            PageIdentityService::class.java,
            FrontmatterPatcher::class.java,
        )
        dependencies.forEach { dependency ->
            val violations = dependency.publicApiAuthorityViolations(productionIndex)
            withClue("public dependency authority leaks from ${dependency.name}: $violations") {
                violations.shouldBeEmpty()
            }
        }
        listOf(
            CitationFactory::class.java,
            MarkdownRenderer::class.java,
            PageIndexView::class.java,
            PageIndex::class.java,
        ).forEach { dependency ->
            val violations = dependency.publicApiAuthorityViolations(
                productionIndex,
                forbiddenTypes = ASSEMBLER_FORBIDDEN_AUTHORITY_TYPES,
                sourceAuthorityFqns = ASSEMBLER_FORBIDDEN_AUTHORITY_TYPES.sourceAuthorityFqns(),
            )
            withClue("assembler dependency authority leaks from ${dependency.name}: $violations") {
                violations.shouldBeEmpty()
            }
        }
        val erasedFixtureSource = KotlinSourceUnit(
            "com/plainbase/domain/service/ErasedSignatureFixture.kt",
            """
            package com.plainbase.domain.service
            class ErasedSignatureFixture {
                fun exposed(
                    input: com.plainbase.domain.root.ObservationId,
                ): String = input.toString()
                companion object {
                    val epoch: com.plainbase.domain.root.BindingEpoch? = null
                }
            }
            """.trimIndent(),
        )
        val erasedFixtureIndex = KotlinSourceIndex(authorityDefinitionUnits() + erasedFixtureSource)
        val erasedFixtureViolations = ErasedSignatureFixture::class.java.publicApiAuthorityViolations(erasedFixtureIndex)
        val erasedFixtureSourceViolations = erasedFixtureViolations.filter { it.startsWith("${erasedFixtureSource.path}:") }
        val observationOffset = erasedFixtureSource.source.lastIndexOf("ObservationId")
        val epochOffset = erasedFixtureSource.source.lastIndexOf("BindingEpoch")
        val expectedErasedFixtureSourceViolations = setOf(
            "${erasedFixtureSource.path}:${erasedFixtureSource.indexed.lineOf(observationOffset)}:" +
                "ErasedSignatureFixture.exposed:com.plainbase.domain.root.ObservationId -> " +
                "com.plainbase.domain.root.ObservationId",
            "${erasedFixtureSource.path}:${erasedFixtureSource.indexed.lineOf(epochOffset)}:" +
                "ErasedSignatureFixture.Companion.epoch:com.plainbase.domain.root.BindingEpoch -> " +
                "com.plainbase.domain.root.BindingEpoch",
        )
        withClue("the dependency audit entry point must connect reached JVM types to exact source signatures: $erasedFixtureViolations") {
            erasedFixtureSourceViolations shouldBe expectedErasedFixtureSourceViolations
        }
        val admittedErasedFixtureViolations = ErasedSignatureFixture::class.java
            .publicApiAuthorityViolations(erasedFixtureIndex, allowForbiddenEntry = true)
        val admittedErasedFixtureSourceViolations = admittedErasedFixtureViolations
            .filter { it.startsWith("${erasedFixtureSource.path}:") }
        withClue("entry-only admission must preserve exact reached source diagnostics: $admittedErasedFixtureViolations") {
            admittedErasedFixtureSourceViolations shouldBe expectedErasedFixtureSourceViolations
        }
        val inferredFixtureSource = KotlinSourceUnit(
            "com/plainbase/domain/service/InferredErasedSignatureFixture.kt",
            """
            package com.plainbase.domain.service
            import com.plainbase.domain.root.ObservationId as Oid
            import com.plainbase.domain.root.BindingEpoch
            typealias InferredEpochAlias = BindingEpoch
            class InferredErasedSignatureFixture {
                fun token() = Oid(1L)
                fun aliasedToken() = InferredEpochAlias(1L)
                fun valueOnly() = Oid(1L).value == 1L
                fun blockOnly(): Boolean {
                    val token = Oid(1L)
                    return token.value == 1L
                }
                private fun hidden() = Oid(1L)
                companion object {
                    val epoch = InferredEpochAlias(1L)
                    val number = BindingEpoch(1L).value
                }
            }
            """.trimIndent(),
        )
        val inferredFixtureIndex = KotlinSourceIndex(authorityDefinitionUnits() + inferredFixtureSource)
        val inferredFixtureViolations = InferredErasedSignatureFixture::class.java
            .publicApiAuthorityViolations(inferredFixtureIndex)
            .filter { it.startsWith("${inferredFixtureSource.path}:") }
        val tokenOffset = inferredFixtureSource.source.indexOf("Oid(1L)")
        val aliasedTokenOffset = inferredFixtureSource.source.indexOf("InferredEpochAlias(1L)")
        val inferredEpochOffset = inferredFixtureSource.source.lastIndexOf("InferredEpochAlias(1L)")
        val expectedInferredViolations = setOf(
            "${inferredFixtureSource.path}:${inferredFixtureSource.indexed.lineOf(tokenOffset)}:" +
                "InferredErasedSignatureFixture.token:Oid -> com.plainbase.domain.root.ObservationId",
            "${inferredFixtureSource.path}:${inferredFixtureSource.indexed.lineOf(aliasedTokenOffset)}:" +
                "InferredErasedSignatureFixture.aliasedToken:InferredEpochAlias -> " +
                "com.plainbase.domain.root.BindingEpoch",
            "${inferredFixtureSource.path}:${inferredFixtureSource.indexed.lineOf(inferredEpochOffset)}:" +
                "InferredErasedSignatureFixture.Companion.epoch:InferredEpochAlias -> " +
                "com.plainbase.domain.root.BindingEpoch",
        )
        withClue("direct inferred constructors are supplemented through the reached production graph: $inferredFixtureViolations") {
            inferredFixtureViolations.filter { ":InferredErasedSignatureFixture.token:" in it } shouldBe
                expectedInferredViolations.filter { ":InferredErasedSignatureFixture.token:" in it }.toSet()
            inferredFixtureViolations.filter { ":InferredErasedSignatureFixture.aliasedToken:" in it } shouldBe
                expectedInferredViolations.filter { ":InferredErasedSignatureFixture.aliasedToken:" in it }.toSet()
            inferredFixtureViolations.filter { ":InferredErasedSignatureFixture.Companion.epoch:" in it } shouldBe
                expectedInferredViolations.filter { ":InferredErasedSignatureFixture.Companion.epoch:" in it }.toSet()
            inferredFixtureViolations shouldBe expectedInferredViolations
        }
        withClue("non-token inferred expressions stay outside the finite constructor supplement") {
            inferredFixtureViolations.none {
                it.contains("valueOnly") || it.contains("blockOnly") || it.contains("shadowed") || it.contains("hidden") ||
                    it.contains("number")
            } shouldBe true
        }
        withClue("recursive generic bounds are visited without runaway expansion") {
            RecursiveFixture::class.java.publicApiAuthorityViolations().shouldBeEmpty()
        }
        listOf(
            "wrapper" to WrapperAuthorityFixture::class.java,
            "list" to ListAuthorityFixture::class.java,
            "companion" to CompanionAuthorityFixture::class.java,
            "inherited list" to InheritedListAuthorityFixture::class.java,
        ).forEach { (label, type) ->
            val violations = type.publicApiAuthorityViolations()
            withClue("$label public API authority fixture: $violations") {
                violations shouldBe setOf("IdMapRepository")
            }
        }
        withClue("private authority storage is not an API leak") {
            PrivateAuthorityStorageFixture::class.java.publicApiAuthorityViolations().shouldBeEmpty()
        }
        withClue("safe substituted generic API remains clean") {
            InheritedSafeFixture::class.java.publicApiAuthorityViolations().shouldBeEmpty()
        }
        withClue("IdMapRepository's own public port is admitted only as the traversal seed") {
            com.plainbase.domain.repository.IdMapRepository::class.java
                .publicApiAuthorityViolations(allowForbiddenEntry = true)
                .shouldBeEmpty()
            com.plainbase.domain.repository.IdMapRepository::class.java
                .publicApiAuthorityViolations() shouldBe setOf("IdMapRepository")
        }
        withClue("entry-only admission still rejects forbidden types on public API edges") {
            IdMapEntryAuthorityFixture::class.java
                .publicApiAuthorityViolations(allowForbiddenEntry = true) shouldBe setOf("RetirementRepository")
        }
        withClue("ordinary IdMapRepository exposure remains rejected") {
            ListAuthorityFixture::class.java.publicApiAuthorityViolations() shouldBe setOf("IdMapRepository")
        }
        withClue("the real IdMapRepository seed is clean only with its exact bridge receiver admission") {
            IdMapRepository::class.java.publicApiAuthorityViolations(productionIndex, allowForbiddenEntry = true)
                .shouldBeEmpty()
        }
        withClue("synthetic accessors remain part of the public authority graph") {
            val fixture = SyntheticAccessorAuthorityFixture::class.java
            val accessors = fixture.declaredMethods.filter { it.name == "access\$getRepository\$p" }
            accessors.size shouldBe 1
            val accessor = accessors.single()
            accessor.isSynthetic shouldBe true
            Modifier.isPublic(accessor.modifiers) shouldBe true
            Modifier.isStatic(accessor.modifiers) shouldBe true
            accessor.parameterTypes.toList() shouldBe listOf(fixture)
            accessor.returnType shouldBe RetirementRepository::class.java
            fixture.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .flatMap { method -> listOf(method.genericReturnType) + method.genericParameterTypes.toList() }
                .flatMap { it.authorityNames() }
                .shouldBeEmpty()
            fixture.publicApiAuthorityViolations() shouldBe setOf("RetirementRepository")
        }
    }

    test("assembler materialized result graph") {
        val pageIndexViolations = PageIndex::class.java.materializedDataGraphViolations()
        withClue("PageIndex materialized payload graph must remain eager and authority-free: $pageIndexViolations") {
            pageIndexViolations.shouldBeEmpty()
        }
        val positiveViolations = MaterializedPositiveFixture::class.java.materializedDataGraphViolations()
        withClue("ordinary nested materialized values remain allowed: $positiveViolations") {
            positiveViolations.shouldBeEmpty()
        }
        val deferredViolations = MaterializedDeferredFixture::class.java.materializedDataGraphViolations()
        withClue("nested deferred materialized values are rejected independently: $deferredViolations") {
            deferredViolations.any { it.contains("Lazy") } shouldBe true
        }
        val authorityViolations = MaterializedAuthorityFixture::class.java.materializedDataGraphViolations()
        withClue("nested authority materialized values are rejected independently: $authorityViolations") {
            authorityViolations.any { it.contains("RetirementRepository") } shouldBe true
        }
        val unknownViolations = MaterializedUnknownWrapperFixture::class.java.materializedDataGraphViolations()
        withClue("unknown materialized application types fail closed with a diagnostic: $unknownViolations") {
            unknownViolations shouldBe setOf(
                "${MaterializedUnknownWrapperFixture::class.java.name} field wrapped: " +
                    "unreviewed materialized application type ${MaterializedUnknownPayload::class.java.name}",
            )
        }
    }

    test("same-spelled member shadows imported constructor in value resolution") {
        val source = KotlinSourceUnit(
            "com/plainbase/domain/service/LateMemberShadowFixture.kt",
            """
            package com.plainbase.domain.service
            import com.plainbase.domain.root.ObservationId as Oid
            class LateMemberShadowFixture {
                fun exposed() = Oid(1L)
                private fun `Oid`(value: Long): Long = value
                fun memberBeforeUse() = Oid(1L)
            }
            """.trimIndent(),
        )
        val sourceIndex = KotlinSourceIndex(authorityDefinitionUnits() + source)
        val callOffset = source.source.indexOf("Oid(1L)")
        val callIndex = source.tokens.indexOfFirst { it.offset == callOffset }
        val resolved = sourceIndex.resolveExpressionReference(source, callIndex)
        val member = source.indexed.declarations.single { it.kind == SourceDeclarationKind.FUNCTION && it.name == "Oid" }
        withClue("a later class member is visible before its textual declaration") {
            resolved.namespace shouldBe SourceNamespace.VALUE
            resolved.identity shouldBe member.identity
        }
    }

    test("same-spelled callback and local declarations retain their legal scopes") {
        val source = KotlinSourceUnit(
            "com/plainbase/domain/service/ScopedValueShadowFixture.kt",
            """
            package com.plainbase.domain.service
            import com.plainbase.domain.root.ObservationId as Oid
            class CallbackValueShadowFixture {
                fun callback(`Oid`: (Long) -> Long) = `Oid`(1L)
            }
            class LocalValueOrderFixture {
                fun order(): Long {
                    val before = Oid(1L).value
                    return run {
                        fun `Oid`(value: Long): Long = value
                        `Oid`(1L)
                    }
                }
            }
            """.trimIndent(),
        )
        val sourceIndex = KotlinSourceIndex(authorityDefinitionUnits() + source)
        val callbackDeclaration = source.indexed.declarations.single {
            it.kind == SourceDeclarationKind.PARAMETER && it.name == "Oid"
        }
        val callbackStart = source.source.indexOf("fun callback")
        val callbackOffset = source.source.indexOf("`Oid`(1L)", callbackStart)
        val callbackIndex = source.tokens.indexOfFirst { it.offset == callbackOffset }
        val callbackResolved = sourceIndex.resolveExpressionReference(source, callbackIndex)
        val firstOffset = source.source.indexOf("Oid(1L)")
        val firstIndex = source.tokens.indexOfFirst { it.offset == firstOffset }
        val firstResolved = sourceIndex.resolveExpressionReference(source, firstIndex)
        val localOffset = source.source.lastIndexOf("`Oid`(1L)")
        val localIndex = source.tokens.indexOfFirst { it.offset == localOffset }
        val localResolved = sourceIndex.resolveExpressionReference(source, localIndex)
        val localDeclaration = source.indexed.declarations.single {
            it.kind == SourceDeclarationKind.FUNCTION &&
                it.name == "Oid" &&
                it.ownerQualifiedName?.contains("LocalValueOrderFixture") == true
        }
        withClue("a callback parameter keeps VALUE precedence for its same-spelled call") {
            callbackResolved.namespace shouldBe SourceNamespace.VALUE
            callbackResolved.identity shouldBe callbackDeclaration.identity
        }
        withClue("an imported constructor remains visible before a nested local declaration enters scope") {
            firstResolved.namespace shouldBe SourceNamespace.TYPE
            firstResolved.identity shouldBe "com.plainbase.domain.root.ObservationId"
        }
        withClue("the nested local declaration resolves after its legal declaration point") {
            localResolved.namespace shouldBe SourceNamespace.VALUE
            localResolved.identity shouldBe localDeclaration.identity
        }
        withClue("compiled callback and local controls remain outside the authority graph") {
            CallbackValueShadowFixture::class.java.publicApiAuthorityViolations(sourceIndex)
                .filter { it.startsWith("${source.path}:") }.shouldBeEmpty()
            LocalValueOrderFixture::class.java.publicApiAuthorityViolations(sourceIndex)
                .filter { it.startsWith("${source.path}:") }.shouldBeEmpty()
        }
    }

    test("same-spelled member leaves the reached public graph clean") {
        val source = KotlinSourceUnit(
            "com/plainbase/domain/service/LateMemberShadowFixture.kt",
            """
            package com.plainbase.domain.service
            import com.plainbase.domain.root.ObservationId as Oid
            class LateMemberShadowFixture {
                fun exposed() = Oid(1L)
                private fun `Oid`(value: Long): Long = value
                fun memberBeforeUse() = Oid(1L)
            }
            """.trimIndent(),
        )
        val sourceIndex = KotlinSourceIndex(authorityDefinitionUnits() + source)
        val violations = LateMemberShadowFixture::class.java.publicApiAuthorityViolations(sourceIndex)
        withClue("the exact compiled owner has primitive public results, not an imported authority token: $violations") {
            violations.filter { it.startsWith("${source.path}:") }.shouldBeEmpty()
        }
    }

    test("indexing passive confinement") {
        val productionUnits = kotlinFiles(productionRoot).map { path ->
            KotlinSourceUnit(productionRoot.relativize(path).toString(), Files.readString(path))
        }
        val sourceIndex = KotlinSourceIndex(productionUnits)
        val offenders = sourceIndex.indexingReferences()
        withClue("passive indexing values escaped their three admitted owners: $offenders") {
            offenders.shouldBeEmpty()
        }
        val semanticCases = listOf(
            "same-package escape" to KotlinSourceUnit(
                "com/other/Reader.kt",
                "package com.plainbase.domain.service\nfun read(value: SourceScan) = value",
            ),
            "explicit alias" to KotlinSourceUnit(
                "com/other/Reader.kt",
                "package com.other\nimport com.plainbase.domain.service.SourceScan as Scan\nfun read(value: Scan) = value",
            ),
            "star import" to KotlinSourceUnit(
                "com/other/Reader.kt",
                "package com.other\nimport com.plainbase.domain.service.*\nfun read(value: SourceScan) = value",
            ),
            "cross-file alias" to KotlinSourceUnit(
                "com/other/Reader.kt",
                "package com.other\nimport com.other.Mid as Scan\nfun read(value: Scan) = value",
            ),
            "legitimate nested AdoptionPass Draft" to KotlinSourceUnit(
                "com/plainbase/domain/service/AdoptionPass.kt",
                "package com.plainbase.domain.service\nclass AdoptionPass { private class Draft; fun use() = Draft() }",
            ),
            "indexing Draft outside nested scope" to KotlinSourceUnit(
                "com/other/Reader.kt",
                "package com.plainbase.domain.service\nclass AdoptionPass { private class Draft }\nfun use(value: Draft) = value",
            ),
            "explicit indexing Draft inside AdoptionPass" to KotlinSourceUnit(
                "com/other/Reader.kt",
                "package com.plainbase.domain.service\nclass AdoptionPass { fun use(value: com.plainbase.domain.service.Draft) = value }",
            ),
            "unrelated package declaration" to KotlinSourceUnit(
                "com/other/Reader.kt",
                "package com.other\nclass SourceScan\nfun read(value: SourceScan) = value",
            ),
            "type parameter shadows same-package name" to KotlinSourceUnit(
                "com/other/Reader.kt",
                "package com.plainbase.domain.service\nfun <SourceScan> read(value: SourceScan) = value",
            ),
            "other package IndexBuilder path is not admitted" to KotlinSourceUnit(
                "com/other/IndexBuilder.kt",
                "package com.other\nimport com.plainbase.domain.service.SourceScan\nfun read(value: SourceScan) = value",
            ),
        )
        val aliasSource = KotlinSourceUnit(
            "com/other/Alias.kt",
            "package com.other\ntypealias Mid = com.plainbase.domain.service.SourceScan",
        )
        val indexingDefinitions = KotlinSourceUnit(
            "com/plainbase/domain/service/IndexingDefinitions.kt",
            "package com.plainbase.domain.service\nclass Draft\nclass SourceScan\nclass Identity",
        )
        semanticCases.forEach { (label, unit) ->
            val units = if (label == "cross-file alias") {
                listOf(indexingDefinitions, aliasSource, unit)
            } else {
                listOf(indexingDefinitions, unit)
            }
            val caseIndex = KotlinSourceIndex(units)
            val caseOffenders = caseIndex.indexingReferences()
            val shouldReport = label !in setOf(
                "legitimate nested AdoptionPass Draft",
                "unrelated package declaration",
                "type parameter shadows same-package name",
            )
            withClue("semantic case $label: $caseOffenders") {
                (caseOffenders.isNotEmpty()) shouldBe shouldReport
            }
            if (label == "legitimate nested AdoptionPass Draft") caseOffenders shouldBe emptyList()
        }
        val bodyless = KotlinSourceScanner.scan(
            "scope.kt",
            "class Outer { class Bodyless; fun f() { class Local } }\nfun topLevel() = Unit",
        )
        bodyless.declarations.map { it.qualified } shouldBe listOf("Outer", "Outer.Bodyless", "Outer.f.Local")
        bodyless.topLevelRecords shouldBe listOf("scope.kt|fun topLevel")
        KotlinSourceIndex(
            listOf(KotlinSourceUnit("scope.kt", "class Outer { class Bodyless; fun f() { class Local } }\nfun topLevel() = Unit")),
        )
            .discoveredDeclarations() shouldBe listOf(
                "scope.kt|Outer", "scope.kt|Outer.Bodyless", "scope.kt|Outer.f.Local",
            )
        val malformed = runCatching { KotlinSourceScanner.scan("broken.kt", "class Broken {") }.exceptionOrNull()
        malformed?.message?.contains("broken.kt:1") shouldBe true
    }

    test("loadability and source ownership cross-check") {
        val loader = this::class.java.classLoader
        val productionIndex = KotlinSourceIndex(
            serviceFiles.map { path ->
                KotlinSourceUnit(serviceRoot.relativize(path).toString(), Files.readString(path))
            },
        )
        val classifications = productionIndex.jvmClassifications(loader)
        val discovered = productionIndex.discoveredDeclarations()
        withClue("loadability must audit the same discovered declaration records") {
            discovered.groupingBy { it }.eachCount() shouldBe EXPECTED_SERVICE_DECLARATIONS.groupingBy { it }.eachCount()
            classifications.size shouldBe discovered.size
        }
        val failures = classifications.mapNotNull { classification ->
            val binary = classification.emittedBinaryName ?: return@mapNotNull null
            val loaded = runCatching { Class.forName(binary, false, loader) }.getOrNull()
            when {
                loaded == null -> "${classification.sourceQualifiedName} -> $binary did not load"
                else -> {
                    val expectedOuter = classification.sourceQualifiedName.substringBeforeLast('.', missingDelimiterValue = "")
                        .takeIf { it.isNotEmpty() }
                        ?.let { "com.plainbase.domain.service.${it.replace('.', '$')}" }
                    val actualOuter = loaded.enclosingClass?.name
                    if (actualOuter != expectedOuter) {
                        "${classification.sourceQualifiedName} -> enclosing=$actualOuter expected=$expectedOuter"
                    } else {
                        null
                    }
                }
            }
        }
        withClue("named declarations must map to their known JVM nesting: $failures") {
            failures.shouldBeEmpty()
        }
    }
})

private data class LexToken(val text: String, val offset: Int)

private data class Declaration(val path: String, val qualified: String, val offset: Int = -1)

private data class ScanResult(
    val tokens: List<LexToken>,
    val declarations: List<Declaration>,
    val topLevelRecords: List<String>,
)

private object KotlinSourceScanner {
    fun scan(path: String, source: String): ScanResult = KotlinSourceUnit(path, source).scanResult()
}

private fun KotlinSourceUnit.topLevelDeclarationTokenSequences(): Map<String, List<String>> {
    val starts = indexed.declarations.filter {
        it.scopeId == 0 && it.kind in setOf(SourceDeclarationKind.CLASS, SourceDeclarationKind.INTERFACE, SourceDeclarationKind.OBJECT)
    }.map { declaration ->
        var start = declaration.tokenIndex
        while (start > 0 && tokens[start - 1].text in SOURCE_MODIFIERS) start--
        TopLevelDeclarationStart(declaration.name, start)
    }
    return starts.mapIndexed { index, start ->
        val end = starts.getOrNull(index + 1)?.index ?: tokens.size
        start.name to tokens.subList(start.index, end).map { it.text }
    }.toMap()
}

private fun KotlinSourceUnit.readerSourceSchemaViolations(
    expectedConstructorTokens: List<String>,
    expectedReadTokens: List<String>,
    className: String = "IndexSourceReader",
    operationName: String = "read",
    diagnosticLabel: String = "reader",
): List<String> {
    val readerClasses = indexed.declarations.filter {
        it.kind == SourceDeclarationKind.CLASS && it.name == className && it.ownerQualifiedName == null
    }
    val reader = readerClasses.singleOrNull()
        ?: return listOf("$diagnosticLabel source has ${readerClasses.size} direct $className declarations, expected=1")
    val readMethods = indexed.declarations.filter {
        it.kind == SourceDeclarationKind.FUNCTION &&
            it.name == operationName &&
            it.ownerQualifiedName == reader.qualifiedName &&
            it.visibility == "public"
    }
    if (readMethods.size != 1) {
        return listOf("$diagnosticLabel source has ${readMethods.size} direct public $operationName declarations, expected=1")
    }
    fun headerTokens(declaration: LocatedSourceDeclaration): List<String> {
        var start = declaration.tokenIndex
        while (start > 0 && tokens[start - 1].text in SOURCE_MODIFIERS) start--
        return tokens.subList(start, declaration.headerEndTokenIndex).map { it.text }
    }
    val actualConstructorTokens = headerTokens(reader)
    val actualReadTokens = headerTokens(readMethods.single())
    return buildList {
        if (actualConstructorTokens != expectedConstructorTokens) {
            add("$diagnosticLabel constructor tokens=$actualConstructorTokens, expected=$expectedConstructorTokens")
        }
        if (actualReadTokens != expectedReadTokens) {
            add("$diagnosticLabel $operationName tokens=$actualReadTokens, expected=$expectedReadTokens")
        }
    }
}

private data class TopLevelDeclarationStart(val name: String, val index: Int)

private class KotlinSourceFailure(val offset: Int, message: String) : IllegalArgumentException(message)

private object KotlinTokenizer {
    fun tokenize(source: String): List<LexToken> = Lexer(source).run()

    private class Lexer(private val source: String) {
        private val output = mutableListOf<LexToken>()
        private val length = source.length

        fun run(): List<LexToken> {
            scanCode(0, interpolation = false)
            return output
        }

        private fun scanCode(start: Int, interpolation: Boolean): Int {
            var index = start
            var braceDepth = if (interpolation) 1 else 0
            while (index < length) {
                val c = source[index]
                when {
                    c.isWhitespace() -> index++
                    c == '/' && source.getOrNull(index + 1) == '/' -> index = skipLine(index + 2)
                    c == '/' && source.getOrNull(index + 1) == '*' -> index = skipBlock(index + 2)
                    c == '"' -> index = skipString(index)
                    c == '\'' -> index = skipCharacter(index)
                    c == '`' -> index = emitBacktick(index)
                    c == '{' -> {
                        output += LexToken("{", index)
                        braceDepth++
                        index++
                    }
                    c == '}' -> {
                        braceDepth--
                        if (interpolation && braceDepth == 0) return index + 1
                        output += LexToken("}", index)
                        index++
                    }
                    c.isLetter() || c == '_' -> index = emitIdentifier(index)
                    c == '$' && source.getOrNull(index + 1)?.let(Character::isJavaIdentifierStart) == true -> {
                        index = emitIdentifier(index + 1)
                    }
                    else -> {
                        output += LexToken(c.toString(), index)
                        index++
                    }
                }
            }
            if (interpolation && braceDepth != 0) throw KotlinSourceFailure(index, "unclosed interpolation")
            return index
        }

        private fun emitIdentifier(start: Int): Int {
            var index = start
            while (index < length && (source[index].isLetterOrDigit() || source[index] == '_')) index++
            output += LexToken(source.substring(start, index), start)
            return index
        }

        private fun emitBacktick(start: Int): Int {
            val end = source.indexOf('`', start + 1)
            if (end < 0) throw KotlinSourceFailure(start, "unclosed backtick identifier")
            val actualEnd = end
            output += LexToken(source.substring(start + 1, actualEnd), start)
            return (actualEnd + 1).coerceAtMost(length)
        }

        private fun skipLine(start: Int): Int = source.indexOf('\n', start).takeIf { it >= 0 }?.plus(1) ?: length

        private fun skipBlock(start: Int): Int {
            var index = start
            var depth = 1
            while (index < length - 1 && depth > 0) {
                when {
                    source[index] == '/' && source[index + 1] == '*' -> {
                        depth++
                        index += 2
                    }
                    source[index] == '*' && source[index + 1] == '/' -> {
                        depth--
                        index += 2
                    }
                    else -> index++
                }
            }
            if (depth != 0) throw KotlinSourceFailure(start, "unclosed block comment")
            return index
        }

        private fun skipCharacter(start: Int): Int {
            var index = start + 1
            while (index < length) {
                if (source[index] == '\\') {
                    index += 2
                } else if (source[index++] == '\'') {
                    break
                }
            }
            if (index > length || source.getOrNull(index - 1) != '\'') {
                throw KotlinSourceFailure(start, "unclosed character literal")
            }
            return index
        }

        private fun skipString(start: Int): Int {
            val triple = source.startsWith("\"\"\"", start)
            var index = start + if (triple) 3 else 1
            while (index < length) {
                if (triple && source.startsWith("\"\"\"\"", index)) {
                    // A quote immediately before a raw-string terminator is content; consume it before the final
                    // three quotes so SQL/JSON snippets ending in a quote remain lexically balanced.
                    index++
                    continue
                }
                if (triple && source.startsWith("\"\"\"", index)) return index + 3
                if (!triple && source[index] == '\\') {
                    index += 2
                } else if (!triple && source[index] == '"') {
                    return index + 1
                } else if (source[index] == '$' && source.getOrNull(index + 1) == '{') {
                    output += LexToken("{", index + 1)
                    index = scanCode(index + 2, interpolation = true)
                    output += LexToken("}", index - 1)
                } else if (source[index] == '$' &&
                    source.getOrNull(index + 1)?.let(Character::isJavaIdentifierStart) == true
                ) {
                    index = emitIdentifier(index + 1)
                } else {
                    index++
                }
            }
            throw KotlinSourceFailure(start, "unclosed string literal")
        }
    }
}

private fun String.isIdentifier(): Boolean = isNotEmpty() && first().let { it.isLetter() || it == '_' } && all {
    it.isLetterOrDigit() || it == '_' || it == '`' || it == ' '
}

private fun String.normalizedWhitespace(): String = replace(Regex("\\s+"), " ").trim()

private fun mainKotlinRoot(): Path {
    var directory: Path? = Paths.get("").toAbsolutePath()
    while (directory != null) {
        for (candidate in listOf("src/main/kotlin", "server/src/main/kotlin")) {
            val resolved = directory.resolve(candidate)
            if (Files.isDirectory(resolved)) return resolved
        }
        directory = directory.parent
    }
    error("src/main/kotlin not found while walking up from ${Paths.get("").toAbsolutePath()}")
}

private fun kotlinFiles(root: Path): List<Path> = Files.walk(root).use { paths ->
    paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.sorted().toList()
}

private fun Class<*>.isDataClass(): Boolean = declaredMethods.any { it.name.startsWith("component1") }

private fun Type.render(): String = when (this) {
    is Class<*> -> name
    is ParameterizedType -> buildString {
        append(rawType.render())
        append('<')
        append(actualTypeArguments.joinToString(",") { it.render() })
        append('>')
    }
    is WildcardType -> buildString {
        append('?')
        lowerBounds.firstOrNull()?.let { append(" super ").append(it.render()) }
        if (lowerBounds.isEmpty() && upperBounds.firstOrNull()?.let { it != Any::class.java } == true) {
            append(" extends ").append(upperBounds.first().render())
        }
    }
    is TypeVariable<*> -> name
    is GenericArrayType -> "${genericComponentType.render()}[]"
    else -> typeName
}

private fun Type.deferredNames(seen: MutableSet<Type> = mutableSetOf()): Set<String> {
    if (!seen.add(this)) return emptySet()
    return when (this) {
        is Class<*> -> buildSet {
            val simple = simpleName
            if (simple in setOf("Lazy", "Sequence", "Iterator", "Flow") || simple.startsWith("Function")) {
                add(if (simple.startsWith("Function")) "Function" else simple)
            }
            if (isArray) addAll(componentType.deferredNames(seen))
        }
        is ParameterizedType -> buildSet {
            addAll(rawType.deferredNames(seen))
            ownerType?.let { addAll(it.deferredNames(seen)) }
            actualTypeArguments.forEach { addAll(it.deferredNames(seen)) }
        }
        is WildcardType -> buildSet {
            upperBounds.forEach { addAll(it.deferredNames(seen)) }
            lowerBounds.forEach { addAll(it.deferredNames(seen)) }
        }
        is TypeVariable<*> -> buildSet { bounds.forEach { addAll(it.deferredNames(seen)) } }
        is GenericArrayType -> genericComponentType.deferredNames(seen)
        else -> emptySet()
    }
}

private fun Type.authorityNames(
    bindings: Map<TypeVariable<*>, Type> = emptyMap(),
    seen: MutableSet<Type> = mutableSetOf(),
    forbiddenTypes: Map<String, String> = FORBIDDEN_AUTHORITY_TYPES,
): Set<String> {
    val resolved = if (this is TypeVariable<*> && this in bindings) bindings.getValue(this) else this
    if (!seen.add(resolved)) return emptySet()
    return when (resolved) {
        is Class<*> -> buildSet {
            resolved.authorityLabel(forbiddenTypes)?.let(::add)
            if (resolved.isArray) addAll(resolved.componentType.authorityNames(bindings, seen, forbiddenTypes))
        }
        is ParameterizedType -> buildSet {
            addAll(resolved.rawType.authorityNames(bindings, seen, forbiddenTypes))
            resolved.ownerType?.let { addAll(it.authorityNames(bindings, seen, forbiddenTypes)) }
            resolved.actualTypeArguments.forEach { addAll(it.authorityNames(bindings, seen, forbiddenTypes)) }
        }
        is WildcardType -> buildSet {
            resolved.upperBounds.forEach { addAll(it.authorityNames(bindings, seen, forbiddenTypes)) }
            resolved.lowerBounds.forEach { addAll(it.authorityNames(bindings, seen, forbiddenTypes)) }
        }
        is TypeVariable<*> -> buildSet {
            resolved.bounds.forEach { addAll(it.authorityNames(bindings, seen, forbiddenTypes)) }
        }
        is GenericArrayType -> resolved.genericComponentType.authorityNames(bindings, seen, forbiddenTypes)
        else -> emptySet()
    }
}

private fun Type.deferredOrAuthorityNames(): Set<String> = deferredNames() + authorityNames()

private fun Class<*>.typeGraphViolations(): Set<String> = buildSet {
    declaredFields.forEach { addAll(it.genericType.deferredOrAuthorityNames()) }
    declaredConstructors.forEach { constructor ->
        constructor.genericParameterTypes.forEach { addAll(it.deferredOrAuthorityNames()) }
    }
    declaredMethods.forEach { method ->
        addAll(method.genericReturnType.deferredOrAuthorityNames())
        method.genericParameterTypes.forEach { addAll(it.deferredOrAuthorityNames()) }
    }
}

private val MATERIALIZED_DATA_TYPES = setOf(
    "com.plainbase.domain.page.PageIndex",
    "com.plainbase.domain.page.RootSection",
    "com.plainbase.domain.page.IndexedPage",
    "com.plainbase.domain.page.Frontmatter",
    "com.plainbase.domain.page.FrontmatterValue",
    "com.plainbase.domain.page.FrontmatterValue\$Scalar",
    "com.plainbase.domain.page.FrontmatterValue\$StringList",
    "com.plainbase.domain.page.Heading",
    "com.plainbase.domain.model.PageLink",
    "com.plainbase.domain.model.LinkOutcome",
    "com.plainbase.domain.model.LinkOutcome\$Resolved",
    "com.plainbase.domain.model.LinkOutcome\$Resolved\$Page",
    "com.plainbase.domain.model.LinkOutcome\$Resolved\$Asset",
    "com.plainbase.domain.model.LinkOutcome\$Resolved\$External",
    "com.plainbase.domain.model.LinkOutcome\$Resolved\$Anchor",
    "com.plainbase.domain.model.LinkOutcome\$Broken",
    "com.plainbase.domain.model.LinkOutcome\$BrokenReason",
    "com.plainbase.domain.render.RenderedSection",
    "com.plainbase.domain.content.ContentFolder",
    "com.plainbase.domain.content.FolderMeta",
    "com.plainbase.domain.content.TreePath",
    "com.plainbase.domain.root.RootedPath",
    "com.plainbase.domain.root.RootedPageId",
    "com.plainbase.domain.root.RootName",
    "com.plainbase.domain.page.PageId",
    "com.plainbase.domain.page.PageIndexView",
    "com.plainbase.domain.page.PageIndex\$SectionView",
    "com.plainbase.domain.service.MaterializedPositiveFixture",
    "com.plainbase.domain.service.MaterializedNestedPositiveFixture",
    "com.plainbase.domain.service.MaterializedDeferredFixture",
    "com.plainbase.domain.service.MaterializedNestedDeferredFixture",
    "com.plainbase.domain.service.MaterializedAuthorityFixture",
    "com.plainbase.domain.service.MaterializedNestedAuthorityFixture",
)

private val MATERIALIZED_SEALED_VARIANTS = mapOf(
    "com.plainbase.domain.page.FrontmatterValue" to listOf(
        "com.plainbase.domain.page.FrontmatterValue\$Scalar",
        "com.plainbase.domain.page.FrontmatterValue\$StringList",
    ),
    "com.plainbase.domain.model.LinkOutcome" to listOf(
        "com.plainbase.domain.model.LinkOutcome\$Resolved",
        "com.plainbase.domain.model.LinkOutcome\$Resolved\$Page",
        "com.plainbase.domain.model.LinkOutcome\$Resolved\$Asset",
        "com.plainbase.domain.model.LinkOutcome\$Resolved\$External",
        "com.plainbase.domain.model.LinkOutcome\$Resolved\$Anchor",
        "com.plainbase.domain.model.LinkOutcome\$Broken",
    ),
)

private val MATERIALIZED_RETAINED_TYPES = mapOf(
    "com.plainbase.domain.page.PageIndex" to listOf("com.plainbase.domain.page.PageIndex\$SectionView"),
)

private fun Class<*>.materializedDataGraphViolations(): Set<String> {
    val root = this
    val violations = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    lateinit var visitClass: (Class<*>, String) -> Unit

    fun visit(type: Type, origin: String) {
        val deferred = type.deferredNames()
        deferred.forEach { violations += "$origin: deferred $it" }
        val authority = type.authorityNames(forbiddenTypes = ASSEMBLER_FORBIDDEN_AUTHORITY_TYPES)
        authority.forEach { violations += "$origin: authority $it" }
        when (type) {
            is TypeVariable<*> -> type.bounds.forEach { visit(it, "$origin bound") }
            is GenericArrayType -> visit(type.genericComponentType, "$origin[]")
            is WildcardType -> {
                type.upperBounds.forEach { visit(it, "$origin upper") }
                type.lowerBounds.forEach { visit(it, "$origin lower") }
            }
            is ParameterizedType -> {
                type.ownerType?.let { visit(it, "$origin owner") }
                type.actualTypeArguments.forEachIndexed { index, argument -> visit(argument, "$origin arg[$index]") }
                (type.rawType as? Class<*>)?.let { visitClass(it, origin) }
            }
            is Class<*> -> {
                if (type.isArray) {
                    visit(type.componentType, "$origin[]")
                } else {
                    visitClass(type, origin)
                }
            }
        }
    }

    visitClass = fun(type: Class<*>, origin: String) {
        val isLibraryType =
            type.isPrimitive ||
                type.name.startsWith("java.") ||
                type.name.startsWith("kotlin.") ||
                type.name.startsWith("io.github.") ||
                type.name.startsWith("org.jetbrains.")
        if (isLibraryType) {
            return
        }
        if (type != root && type.name !in MATERIALIZED_DATA_TYPES) {
            violations += "$origin: unreviewed materialized application type ${type.name}"
            return
        }
        if (!visited.add(type.name)) return
        type.declaredConstructors.forEachIndexed { constructorIndex, constructor ->
            constructor.genericParameterTypes.forEachIndexed { parameterIndex, parameter ->
                visit(parameter, "$origin constructor[$constructorIndex] arg[$parameterIndex]")
            }
        }
        type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { field ->
            visit(field.genericType, "$origin field ${field.name}")
        }
        MATERIALIZED_RETAINED_TYPES[type.name].orEmpty().forEach { retainedName ->
            val retained = Class.forName(retainedName, false, type.classLoader)
            visitClass(retained, "$origin retained ${retained.simpleName}")
        }
        MATERIALIZED_SEALED_VARIANTS[type.name].orEmpty().forEach { variantName ->
            val variant = Class.forName(variantName, false, type.classLoader)
            visitClass(variant, "$origin variant ${variant.simpleName}")
        }
    }

    visit(this, name)
    return violations
}

private val FORBIDDEN_AUTHORITY_TYPES = mapOf(
    "com.plainbase.domain.repository.IdMapRepository" to "IdMapRepository",
    "com.plainbase.domain.repository.RetirementRepository" to "RetirementRepository",
    "com.plainbase.domain.repository.RootTopologyRepository" to "RootTopologyRepository",
    "com.plainbase.domain.root.ObservationEpoch" to "ObservationEpoch",
    "com.plainbase.domain.root.BindingLatch" to "BindingLatch",
    "com.plainbase.domain.root.AbsenceProof" to "AbsenceProof",
    "com.plainbase.domain.root.ObservationId" to "ObservationId",
    "com.plainbase.domain.root.BindingEpoch" to "BindingEpoch",
    "com.plainbase.domain.root.ObjectManifestProvider" to "ObjectManifestProvider",
    "com.plainbase.domain.root.ObjectManifest" to "ObjectManifest",
    "com.plainbase.domain.service.IndexBuilder" to "IndexBuilder",
    "com.plainbase.domain.service.IndexBuilder\$Published" to "Published",
    "com.plainbase.domain.service.IndexBuilder\$PublicationListener" to "PublicationListener",
    "com.plainbase.domain.service.SearchIndexer" to "SearchIndexer",
)

private val ASSEMBLER_FORBIDDEN_AUTHORITY_TYPES = FORBIDDEN_AUTHORITY_TYPES + mapOf(
    "com.plainbase.domain.content.ContentStore" to "ContentStore",
    "com.plainbase.domain.history.HistoryProvider" to "HistoryProvider",
    "com.plainbase.domain.repository.PageCheckpointRepository" to "PageCheckpointRepository",
    "com.plainbase.domain.service.UrlAliasRegistry" to "UrlAliasRegistry",
    "com.plainbase.domain.root.RootAvailability" to "RootAvailability",
    "com.plainbase.domain.root.RootLimbo" to "RootLimbo",
    "java.util.concurrent.atomic.AtomicReference" to "AtomicReference",
)

private fun Class<*>.authorityLabel(forbiddenTypes: Map<String, String> = FORBIDDEN_AUTHORITY_TYPES): String? =
    forbiddenTypes[name]

private fun Method.isAdmittedIdMapBindDefaultReceiver(): Boolean =
    declaringClass == IdMapRepository::class.java &&
        name == "bind\$default" &&
        Modifier.isPublic(modifiers) &&
        Modifier.isStatic(modifiers) &&
        isSynthetic &&
        parameterTypes.toList() == listOf(
            IdMapRepository::class.java,
            RootedPath::class.java,
            PageId::class.java,
            java.lang.Boolean.TYPE,
            Supersession::class.java,
            java.lang.Integer.TYPE,
            Any::class.java,
        ) &&
        returnType == BindOutcome::class.java

private fun Method.authorityGenericParameterTypes(): List<Type> =
    genericParameterTypes.drop(if (isAdmittedIdMapBindDefaultReceiver()) 1 else 0)

private fun Class<*>.publicApiAuthorityViolations(
    sourceIndex: KotlinSourceIndex? = null,
    allowForbiddenEntry: Boolean = false,
    forbiddenTypes: Map<String, String> = FORBIDDEN_AUTHORITY_TYPES,
    sourceAuthorityFqns: Map<String, String> = ERASED_AUTHORITY_FQNS,
): Set<String> {
    val violations = mutableSetOf<String>()
    data class VisitKey(val type: Class<*>, val bindings: String)
    val visited = mutableSetOf<VisitKey>()
    val visitedTypeEdges = mutableSetOf<String>()
    val reachedTypeNames = mutableSetOf<String>()
    lateinit var visitClass: (Class<*>, Map<TypeVariable<*>, Type>, String, Boolean) -> Unit

    fun isLibrary(type: Class<*>): Boolean = type.name.startsWith("java.") || type.name.startsWith("kotlin.") ||
        type.name.startsWith("io.github.") || type.name.startsWith("org.jetbrains.")

    fun visit(type: Type, bindings: Map<TypeVariable<*>, Type>, origin: String) {
        if (type !is Class<*> && !visitedTypeEdges.add("${System.identityHashCode(type)}:${bindings.signature()}")) return
        when (type) {
            is TypeVariable<*> -> {
                bindings[type]?.let { visit(it, bindings, origin) }
                    ?: type.bounds.forEach { visit(it, bindings, origin) }
            }
            is Class<*> -> {
                if (type.isArray) {
                    visit(type.componentType, bindings, "$origin[]")
                } else {
                    visitClass(type, bindings, origin, false)
                }
            }
            is ParameterizedType -> {
                type.ownerType?.let { visit(it, bindings, "$origin owner") }
                type.actualTypeArguments.forEachIndexed { index, argument ->
                    visit(argument, bindings, "$origin arg[$index]")
                }
                val raw = type.rawType as? Class<*>
                if (raw != null) visitClass(raw, bindingsFor(type, bindings), origin, false)
            }
            is WildcardType -> {
                type.upperBounds.forEach { visit(it, bindings, "$origin upper") }
                type.lowerBounds.forEach { visit(it, bindings, "$origin lower") }
            }
            is GenericArrayType -> visit(type.genericComponentType, bindings, "$origin[]")
        }
    }

    visitClass = fun(type: Class<*>, bindings: Map<TypeVariable<*>, Type>, origin: String, entry: Boolean) {
        type.authorityLabel(forbiddenTypes)?.takeUnless { entry }?.let {
            violations += it
            return
        }
        if (type.isArray || isLibrary(type)) return
        reachedTypeNames += type.name
        val key = VisitKey(type, bindings.signature())
        if (!visited.add(key)) return
        type.fields.filter { Modifier.isPublic(it.modifiers) }.forEach { visit(it.genericType, bindings, "$origin field ${it.name}") }
        type.methods.filter { Modifier.isPublic(it.modifiers) }.forEach { method ->
            visit(method.genericReturnType, bindings, "$origin method ${method.name} return")
            method.authorityGenericParameterTypes().forEachIndexed { index, parameter ->
                visit(parameter, bindings, "$origin method ${method.name} arg[$index]")
            }
        }
        type.genericSuperclass?.let { visit(it, bindings, "$origin superclass") }
        type.genericInterfaces.forEach { visit(it, bindings, "$origin interface") }
    }

    visitClass(this, emptyMap(), name, allowForbiddenEntry)
    sourceIndex?.publicSignatureAuthorityViolations(
        reachedTypeNames,
        authorityFqns = sourceAuthorityFqns,
    )?.let { violations += it }
    return violations
}

private fun Map<String, String>.sourceAuthorityFqns(): Map<String, String> =
    entries.associate { (binaryName, _) ->
        binaryName.substringAfterLast('.').substringAfterLast('$') to binaryName.replace('$', '.')
    }

private fun Map<TypeVariable<*>, Type>.signature(): String =
    entries.sortedBy { it.key.name }.joinToString { "${it.key.name}=${it.value.render()}" }

private fun bindingsFor(type: Type, inherited: Map<TypeVariable<*>, Type>): Map<TypeVariable<*>, Type> {
    if (type !is ParameterizedType || type.rawType !is Class<*>) return inherited
    val raw = type.rawType as Class<*>
    val arguments = type.actualTypeArguments.map {
        if (it is TypeVariable<*> && it in inherited) inherited.getValue(it) else it
    }
    return inherited + raw.typeParameters.zip(arguments)
}

private enum class SourceDeclarationKind { CLASS, INTERFACE, OBJECT, FUNCTION, PROPERTY, TYPEALIAS, PARAMETER, TYPE_PARAMETER }

private enum class SourceScopeKind { FILE, CLASS, FUNCTION, BLOCK, INITIALIZER, INTERPOLATION }

private enum class SourceNamespace { TYPE, VALUE }

private data class SourceImport(val alias: String, val target: String, val tokenIndices: Set<Int>)

private data class SourceTypeParameter(val name: String, val nameTokenIndex: Int, val endOffset: Int)

private data class RawSourceDeclaration(
    val kind: SourceDeclarationKind,
    val tokenIndex: Int,
    val nameTokenIndex: Int,
    val name: String,
    val bodyTokenIndex: Int?,
    val endTokenIndex: Int,
    val braceDepth: Int,
)

private data class LocatedSourceScope(
    val id: Int,
    val kind: SourceScopeKind,
    val parentId: Int?,
    val startOffset: Int,
    val endOffset: Int,
)

private data class LocatedSourceDeclaration(
    val identity: String,
    val kind: SourceDeclarationKind,
    val namespace: SourceNamespace,
    val name: String,
    val qualifiedName: String,
    val ownerQualifiedName: String?,
    val tokenIndex: Int,
    val nameTokenIndex: Int,
    val scopeId: Int,
    val startOffset: Int,
    val endOffset: Int,
    val headerEndTokenIndex: Int,
    val visibility: String,
    val typeParameters: List<SourceTypeParameter> = emptyList(),
    val aliasRhsStartTokenIndex: Int? = null,
    val aliasRhsEndTokenIndex: Int? = null,
    val typeStartTokenIndex: Int? = null,
    val typeEndTokenIndex: Int? = null,
    val isConstructorProperty: Boolean = false,
)

private data class IndexedSourceUnit(
    val path: String,
    val source: String,
    val tokens: List<LexToken>,
    val lineStarts: List<Int>,
    val packageName: String,
    val imports: List<SourceImport>,
    val scopes: List<LocatedSourceScope>,
    val declarations: List<LocatedSourceDeclaration>,
    val ignoredTokenIndices: Set<Int>,
    val declarationNameIndices: Set<Int>,
    val delimiterPairs: Map<Int, Int>,
) {
    fun lineOf(offset: Int): Int {
        var low = 0
        var high = lineStarts.size
        while (low + 1 < high) {
            val middle = (low + high) / 2
            if (lineStarts[middle] <= offset) low = middle else high = middle
        }
        return low + 1
    }

    fun scopeAt(offset: Int): LocatedSourceScope = scopes
        .filter { it.startOffset <= offset && offset < it.endOffset }
        .maxByOrNull { it.startOffset } ?: scopes.first()

    fun scopeChain(scopeId: Int): List<Int> = buildList {
        var current: Int? = scopeId
        while (current != null) {
            add(current)
            current = scopes.first { it.id == current }.parentId
        }
    }
}

private data class ResolvedSourceType(
    val identity: String?,
    val cycle: List<String> = emptyList(),
    val resolvedIdentities: Set<String> = emptySet(),
    val ambiguity: List<String> = emptyList(),
    val namespace: SourceNamespace? = null,
)

private val ResolvedSourceType.allIdentities: Set<String>
    get() = resolvedIdentities + listOfNotNull(identity)

private enum class SourceReferenceKind { TYPE, EXPRESSION }

private data class SourceReference(val index: Int, val spelling: String, val kind: SourceReferenceKind)

private data class SourceJvmClassification(
    val sourceQualifiedName: String,
    val executableOwner: String?,
    val emittedBinaryName: String?,
    val loadable: Boolean,
    val diagnostic: String?,
)

private fun classifySourceJvmMapping(
    unit: KotlinSourceUnit,
    declaration: LocatedSourceDeclaration,
    loader: ClassLoader,
): SourceJvmClassification {
    val ownerChain = generateSequence(declaration) { current ->
        current.ownerQualifiedName?.let { ownerName ->
            unit.indexed.declarations.firstOrNull { it.qualifiedName == ownerName }
        }
    }
    val executableOwner = ownerChain.drop(1).firstOrNull { it.kind == SourceDeclarationKind.FUNCTION }?.qualifiedName
    val executableScope = unit.indexed.scopeChain(declaration.scopeId).any { scopeId ->
        unit.indexed.scopes.first { it.id == scopeId }.kind in setOf(
            SourceScopeKind.FUNCTION,
            SourceScopeKind.BLOCK,
            SourceScopeKind.INITIALIZER,
            SourceScopeKind.INTERPOLATION,
        )
    }
    val emitted = if (executableOwner != null || executableScope) {
        null
    } else {
        val qualifiedName = "${unit.packageName}.${declaration.qualifiedName}".trim('.')
        if (unit.packageName.isEmpty()) {
            qualifiedName.replace('.', '$')
        } else {
            "${unit.packageName}.${declaration.qualifiedName.replace('.', '$')}"
        }
    }
    val loadable = emitted?.let { runCatching { Class.forName(it, false, loader) }.isSuccess } ?: false
    return SourceJvmClassification(
        sourceQualifiedName = declaration.qualifiedName,
        executableOwner = executableOwner,
        emittedBinaryName = emitted,
        loadable = loadable,
        diagnostic = emitted?.let { null } ?: "no stable emitted JVM name for " +
            (if (executableOwner != null) "function-local" else "executable-local") +
            " ${declaration.qualifiedName}",
    )
}

private fun KotlinSourceUnit.scanResult(): ScanResult {
    val classDeclarations = indexed.declarations.filter {
        it.kind in setOf(SourceDeclarationKind.CLASS, SourceDeclarationKind.INTERFACE, SourceDeclarationKind.OBJECT)
    }.map { Declaration(path, it.qualifiedName, it.startOffset) }
    val topLevelRecords = indexed.declarations.filter {
        it.scopeId == 0 &&
            it.kind in setOf(SourceDeclarationKind.FUNCTION, SourceDeclarationKind.PROPERTY, SourceDeclarationKind.TYPEALIAS) &&
            !it.isConstructorProperty
    }.map { declaration ->
        val kind = when (declaration.kind) {
            SourceDeclarationKind.FUNCTION -> "fun"
            SourceDeclarationKind.PROPERTY -> "val"
            SourceDeclarationKind.TYPEALIAS -> "typealias"
            else -> error("unexpected non-class declaration ${declaration.kind}")
        }
        "$path|$kind ${declaration.name}"
    }
    return ScanResult(tokens, classDeclarations, topLevelRecords)
}

private class KotlinSourceUnit(val path: String, val source: String) {
    val indexed: IndexedSourceUnit = buildIndexedSourceUnit(path, source)
    val tokens: List<LexToken> = indexed.tokens
    val packageName: String = indexed.packageName
    val imports: Map<String, String> = indexed.imports.associate { it.alias to it.target }
    val starImports: Set<String> = indexed.imports.map { it.target }
        .filter { it.endsWith(".*") }.map { it.removeSuffix(".*") }.toSet()
    val typeAliases: Map<String, String> = indexed.declarations
        .filter { it.kind == SourceDeclarationKind.TYPEALIAS }
        .associate { declaration ->
            val start = declaration.aliasRhsStartTokenIndex ?: declaration.headerEndTokenIndex
            val end = declaration.aliasRhsEndTokenIndex ?: start
            declaration.name to indexed.tokens.subList(start, end).joinToString("") { it.text }
        }
}

private class KotlinSourceIndex(private val units: List<KotlinSourceUnit>) {
    private val unitsByPath = units.associateBy { it.path }
    private val declarations = units.flatMap { unit -> unit.indexed.declarations.map { unit to it } }
    private val declarationsByIdentity = declarations.groupBy { (_, declaration) ->
        declaration.identity
    }
    private val declarationsByPackageAndName = declarations.groupBy { (unit, declaration) ->
        unit.packageName to declaration.name
    }

    fun discoveredDeclarations(): List<String> = units.flatMap { unit ->
        unit.indexed.declarations.filter {
            it.kind in setOf(SourceDeclarationKind.CLASS, SourceDeclarationKind.INTERFACE, SourceDeclarationKind.OBJECT)
        }.map { "${unit.path}|${it.qualifiedName}" }
    }

    fun discoveredNonClassDeclarations(): List<String> = units.flatMap { unit ->
        unit.indexed.declarations.filter {
            it.scopeId == 0 &&
                it.kind in setOf(SourceDeclarationKind.FUNCTION, SourceDeclarationKind.PROPERTY, SourceDeclarationKind.TYPEALIAS) &&
                !it.isConstructorProperty
        }.map { declaration ->
            val kind = when (declaration.kind) {
                SourceDeclarationKind.FUNCTION -> "fun"
                SourceDeclarationKind.PROPERTY -> "val"
                SourceDeclarationKind.TYPEALIAS -> "typealias"
                else -> error("unexpected inventory kind ${declaration.kind}")
            }
            "${unit.path}|$kind ${declaration.name}"
        }
    }

    fun jvmClassifications(loader: ClassLoader): List<SourceJvmClassification> = declarations.mapNotNull { (unit, declaration) ->
        declaration.takeIf {
            it.kind in setOf(SourceDeclarationKind.CLASS, SourceDeclarationKind.INTERFACE, SourceDeclarationKind.OBJECT)
        }?.let { classifySourceJvmMapping(unit, it, loader) }
    }

    fun indexingReferences(): List<String> = units.flatMap { unit -> indexingReferences(unit) }

    fun indexingReferences(unit: KotlinSourceUnit): List<String> {
        val relative = unit.path.replace('\\', '/')
        val ownerAllowed = relative in ADMITTED_INDEXING_PATHS && unit.packageName == "com.plainbase.domain.service"
        if (ownerAllowed) return emptyList()
        val references = buildList {
            addAll(typeReferences(unit).map { SourceReference(it.first, it.second, SourceReferenceKind.TYPE) })
            addAll(expressionReferences(unit).map { SourceReference(it.first, it.second, SourceReferenceKind.EXPRESSION) })
        }
            .distinctBy { it.index to it.spelling }
            .sortedBy { it.index }
        return references.mapNotNull { reference ->
            val resolved = when (reference.kind) {
                SourceReferenceKind.TYPE -> resolveType(unit, reference.index, reference.spelling)
                SourceReferenceKind.EXPRESSION -> resolveExpressionReference(unit, reference.index)
            }
            when {
                resolved.ambiguity.isNotEmpty() ->
                    "$relative:${unit.indexed.lineOf(unit.tokens[reference.index].offset)}:${unit.tokens[reference.index].offset}:" +
                        "${reference.spelling} -> ambiguous ${resolved.ambiguity.joinToString(", ")}"
                resolved.cycle.isNotEmpty() ->
                    "$relative:${unit.indexed.lineOf(unit.tokens[reference.index].offset)}:${unit.tokens[reference.index].offset}:" +
                        "${reference.spelling} -> alias cycle ${resolved.cycle.joinToString(" -> ")}"
                resolved.allIdentities.any { it in INDEXING_FQNS.values } ->
                    "$relative:${unit.indexed.lineOf(unit.tokens[reference.index].offset)}:${unit.tokens[reference.index].offset}:" +
                        "${reference.spelling} -> ${resolved.allIdentities.filter { it in INDEXING_FQNS.values }.joinToString(", ")}"
                else -> null
            }
        }
    }

    fun publicSignatureAuthorityViolations(
        reachedTypeNames: Set<String>? = null,
        authorityFqns: Map<String, String> = ERASED_AUTHORITY_FQNS,
    ): Set<String> {
        val reached = reachedTypeNames?.map { it.replace('$', '.') }?.toSet()
        return units.flatMapTo(mutableSetOf()) { unit ->
            val candidates = if (reached == null) {
                unit.indexed.declarations.filter {
                    it.kind in setOf(SourceDeclarationKind.FUNCTION, SourceDeclarationKind.PROPERTY) &&
                        it.visibility == "public"
                }
            } else {
                val owners = unit.indexed.declarations.filter {
                    it.kind in setOf(SourceDeclarationKind.CLASS, SourceDeclarationKind.INTERFACE, SourceDeclarationKind.OBJECT) &&
                        "${unit.packageName}.${it.qualifiedName}".trim('.') in reached
                }.map { it.qualifiedName }.toSet()
                unit.indexed.declarations.filter {
                    it.kind in setOf(SourceDeclarationKind.FUNCTION, SourceDeclarationKind.PROPERTY) &&
                        it.visibility == "public" && it.ownerQualifiedName in owners
                }
            }
            candidates.flatMapTo(mutableSetOf()) { declaration ->
                val signatureFindings = signatureTypeReferences(unit, declaration).mapNotNull { (index, spelling) ->
                    val resolved = resolveType(unit, index, spelling)
                    when {
                        resolved.ambiguity.isNotEmpty() ->
                            "${unit.path}:${unit.indexed.lineOf(unit.tokens[index].offset)}:${declaration.qualifiedName}:" +
                                "$spelling -> ambiguous ${resolved.ambiguity.joinToString(", ")}"
                        resolved.cycle.isNotEmpty() ->
                            "${unit.path}:${unit.indexed.lineOf(unit.tokens[index].offset)}:${declaration.qualifiedName}:" +
                                "$spelling -> alias cycle ${resolved.cycle.joinToString(" -> ")}"
                        resolved.allIdentities.any { it in authorityFqns.values } ->
                            "${unit.path}:${unit.indexed.lineOf(unit.tokens[index].offset)}:${declaration.qualifiedName}:" +
                                "$spelling -> ${resolved.allIdentities.filter { it in authorityFqns.values }.joinToString(", ")}"
                        else -> null
                    }
                }
                val inferredFinding = inferredAuthorityReference(unit, declaration, authorityFqns)?.let { (index, spelling, target) ->
                    "${unit.path}:${unit.indexed.lineOf(unit.tokens[index].offset)}:${declaration.qualifiedName}:" +
                        "$spelling -> $target"
                }
                signatureFindings + listOfNotNull(inferredFinding)
            }
        }
    }

    private fun inferredAuthorityReference(
        unit: KotlinSourceUnit,
        declaration: LocatedSourceDeclaration,
        authorityFqns: Map<String, String>,
    ): Triple<Int, String, String>? {
        val expression = inferredExpressionRange(unit, declaration) ?: return null
        val (start, limit) = expression
        val call = directConstructorCall(unit, start, limit) ?: return null
        val (callStart, spelling) = call
        val resolved = resolveExpressionReference(unit, callStart)
        val target = resolved.identity?.takeIf { resolved.namespace == SourceNamespace.TYPE && it in authorityFqns.values }
            ?: return null
        return Triple(callStart, spelling, target)
    }

    private fun inferredExpressionRange(
        unit: KotlinSourceUnit,
        declaration: LocatedSourceDeclaration,
    ): Pair<Int, Int>? {
        if (declaration.visibility != "public") return null
        if (declaration.kind == SourceDeclarationKind.PROPERTY && declaration.typeStartTokenIndex != null) return null
        if (declaration.kind !in setOf(SourceDeclarationKind.FUNCTION, SourceDeclarationKind.PROPERTY)) return null
        if (declaration.kind == SourceDeclarationKind.FUNCTION && declaration.hasExplicitReturnType(unit)) return null
        if (unit.tokens.getOrNull(declaration.headerEndTokenIndex)?.text != "=") return null
        val end = unit.tokens.indexOfFirst { it.offset >= declaration.endOffset }.takeIf { it >= 0 } ?: unit.tokens.size
        var start = declaration.headerEndTokenIndex + 1
        var limit = end
        while (start < limit && unit.tokens[start].text == "(") {
            val close = unit.indexed.delimiterPairs[start] ?: return null
            if (close != limit - 1) break
            start++
            limit = close
        }
        return start to limit
    }

    private fun LocatedSourceDeclaration.hasExplicitReturnType(unit: KotlinSourceUnit): Boolean {
        val open = (nameTokenIndex + 1 until headerEndTokenIndex).firstOrNull { unit.tokens[it].text == "(" } ?: return true
        val close = unit.indexed.delimiterPairs[open] ?: return true
        return (close + 1 until headerEndTokenIndex).any { unit.tokens[it].text == ":" }
    }

    private fun directConstructorCall(unit: KotlinSourceUnit, start: Int, limit: Int): Pair<Int, String>? {
        if (start >= limit || !unit.tokens[start].text.isIdentifier()) return null
        val spelling = qualifiedSpelling(unit, start, limit)
        val callOpen = qualifiedEnd(unit, start).takeIf { it < limit && unit.tokens[it].text == "(" } ?: return null
        val callClose = unit.indexed.delimiterPairs[callOpen] ?: return null
        if (callClose != limit - 1) return null
        return start to spelling
    }

    private fun typeReferences(unit: KotlinSourceUnit): List<Pair<Int, String>> {
        val ranges = unit.indexed.declarations.flatMap { declaration ->
            signatureTypeRanges(unit, declaration) + listOfNotNull(declaration.supertypeRange(unit))
        } + unit.indexed.declarations.filter { it.kind == SourceDeclarationKind.TYPEALIAS }.mapNotNull { declaration ->
            val start = declaration.aliasRhsStartTokenIndex ?: return@mapNotNull null
            start to (declaration.aliasRhsEndTokenIndex ?: start)
        }
        val references = ranges.flatMap { (start, end) -> typeReferencesInRegion(unit, start, end) }
        val classLiterals = unit.tokens.indices.mapNotNull { index ->
            if (unit.tokens.getOrNull(index + 1)?.text == ":" &&
                unit.tokens.getOrNull(index + 2)?.text == ":" &&
                unit.tokens.getOrNull(index + 3)?.text == "class"
            ) {
                val start = qualifiedStart(unit, index)
                if (start == index) index to qualifiedSpelling(unit, index) else null
            } else {
                null
            }
        }
        return (references + classLiterals).distinctBy { it.first }
    }

    private fun signatureTypeReferences(
        unit: KotlinSourceUnit,
        declaration: LocatedSourceDeclaration,
    ): List<Pair<Int, String>> = signatureTypeRanges(unit, declaration).flatMap { (start, end) ->
        typeReferencesInRegion(unit, start, end)
    }.distinctBy { it.first }

    private fun typeReferencesInRegion(unit: KotlinSourceUnit, start: Int, end: Int): List<Pair<Int, String>> =
        DeclaredTypeReferenceWalker(unit).references(start, end)

    private fun LocatedSourceDeclaration.supertypeRange(unit: KotlinSourceUnit): Pair<Int, Int>? {
        if (kind !in setOf(SourceDeclarationKind.CLASS, SourceDeclarationKind.INTERFACE, SourceDeclarationKind.OBJECT)) {
            return null
        }
        val colon = (nameTokenIndex + 1 until headerEndTokenIndex).firstOrNull { index ->
            unit.tokens[index].text == ":" && unit.tokens.getOrNull(index - 1)?.text != ":"
        } ?: return null
        return colon + 1 to headerEndTokenIndex
    }

    private fun signatureTypeRanges(
        unit: KotlinSourceUnit,
        declaration: LocatedSourceDeclaration,
    ): List<Pair<Int, Int>> {
        declaration.typeStartTokenIndex?.let { start ->
            return listOf(start to (declaration.typeEndTokenIndex ?: declaration.headerEndTokenIndex))
        }
        if (declaration.kind != SourceDeclarationKind.FUNCTION) return emptyList()
        val ranges = mutableListOf<Pair<Int, Int>>()
        val receiverStart = functionReceiverStart(unit, declaration)
        if (receiverStart != null) ranges += receiverStart
        unit.indexed.declarations.filter { parameter ->
            parameter.ownerQualifiedName == declaration.qualifiedName &&
                parameter.kind == SourceDeclarationKind.PARAMETER
        }.forEach { parameter ->
            parameter.typeStartTokenIndex?.let { start ->
                ranges += start to (parameter.typeEndTokenIndex ?: parameter.headerEndTokenIndex)
            }
        }
        val open = (declaration.nameTokenIndex + 1 until declaration.headerEndTokenIndex)
            .firstOrNull { unit.tokens[it].text == "(" }
        val close = open?.let { unit.indexed.delimiterPairs[it] }
        val returnColon = close?.let { closeIndex ->
            (closeIndex + 1 until declaration.headerEndTokenIndex).firstOrNull { unit.tokens[it].text == ":" }
        }
        returnColon?.let { colon -> ranges += (colon + 1) to declaration.headerEndTokenIndex }
        return ranges
    }

    private fun functionReceiverStart(
        unit: KotlinSourceUnit,
        declaration: LocatedSourceDeclaration,
    ): Pair<Int, Int>? {
        val afterTypeParameters = unit.tokens.getOrNull(declaration.tokenIndex + 1)?.let { token ->
            if (token.text != "<") declaration.tokenIndex + 1 else angleClose(unit.tokens, declaration.tokenIndex + 1)?.plus(1)
        } ?: return null
        if (afterTypeParameters >= declaration.nameTokenIndex) return null
        val dot = (afterTypeParameters until declaration.nameTokenIndex).lastOrNull { unit.tokens[it].text == "." }
            ?: return null
        return afterTypeParameters to dot
    }

    @Suppress("ReturnCount")
    private fun resolveType(
        unit: KotlinSourceUnit,
        index: Int,
        spelling: String,
        stack: Set<String> = emptySet(),
        substitutions: Map<String, ResolvedSourceType> = emptyMap(),
    ): ResolvedSourceType {
        val parts = spelling.split('.')
        substitutions[spelling]?.let { return it }
        val typeArguments = typeArgumentsAt(unit, index, stack, substitutions)
        if (parts.size > 1) {
            return resolveQualifiedType(unit, parts.joinToString("."), stack, typeArguments, offset = unit.tokens[index].offset)
        }
        val name = parts.single()
        val offset = unit.tokens[index].offset
        val lexical = unit.indexed.declarations.filter { declaration ->
            declaration.namespace == SourceNamespace.TYPE && declaration.name == name && visibleAt(unit, declaration, offset)
        }
        if (lexical.isNotEmpty()) {
            val deepest = lexical.maxOf { scopeDepth(unit, it.scopeId) }
            val selected = lexical.filter { scopeDepth(unit, it.scopeId) == deepest }
            if (selected.size > 1) {
                return ResolvedSourceType(null, ambiguity = selected.map { it.identity }.sorted())
            }
            return resolveIdentity(selected.single().identity, stack, typeArguments)
        }
        unit.imports[name]?.let { return resolveIdentity(it, stack, typeArguments) }
        topLevelCandidates(unit.packageName, name).let { candidates ->
            if (candidates.size == 1) return resolveIdentity(candidates.single(), stack, typeArguments)
            if (candidates.size > 1) return ResolvedSourceType(null, ambiguity = candidates.sorted())
        }
        val starCandidates = unit.starImports.flatMap { packageName -> topLevelCandidates(packageName, name) }.distinct()
        if (starCandidates.size == 1) return resolveIdentity(starCandidates.single(), stack, typeArguments)
        if (starCandidates.size > 1) {
            return ResolvedSourceType(null, ambiguity = starCandidates.sorted())
        }
        return ResolvedSourceType(null)
    }

    fun resolveExpressionReference(unit: KotlinSourceUnit, index: Int): ResolvedSourceType {
        val spelling = qualifiedSpelling(unit, index)
        if (!spelling.contains('.')) {
            val value = resolveValue(unit, index, spelling)
            if (value != null) return ResolvedSourceType(value.identity, namespace = SourceNamespace.VALUE)
        }
        return resolveType(unit, index, spelling).copy(namespace = SourceNamespace.TYPE)
    }

    private fun resolveValue(unit: KotlinSourceUnit, index: Int, name: String): LocatedSourceDeclaration? {
        val offset = unit.tokens[index].offset
        val candidates = unit.indexed.declarations.filter { declaration ->
            declaration.namespace == SourceNamespace.VALUE && declaration.name == name && visibleAt(unit, declaration, offset)
        }
        if (candidates.isEmpty()) return null
        val deepest = candidates.maxOf { scopeDepth(unit, it.scopeId) }
        return candidates.filter { scopeDepth(unit, it.scopeId) == deepest }.maxByOrNull { it.startOffset }
    }

    private fun expressionReferences(unit: KotlinSourceUnit): List<Pair<Int, String>> =
        unit.tokens.indices.mapNotNull { index ->
            val token = unit.tokens[index]
            if (!token.text.isIdentifier() || index in unit.indexed.ignoredTokenIndices || index in unit.indexed.declarationNameIndices) {
                return@mapNotNull null
            }
            val start = qualifiedStart(unit, index)
            if (start != index || (index > 0 && unit.tokens[index - 1].text == ".")) return@mapNotNull null
            val end = qualifiedEnd(unit, index)
            if (unit.tokens.getOrNull(end)?.text != "(") return@mapNotNull null
            index to qualifiedSpelling(unit, index)
        }

    fun resolveReference(unit: KotlinSourceUnit, index: Int): ResolvedSourceType =
        resolveType(unit, index, qualifiedSpelling(unit, index))

    @Suppress("ReturnCount")
    private fun resolveQualifiedType(
        unit: KotlinSourceUnit,
        spelling: String,
        stack: Set<String>,
        typeArguments: List<ResolvedSourceType>,
        offset: Int,
    ): ResolvedSourceType {
        val first = spelling.substringBefore('.')
        val suffix = spelling.removePrefix(first).removePrefix(".")
        val lexical = unit.indexed.declarations.filter { declaration ->
            declaration.namespace == SourceNamespace.TYPE && declaration.name == first &&
                visibleAt(unit, declaration, offset)
        }
        val deepest = lexical.maxOfOrNull { scopeDepth(unit, it.scopeId) }
        val selectedLexical = lexical.filter { scopeDepth(unit, it.scopeId) == deepest }
        val lexicalCandidates = selectedLexical.map { "${it.identity}.$suffix" }.filter { it in declarationsByIdentity }.distinct()
        if (lexicalCandidates.size == 1) return resolveIdentity(lexicalCandidates.single(), stack, typeArguments)
        if (lexicalCandidates.size > 1) return ResolvedSourceType(null, ambiguity = lexicalCandidates.sorted())
        if (selectedLexical.isNotEmpty()) return ResolvedSourceType(null)
        val imported = unit.imports[first]
        if (imported != null) {
            val qualified = if (suffix.isEmpty()) imported else "$imported.$suffix"
            if (qualified in declarationsByIdentity) return resolveIdentity(qualified, stack, typeArguments)
        }
        val direct = listOf(spelling, "${unit.packageName}.$spelling".trim('.'))
            .filter { it in declarationsByIdentity }
            .distinct()
        if (direct.size == 1) return resolveIdentity(direct.single(), stack, typeArguments)
        if (direct.size > 1) return ResolvedSourceType(null, ambiguity = direct.sorted())
        return ResolvedSourceType(null)
    }

    private fun resolveIdentity(
        identity: String,
        stack: Set<String>,
        typeArguments: List<ResolvedSourceType> = emptyList(),
    ): ResolvedSourceType {
        if (identity in stack) return ResolvedSourceType(null, cycle = stack.toList() + identity)
        val declaration = declarationsByIdentity[identity]?.firstOrNull() ?: return ResolvedSourceType(null)
        if (declaration.second.kind != SourceDeclarationKind.TYPEALIAS) {
            return ResolvedSourceType(identity, namespace = SourceNamespace.TYPE)
        }
        val alias = declaration.second
        val aliasUnit = declaration.first
        val start = alias.aliasRhsStartTokenIndex ?: return ResolvedSourceType(identity)
        val end = alias.aliasRhsEndTokenIndex ?: return ResolvedSourceType(identity)
        val substitutions = alias.typeParameters.mapIndexedNotNull { index, parameter ->
            typeArguments.getOrNull(index)?.let { parameter.name to it }
        }.toMap()
        return resolveTypeExpression(aliasUnit, start, end, stack + identity, substitutions).let { resolved ->
            resolved.copy(identity = resolved.allIdentities.singleOrNull(), namespace = SourceNamespace.TYPE)
        }
    }

    private fun resolveTypeExpression(
        unit: KotlinSourceUnit,
        start: Int,
        end: Int,
        stack: Set<String>,
        substitutions: Map<String, ResolvedSourceType> = emptyMap(),
    ): ResolvedSourceType {
        val resolved = typeReferencesInRegion(unit, start, end).map { (index, spelling) ->
            substitutions[spelling] ?: resolveType(unit, index, spelling, stack, substitutions)
        }
        return mergeResolved(resolved)
    }

    private fun typeArgumentsAt(
        unit: KotlinSourceUnit,
        index: Int,
        stack: Set<String>,
        substitutions: Map<String, ResolvedSourceType>,
    ): List<ResolvedSourceType> {
        val open = qualifiedEnd(unit, index).takeIf { unit.tokens.getOrNull(it)?.text == "<" } ?: return emptyList()
        val close = angleClose(unit.tokens, open) ?: return emptyList()
        val ranges = typeArgumentRanges(unit, open, close)
        return ranges.map { (from, to) -> resolveTypeExpression(unit, from, to, stack, substitutions) }
    }

    private fun typeArgumentRanges(unit: KotlinSourceUnit, open: Int, close: Int): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        var start = open + 1
        var angle = 0
        var parentheses = 0
        var brackets = 0
        for (cursor in open + 1 until close) {
            when (unit.tokens[cursor].text) {
                "<" -> angle++
                ">" -> if (angle > 0) angle--
                "(" -> parentheses++
                ")" -> if (parentheses > 0) parentheses--
                "[" -> brackets++
                "]" -> if (brackets > 0) brackets--
                "," -> if (angle == 0 && parentheses == 0 && brackets == 0) {
                    ranges += start to cursor
                    start = cursor + 1
                }
            }
        }
        ranges += start to close
        return ranges
    }

    private fun topLevelCandidates(packageName: String, name: String): List<String> =
        declarationsByPackageAndName[packageName to name].orEmpty()
            .filter { it.second.ownerQualifiedName == null && it.second.namespace == SourceNamespace.TYPE }
            .map { it.second.identity }
            .distinct()

    private fun mergeResolved(resolved: List<ResolvedSourceType>): ResolvedSourceType {
        val identities = resolved.flatMap { it.allIdentities }.toSet()
        val cycle = resolved.firstOrNull { it.cycle.isNotEmpty() }?.cycle.orEmpty()
        val ambiguity = resolved.flatMap { it.ambiguity }.distinct().sorted()
        return ResolvedSourceType(
            identity = identities.singleOrNull(),
            cycle = cycle,
            resolvedIdentities = identities,
            ambiguity = ambiguity,
        )
    }

    private fun visibleAt(unit: KotlinSourceUnit, declaration: LocatedSourceDeclaration, offset: Int): Boolean {
        val scope = unit.indexed.scopes.first { it.id == declaration.scopeId }
        val visibilityEnd = when {
            declaration.kind == SourceDeclarationKind.TYPE_PARAMETER -> declaration.endOffset
            declaration.kind == SourceDeclarationKind.PARAMETER -> declaration.endOffset
            else -> scope.endOffset
        }
        val scopeWideFunction = declaration.kind == SourceDeclarationKind.FUNCTION &&
            scope.kind in setOf(SourceScopeKind.FILE, SourceScopeKind.CLASS)
        if ((!scopeWideFunction && declaration.startOffset >= offset) || offset >= visibilityEnd) return false
        return declaration.scopeId == 0 || unit.indexed.scopeChain(unit.indexed.scopeAt(offset).id).contains(declaration.scopeId)
    }

    private fun scopeDepth(unit: KotlinSourceUnit, scopeId: Int): Int = unit.indexed.scopeChain(scopeId).size
}

private class DeclaredTypeReferenceWalker(private val unit: KotlinSourceUnit) {
    private val references = mutableListOf<Pair<Int, String>>()

    fun references(start: Int, end: Int): List<Pair<Int, String>> {
        parseSequence(start, end)
        return references.distinctBy { it.first }
    }

    private fun parseSequence(from: Int, to: Int) {
        var cursor = from
        while (cursor < to) {
            val previous = cursor
            cursor = parseType(cursor, to)
            if (cursor <= previous) cursor = previous + 1
            while (cursor < to && unit.tokens[cursor].text in setOf("?", "!", "&", ",", "->")) cursor++
        }
    }

    private fun parseFunctionParameters(from: Int, to: Int) {
        splitSegments(from, to).forEach { (segmentStart, segmentEnd) ->
            val colon = topLevelIndex(segmentStart, segmentEnd, ":")
            parseSequence((colon ?: segmentStart) + if (colon == null) 0 else 1, segmentEnd)
        }
    }

    private fun parseType(from: Int, to: Int): Int {
        var cursor = from
        while (cursor < to && unit.tokens[cursor].text in setOf("in", "out", "reified")) cursor++
        if (cursor >= to) return cursor
        if (unit.tokens[cursor].text == "@") return parseType(qualifiedEndWithin(cursor + 1, to), to)
        if (unit.tokens[cursor].text == "(") return parseParenthesizedType(cursor, to)
        if (!unit.tokens[cursor].text.isIdentifier()) return cursor + 1
        val spelling = qualifiedSpelling(unit, cursor, to)
        references += cursor to spelling
        cursor = qualifiedEndWithin(cursor, to)
        if (cursor < to && unit.tokens[cursor].text == "<") {
            val close = angleClose(unit.tokens, cursor)
            if (close != null && close < to) {
                splitSegments(cursor + 1, close).forEach { (segmentStart, segmentEnd) ->
                    parseSequence(segmentStart, segmentEnd)
                }
                cursor = close + 1
            }
        }
        if (cursor + 1 < to && unit.tokens[cursor].text == "." && unit.tokens[cursor + 1].text == "(") {
            val open = cursor + 1
            val close = unit.indexed.delimiterPairs[open]
            if (close != null && close < to && close + 1 < to && unit.tokens[close + 1].text == "->") {
                parseFunctionParameters(open + 1, close)
                return parseType(close + 2, to)
            }
        }
        return cursor + if (cursor < to && unit.tokens[cursor].text == "?") 1 else 0
    }

    private fun parseParenthesizedType(open: Int, limit: Int): Int {
        val close = unit.indexed.delimiterPairs[open] ?: return open + 1
        if (close >= limit) return open + 1
        return if (close + 1 < limit && unit.tokens[close + 1].text == "->") {
            parseFunctionParameters(open + 1, close)
            parseType(close + 2, limit)
        } else {
            parseSequence(open + 1, close)
            close + if (close + 1 < limit && unit.tokens[close + 1].text == "?") 2 else 1
        }
    }

    private fun qualifiedEndWithin(index: Int, limit: Int): Int {
        var cursor = index + 1
        while (cursor + 1 < limit && unit.tokens[cursor].text == "." && unit.tokens[cursor + 1].text.isIdentifier()) {
            cursor += 2
        }
        return cursor
    }

    private fun topLevelIndex(from: Int, to: Int, spelling: String): Int? {
        var angle = 0
        var parentheses = 0
        var brackets = 0
        for (index in from until to) {
            when (unit.tokens[index].text) {
                "<" -> angle++
                ">" -> if (angle > 0) angle--
                "(" -> parentheses++
                ")" -> if (parentheses > 0) parentheses--
                "[" -> brackets++
                "]" -> if (brackets > 0) brackets--
                spelling -> if (angle == 0 && parentheses == 0 && brackets == 0) return index
            }
        }
        return null
    }

    private fun splitSegments(from: Int, to: Int): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var segmentStart = from
        var angle = 0
        var parentheses = 0
        var brackets = 0
        for (index in from until to) {
            when (unit.tokens[index].text) {
                "<" -> angle++
                ">" -> if (angle > 0) angle--
                "(" -> parentheses++
                ")" -> if (parentheses > 0) parentheses--
                "[" -> brackets++
                "]" -> if (brackets > 0) brackets--
            }
            if (unit.tokens[index].text == "," && angle == 0 && parentheses == 0 && brackets == 0) {
                result += segmentStart to index
                segmentStart = index + 1
            }
        }
        result += segmentStart to to
        return result
    }
}

private fun indexingReferences(unit: KotlinSourceUnit, units: List<KotlinSourceUnit>): List<String> =
    KotlinSourceIndex(units).indexingReferences(unit)

private val INDEXING_FQNS = mapOf(
    "Draft" to "com.plainbase.domain.service.Draft",
    "SourceScan" to "com.plainbase.domain.service.SourceScan",
    "Identity" to "com.plainbase.domain.service.Identity",
)

private val ADMITTED_INDEXING_PATHS = setOf(
    "com/plainbase/domain/service/IndexInputs.kt",
    "com/plainbase/domain/service/IndexSourceReader.kt",
    "com/plainbase/domain/service/IndexBuilder.kt",
    "com/plainbase/domain/service/IndexIdentityAssignments.kt",
    "com/plainbase/domain/service/IndexSnapshotAssembler.kt",
)

private val ERASED_AUTHORITY_FQNS = mapOf(
    "ObservationId" to "com.plainbase.domain.root.ObservationId",
    "BindingEpoch" to "com.plainbase.domain.root.BindingEpoch",
)

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun buildIndexedSourceUnit(path: String, source: String): IndexedSourceUnit {
    val tokens = try {
        KotlinTokenizer.tokenize(source)
    } catch (failure: KotlinSourceFailure) {
        val line = source.substring(0, failure.offset.coerceIn(0, source.length)).count { it == '\n' } + 1
        throw IllegalArgumentException("$path:$line: ${failure.message}", failure)
    }
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    val delimiterPairs = delimiterPairs(path, source, tokens)
    val ignored = mutableSetOf<Int>()
    var packageName = ""
    val imports = mutableListOf<SourceImport>()
    tokens.forEachIndexed { index, token ->
        if (token.text != "package" && token.text != "import") return@forEachIndexed
        val line = lineStarts.indexOfLast { it <= token.offset }
        val end = tokens.indexOfFirst { candidate ->
            candidate.offset > token.offset && lineStarts.indexOfLast { it <= candidate.offset } != line
        }.takeIf { it >= 0 } ?: tokens.size
        val row = (index until end).toList()
        ignored += row
        val values = row.map { tokens[it].text }
        if (token.text == "package") {
            packageName = values.drop(1).joinToString("")
        } else {
            val asIndex = values.indexOf("as")
            val target = values.subList(1, if (asIndex < 0) values.size else asIndex).joinToString("")
            if (target.isNotEmpty()) {
                val alias = if (asIndex < 0) target.substringAfterLast('.') else values[asIndex + 1]
                imports += SourceImport(alias, target, row.toSet())
            }
        }
    }
    val rawDeclarations = rawDeclarations(tokens, source, ignored)
    val scopes = sourceScopes(path, source, tokens, delimiterPairs, rawDeclarations)
    val locatedBase = rawDeclarations.mapIndexed { index, raw ->
        val scopeId = scopes.filter { it.startOffset <= tokens[raw.tokenIndex].offset && tokens[raw.tokenIndex].offset < it.endOffset }
            .maxByOrNull { it.startOffset }?.id ?: 0
        val enclosing = rawDeclarations.filter { parent ->
            parent.kind in setOf(
                SourceDeclarationKind.CLASS,
                SourceDeclarationKind.INTERFACE,
                SourceDeclarationKind.OBJECT,
                SourceDeclarationKind.FUNCTION,
            ) &&
                parent.bodyTokenIndex?.let { body ->
                    val close = delimiterPairs[body] ?: return@let false
                    tokens[body].offset < tokens[raw.tokenIndex].offset &&
                        tokens[raw.tokenIndex].offset < tokens[close].offset
                } == true
        }.sortedBy { it.tokenIndex }
        val ownerNames = enclosing.map { it.name }
        val qualifiedName = (ownerNames + raw.name).joinToString(".")
        val ownerQualifiedName = ownerNames.joinToString(".").takeIf { it.isNotEmpty() }
        val endOffset = raw.bodyTokenIndex?.let { delimiterPairs[it] }?.let { tokens[it].offset + 1 }
            ?: tokens.getOrNull(raw.endTokenIndex)?.offset ?: source.length
        val typeParameters = sourceTypeParameters(tokens, raw, endOffset)
        val aliasEquals = if (raw.kind == SourceDeclarationKind.TYPEALIAS) {
            (raw.nameTokenIndex + 1 until raw.endTokenIndex).firstOrNull { tokens[it].text == "=" }
        } else {
            null
        }
        val headerEnd = declarationHeaderEnd(tokens, raw)
        val declaredType = declaredTypeRegion(tokens, raw, headerEnd)
        val visibility = sourceVisibility(tokens, raw.tokenIndex)
        val identity = "$packageName.$qualifiedName".trim('.')
        LocatedSourceDeclaration(
            identity = identity,
            kind = raw.kind,
            namespace = if (raw.kind in setOf(
                    SourceDeclarationKind.CLASS,
                    SourceDeclarationKind.INTERFACE,
                    SourceDeclarationKind.OBJECT,
                    SourceDeclarationKind.TYPEALIAS,
                )
            ) {
                SourceNamespace.TYPE
            } else {
                SourceNamespace.VALUE
            },
            name = raw.name,
            qualifiedName = qualifiedName,
            ownerQualifiedName = ownerQualifiedName,
            tokenIndex = raw.tokenIndex,
            nameTokenIndex = raw.nameTokenIndex,
            scopeId = scopeId,
            startOffset = tokens[raw.tokenIndex].offset,
            endOffset = endOffset,
            headerEndTokenIndex = headerEnd,
            visibility = visibility,
            typeParameters = typeParameters,
            aliasRhsStartTokenIndex = aliasEquals?.plus(1),
            aliasRhsEndTokenIndex = aliasEquals?.let { raw.endTokenIndex },
            typeStartTokenIndex = declaredType?.first,
            typeEndTokenIndex = declaredType?.second,
        )
    }.toMutableList()
    val names = locatedBase.map { it.nameTokenIndex }.toMutableSet()
    val parameterDeclarations = locatedBase.flatMap { declaration ->
        if (declaration.kind !in setOf(SourceDeclarationKind.CLASS, SourceDeclarationKind.FUNCTION)) return@flatMap emptyList()
        val raw = rawDeclarations.first { it.tokenIndex == declaration.tokenIndex }
        sourceParameters(tokens, delimiterPairs, raw, declaration)
    }
    val typeParameterDeclarations = locatedBase.flatMap { declaration ->
        declaration.typeParameters.map { parameter ->
            names += parameter.nameTokenIndex
            LocatedSourceDeclaration(
                identity = "${declaration.identity}#type:${parameter.nameTokenIndex}",
                kind = SourceDeclarationKind.TYPE_PARAMETER,
                namespace = SourceNamespace.TYPE,
                name = parameter.name,
                qualifiedName = "${declaration.qualifiedName}.<${parameter.name}>",
                ownerQualifiedName = declaration.qualifiedName,
                tokenIndex = parameter.nameTokenIndex,
                nameTokenIndex = parameter.nameTokenIndex,
                scopeId = declaration.scopeId,
                startOffset = tokens[parameter.nameTokenIndex].offset,
                endOffset = parameter.endOffset,
                headerEndTokenIndex = declaration.headerEndTokenIndex,
                visibility = "public",
            )
        }
    }
    parameterDeclarations.forEach { names += it.nameTokenIndex }
    val declarations = (locatedBase + parameterDeclarations + typeParameterDeclarations).sortedBy { it.tokenIndex }
    return IndexedSourceUnit(
        path = path,
        source = source,
        tokens = tokens,
        lineStarts = lineStarts,
        packageName = packageName,
        imports = imports,
        scopes = scopes,
        declarations = declarations,
        ignoredTokenIndices = ignored,
        declarationNameIndices = names,
        delimiterPairs = delimiterPairs,
    )
}

private fun delimiterPairs(path: String, source: String, tokens: List<LexToken>): Map<Int, Int> {
    val pairs = mutableMapOf<Int, Int>()
    val open = ArrayDeque<Pair<String, Int>>()
    val closing = mapOf(")" to "(", "]" to "[", "}" to "{")
    tokens.forEachIndexed { index, token ->
        when {
            token.text in setOf("(", "[", "{") -> open.addLast(token.text to index)
            token.text in closing -> {
                val expected = closing.getValue(token.text)
                val (actual, start) = open.removeLastOrNull()
                    ?: throw sourceSyntaxFailure(path, source, token.offset, "unbalanced ${token.text}")
                if (actual != expected) throw sourceSyntaxFailure(path, source, token.offset, "unbalanced ${token.text}")
                pairs[start] = index
                pairs[index] = start
            }
        }
    }
    if (open.isNotEmpty()) {
        val (_, start) = open.last()
        throw sourceSyntaxFailure(path, source, tokens.getOrNull(start)?.offset ?: source.length, "unbalanced opening delimiter")
    }
    return pairs
}

private fun sourceSyntaxFailure(path: String, source: String, offset: Int, message: String): IllegalArgumentException {
    val line = source.substring(0, offset.coerceIn(0, source.length)).count { it == '\n' } + 1
    return IllegalArgumentException("$path:$line: $message")
}

@Suppress("CyclomaticComplexMethod")
private fun rawDeclarations(
    tokens: List<LexToken>,
    source: String,
    ignored: Set<Int>,
): List<RawSourceDeclaration> {
    val declarations = mutableListOf<RawSourceDeclaration>()
    var braceDepth = 0
    var parenDepth = 0
    tokens.forEachIndexed { index, token ->
        if (index in ignored) {
            when (token.text) {
                "{" -> braceDepth++
                "}" -> braceDepth--
                "(" -> parenDepth++
                ")" -> parenDepth--
            }
            return@forEachIndexed
        }
        val kind = when {
            token.text == "class" && !isClassLiteral(tokens, index) -> SourceDeclarationKind.CLASS
            token.text == "interface" -> SourceDeclarationKind.INTERFACE
            token.text == "object" && !isAnonymousObject(tokens, index) -> SourceDeclarationKind.OBJECT
            token.text == "fun" && tokens.getOrNull(index + 1)?.text != "interface" && parenDepth == 0 -> SourceDeclarationKind.FUNCTION
            token.text in setOf("val", "var") && parenDepth == 0 -> SourceDeclarationKind.PROPERTY
            token.text == "typealias" -> SourceDeclarationKind.TYPEALIAS
            else -> null
        }
        if (kind != null) {
            val isCompanion = kind == SourceDeclarationKind.OBJECT && tokens.getOrNull(index - 1)?.text == "companion"
            val namedCompanionIndex = if (isCompanion) {
                tokens.getOrNull(index + 1)?.takeIf {
                    it.text.isIdentifier() &&
                        source.substring(tokens[index].offset, it.offset).none { character -> character == '\n' }
                }?.let { index + 1 }
            } else {
                null
            }
            val nameIndex = when {
                isCompanion -> namedCompanionIndex ?: index
                kind == SourceDeclarationKind.FUNCTION -> functionNameIndex(tokens, index)
                else -> tokens.getOrNull(index + 1)?.takeIf { it.text.isIdentifier() }?.let { index + 1 }
            }
            val name = if (isCompanion) namedCompanionIndex?.let { tokens[it].text } ?: "Companion" else nameIndex?.let { tokens[it].text }
            if (name != null && (kind != SourceDeclarationKind.OBJECT || isCompanion || nameIndex != null)) {
                val body = findDeclarationBody(tokens, index, nameIndex ?: index, braceDepth)
                val end = body ?: findDeclarationEnd(tokens, index, braceDepth)
                declarations += RawSourceDeclaration(kind, index, nameIndex ?: index, name, body, end, braceDepth)
            }
        }
        when (token.text) {
            "{" -> braceDepth++
            "}" -> braceDepth--
            "(" -> parenDepth++
            ")" -> parenDepth--
        }
    }
    return declarations
}

private fun functionNameIndex(tokens: List<LexToken>, start: Int): Int? {
    var angleDepth = 0
    var index = start + 1
    var lastIdentifier: Int? = null
    while (index < tokens.size) {
        when (tokens[index].text) {
            "<" -> angleDepth++
            ">" -> angleDepth--
            "(" -> return lastIdentifier
            "{" -> return lastIdentifier
            "=" -> return lastIdentifier
            else -> if (angleDepth == 0 && tokens[index].text.isIdentifier() && tokens[index].text !in SOURCE_MODIFIERS) {
                lastIdentifier = index
            }
        }
        index++
    }
    return lastIdentifier
}

@Suppress("ReturnCount")
private fun findDeclarationBody(
    tokens: List<LexToken>,
    start: Int,
    nameIndex: Int,
    baseBraceDepth: Int,
): Int? {
    var parentheses = 0
    var angles = 0
    var index = nameIndex + 1
    while (index < tokens.size) {
        val value = tokens[index].text
        if (parentheses == 0 && angles == 0) {
            if (value == "{") return index
            if (value == ";") return null
            if (index > start && value in SOURCE_DECLARATION_KEYWORDS) return null
        }
        when (value) {
            "(" -> parentheses++
            ")" -> parentheses--
            "<" -> angles++
            ">" -> if (angles > 0) angles--
            "}" -> if (parentheses == 0 && angles == 0 && baseBraceDepth > 0) return null
        }
        index++
    }
    return null
}

private fun findDeclarationEnd(tokens: List<LexToken>, start: Int, baseBraceDepth: Int): Int {
    var braces = baseBraceDepth
    var parentheses = 0
    for (index in start + 1 until tokens.size) {
        when (tokens[index].text) {
            "{" -> braces++
            "}" -> braces--
            "(" -> parentheses++
            ")" -> parentheses--
            ";" -> if (braces == baseBraceDepth && parentheses == 0) return index
        }
        if (braces == baseBraceDepth && parentheses == 0 && index > start + 1 && tokens[index].text in SOURCE_DECLARATION_KEYWORDS) {
            return index
        }
    }
    return tokens.size
}

private fun sourceScopes(
    path: String,
    source: String,
    tokens: List<LexToken>,
    pairs: Map<Int, Int>,
    declarations: List<RawSourceDeclaration>,
): List<LocatedSourceScope> {
    val bodyDeclarations = declarations.filter { it.bodyTokenIndex != null }.associateBy { it.bodyTokenIndex }
    val scopes = mutableListOf(LocatedSourceScope(0, SourceScopeKind.FILE, null, 0, source.length))
    val open = ArrayDeque<Int>()
    tokens.forEachIndexed { index, token ->
        when (token.text) {
            "{" -> {
                val close = pairs[index] ?: throw sourceSyntaxFailure(path, source, token.offset, "unmatched body")
                val declaration = bodyDeclarations[index]
                val kind = if (token.offset > 0 && source.getOrNull(token.offset - 1) == '$') {
                    SourceScopeKind.INTERPOLATION
                } else {
                    when (declaration?.kind) {
                        SourceDeclarationKind.CLASS, SourceDeclarationKind.INTERFACE, SourceDeclarationKind.OBJECT -> SourceScopeKind.CLASS
                        SourceDeclarationKind.FUNCTION -> SourceScopeKind.FUNCTION
                        SourceDeclarationKind.PROPERTY -> SourceScopeKind.INITIALIZER
                        else -> SourceScopeKind.BLOCK
                    }
                }
                val id = scopes.size
                scopes += LocatedSourceScope(id, kind, open.lastOrNull() ?: 0, token.offset, tokens[close].offset + 1)
                open.addLast(id)
            }
            "}" -> {
                if (open.isEmpty()) throw sourceSyntaxFailure(path, source, token.offset, "unbalanced closing brace")
                open.removeLast()
            }
        }
    }
    return scopes
}

private fun sourceTypeParameters(
    tokens: List<LexToken>,
    declaration: RawSourceDeclaration,
    endOffset: Int,
): List<SourceTypeParameter> {
    val open = when (declaration.kind) {
        SourceDeclarationKind.FUNCTION -> tokens.getOrNull(declaration.tokenIndex + 1)?.takeIf { it.text == "<" }
            ?.let { declaration.tokenIndex + 1 }
        else -> tokens.getOrNull(declaration.nameTokenIndex + 1)?.takeIf { it.text == "<" }
            ?.let { declaration.nameTokenIndex + 1 }
    } ?: return emptyList()
    var depth = 0
    val result = mutableListOf<SourceTypeParameter>()
    var expectName = true
    for (index in open until declaration.endTokenIndex) {
        when (tokens[index].text) {
            "<" -> depth++
            ">" -> depth--
            "," -> if (depth == 1) expectName = true
            else -> if (depth == 1 && expectName && tokens[index].text.isIdentifier() && tokens[index].text !in SOURCE_MODIFIERS) {
                result += SourceTypeParameter(tokens[index].text, index, endOffset)
                expectName = false
            }
        }
        if (depth == 0 && index > open) break
    }
    return result
}

private fun sourceParameters(
    tokens: List<LexToken>,
    pairs: Map<Int, Int>,
    declaration: RawSourceDeclaration,
    located: LocatedSourceDeclaration,
): List<LocatedSourceDeclaration> {
    val open = (declaration.nameTokenIndex + 1 until (declaration.bodyTokenIndex ?: declaration.endTokenIndex))
        .firstOrNull { tokens[it].text == "(" } ?: return emptyList()
    val close = pairs[open] ?: return emptyList()
    val result = mutableListOf<LocatedSourceDeclaration>()
    var segmentStart = open + 1
    var depth = 0
    fun addSegment(endExclusive: Int) {
        val segment = (segmentStart until endExclusive).toList()
        val colon = segment.firstOrNull { tokens[it].text == ":" } ?: return
        val propertyKeyword = segment.firstOrNull { propertyToken ->
            tokens[propertyToken].text in setOf("val", "var") &&
                segment.any { it > propertyToken && it < colon && tokens[it].text.isIdentifier() }
        }
        val isConstructorProperty = propertyKeyword != null
        val nameIndex = segment.lastOrNull { it < colon && tokens[it].text.isIdentifier() }
            ?: return
        val typeEnd = segment.firstOrNull { it > colon && tokens[it].text == "=" } ?: endExclusive
        result += LocatedSourceDeclaration(
            identity = "${located.identity}#parameter:$nameIndex",
            kind = if (isConstructorProperty) {
                SourceDeclarationKind.PROPERTY
            } else {
                SourceDeclarationKind.PARAMETER
            },
            namespace = SourceNamespace.VALUE,
            name = tokens[nameIndex].text,
            qualifiedName = "${located.qualifiedName}.${tokens[nameIndex].text}",
            ownerQualifiedName = located.qualifiedName,
            tokenIndex = nameIndex,
            nameTokenIndex = nameIndex,
            scopeId = located.scopeId,
            startOffset = tokens[nameIndex].offset,
            endOffset = located.endOffset,
            headerEndTokenIndex = typeEnd,
            visibility = if (isConstructorProperty) {
                sourceVisibility(tokens, propertyKeyword ?: segmentStart, segmentStart)
            } else {
                "private"
            },
            typeStartTokenIndex = colon + 1,
            typeEndTokenIndex = typeEnd,
            isConstructorProperty = isConstructorProperty,
        )
    }
    for (index in open + 1 until close) {
        when (tokens[index].text) {
            "(", "[", "<" -> depth++
            ")", "]", ">" -> if (depth > 0) depth--
            "," -> if (depth == 0) {
                addSegment(index)
                segmentStart = index + 1
            }
        }
    }
    addSegment(close)
    return result
}

@Suppress("LoopWithTooManyJumpStatements")
private fun sourceVisibility(tokens: List<LexToken>, index: Int, lowerBound: Int = 0): String {
    var cursor = index - 1
    val modifiers = mutableSetOf<String>()
    while (cursor >= lowerBound) {
        val value = tokens[cursor].text
        if (value in setOf("{", "}", ";")) break
        if (value in SOURCE_DECLARATION_KEYWORDS && cursor < index - 1) break
        if (value in setOf("private", "internal", "protected", "public")) modifiers += value
        cursor--
    }
    return when {
        "private" in modifiers -> "private"
        "internal" in modifiers -> "internal"
        "protected" in modifiers -> "protected"
        else -> "public"
    }
}

private fun declaredTypeRegion(
    tokens: List<LexToken>,
    declaration: RawSourceDeclaration,
    headerEnd: Int,
): Pair<Int, Int>? {
    if (declaration.kind != SourceDeclarationKind.PROPERTY) return null
    val colon = (declaration.nameTokenIndex + 1 until headerEnd).firstOrNull { tokens[it].text == ":" } ?: return null
    return (colon + 1 to headerEnd).takeIf { it.first < it.second }
}

private fun declarationHeaderEnd(tokens: List<LexToken>, declaration: RawSourceDeclaration): Int {
    val limit = declaration.bodyTokenIndex ?: declaration.endTokenIndex
    if (declaration.kind !in setOf(SourceDeclarationKind.FUNCTION, SourceDeclarationKind.PROPERTY)) return limit
    var parentheses = 0
    var angles = 0
    for (index in declaration.nameTokenIndex + 1 until limit) {
        when (tokens[index].text) {
            "(" -> parentheses++
            ")" -> parentheses--
            "<" -> angles++
            ">" -> if (angles > 0) angles--
            "=" -> if (parentheses == 0 && angles == 0) return index
        }
    }
    return limit
}

private val SOURCE_DECLARATION_KEYWORDS = setOf("class", "interface", "object", "fun", "val", "var", "typealias")
private val SOURCE_MODIFIERS = setOf(
    "actual", "annotation", "abstract", "const", "data", "enum", "expect", "final", "infix", "inline", "internal",
    "lateinit", "noinline", "open", "operator", "out", "private", "protected", "public", "reified", "sealed", "suspend", "value",
)

private fun isClassLiteral(tokens: List<LexToken>, index: Int): Boolean =
    index > 1 && tokens[index - 1].text == ":" && tokens[index - 2].text == ":"

private fun isAnonymousObject(tokens: List<LexToken>, index: Int): Boolean = tokens.getOrNull(index + 1)?.text !in setOf(null, "", "{") &&
    !tokens[index + 1].text.isIdentifier()

private fun qualifiedStart(unit: KotlinSourceUnit, index: Int): Int {
    var start = index
    while (start >= 2 && unit.tokens[start - 1].text == "." && unit.tokens[start - 2].text.isIdentifier()) start -= 2
    return start
}

private fun qualifiedSpelling(unit: KotlinSourceUnit, index: Int, limit: Int = unit.tokens.size): String {
    val parts = mutableListOf(unit.tokens[index].text)
    var cursor = index + 1
    while (cursor + 1 < limit && unit.tokens[cursor].text == "." && unit.tokens[cursor + 1].text.isIdentifier()) {
        parts += unit.tokens[cursor + 1].text
        cursor += 2
    }
    return parts.joinToString(".")
}

private fun qualifiedEnd(unit: KotlinSourceUnit, index: Int): Int {
    var cursor = index + 1
    while (cursor + 1 < unit.tokens.size && unit.tokens[cursor].text == "." && unit.tokens[cursor + 1].text.isIdentifier()) {
        cursor += 2
    }
    return cursor
}

private fun angleClose(tokens: List<LexToken>, open: Int): Int? {
    var depth = 0
    for (index in open until tokens.size) {
        when (tokens[index].text) {
            "<" -> depth++
            ">" -> {
                depth--
                if (depth == 0) return index
            }
        }
    }
    return null
}

private fun sourcePublicSignatureAuthorityViolations(
    path: String,
    source: String,
    authorityFqns: Map<String, String> = ERASED_AUTHORITY_FQNS,
): Set<String> =
    KotlinSourceIndex(authorityDefinitionUnits() + KotlinSourceUnit(path, source))
        .publicSignatureAuthorityViolations(authorityFqns = authorityFqns)

private fun authorityDefinitionUnits(): List<KotlinSourceUnit> = listOf(
    KotlinSourceUnit(
        "com/plainbase/domain/root/AuthorityDefinitions.kt",
        "package com.plainbase.domain.root\nclass ObservationId\nclass BindingEpoch",
    ),
)

private open class GenericAuthorityFixture<T> {
    fun authority(): T = error("fixture only")
}

private class GenericAuthorityFixtureImpl : GenericAuthorityFixture<com.plainbase.domain.repository.IdMapRepository>()

private typealias GenericAuthorityFixtureAlias = GenericAuthorityFixtureImpl

private class WrapperAuthorityFixture {
    val wrapped: List<com.plainbase.domain.repository.IdMapRepository> = emptyList()
}

private class ListAuthorityFixture {
    fun exposed(): List<com.plainbase.domain.repository.IdMapRepository> = emptyList()
}

private class CompanionAuthorityFixture private constructor() {
    companion object {
        fun exposed(): List<com.plainbase.domain.repository.IdMapRepository> = emptyList()
    }
}

private open class GenericListBase<T> {
    fun exposed(): List<T> = emptyList()
}

private class InheritedListAuthorityFixture : GenericListBase<com.plainbase.domain.repository.IdMapRepository>()

private class InheritedSafeFixture : GenericListBase<String>()

private class PrivateAuthorityStorageFixture(private val repository: com.plainbase.domain.repository.IdMapRepository) {
    fun safe(): String = repository.toString()
}

private class IdMapEntryAuthorityFixture {
    fun exposed(): List<com.plainbase.domain.repository.RetirementRepository> = emptyList()
}

private class SyntheticAccessorAuthorityFixture(private val repository: com.plainbase.domain.repository.RetirementRepository) {
    class Reader {
        fun text(owner: SyntheticAccessorAuthorityFixture): String = owner.repository.toString()
    }
}

private class DeferredFixture {
    fun lazyValue(): Lazy<Draft> = lazy { error("fixture only") }
    fun sequenceValue(): Sequence<Draft> = emptySequence()
    fun iteratorValue(): Iterator<Draft> = emptyList<Draft>().iterator()
    fun callbackValue(): (Draft) -> Unit = {}
}

private class CombinedDeferredAuthorityFixture {
    fun authorityOnly(): com.plainbase.domain.repository.IdMapRepository = error("fixture only")
    fun nestedAuthority(): List<com.plainbase.domain.repository.IdMapRepository> = emptyList()
    fun deferredAndAuthority(): Sequence<com.plainbase.domain.repository.IdMapRepository> = emptySequence()
}

private class WildcardFixture {
    fun wildcardValue(): MutableList<out com.plainbase.domain.repository.IdMapRepository> = mutableListOf()
}

private class ArrayFixture {
    fun arrayValue(): Array<com.plainbase.domain.repository.IdMapRepository> = emptyArray()
}

private class RecursiveFixture<T : Comparable<T>> {
    fun value(input: T): T = input
}

private class ErasedSignatureFixture {
    @Suppress("UnusedParameter")
    fun exposed(
        input: com.plainbase.domain.root.ObservationId,
    ): String = error("fixture only")

    companion object {
        val epoch: com.plainbase.domain.root.BindingEpoch? = null
    }
}

private class BenignPublishedFixture {
    class Published

    fun exposed(): Published = error("fixture only")
}

private data class MaterializedPositiveFixture(
    val nested: MaterializedNestedPositiveFixture,
)

private data class MaterializedNestedPositiveFixture(
    val section: com.plainbase.domain.page.RootSection,
)

private data class MaterializedDeferredFixture(
    val nested: MaterializedNestedDeferredFixture,
)

private data class MaterializedNestedDeferredFixture(
    val value: Lazy<String>,
)

private data class MaterializedAuthorityFixture(
    val nested: MaterializedNestedAuthorityFixture,
)

private data class MaterializedNestedAuthorityFixture(
    val repository: RetirementRepository,
)

private class MaterializedUnknownWrapperFixture {
    val wrapped: MaterializedUnknownPayload? = null
}

private data class MaterializedUnknownPayload(
    val value: String,
)

private typealias InferredEpochAlias = com.plainbase.domain.root.BindingEpoch

private class InferredErasedSignatureFixture {
    fun token() = com.plainbase.domain.root.ObservationId(1L)
    fun aliasedToken() = InferredEpochAlias(1L)
    fun valueOnly() = com.plainbase.domain.root.ObservationId(1L).value == 1L
    fun blockOnly(): Boolean {
        val token = com.plainbase.domain.root.ObservationId(1L)
        return token.value == 1L
    }

    private fun hidden() = com.plainbase.domain.root.ObservationId(1L)

    companion object {
        val epoch = InferredEpochAlias(1L)
        val number = com.plainbase.domain.root.BindingEpoch(1L).value
    }
}

private class LateMemberShadowFixture {
    fun exposed() = Oid(1L)
    private fun `Oid`(value: Long): Long = value
    fun memberBeforeUse() = Oid(1L)
}

private class CallbackValueShadowFixture {
    @Suppress("FunctionParameterNaming")
    fun callback(`Oid`: (Long) -> Long) = `Oid`(1L)
}

private class LocalValueOrderFixture {
    fun order(): Long {
        val before = com.plainbase.domain.root.ObservationId(1L).value
        return run {
            fun `Oid`(value: Long): Long = value
            `Oid`(1L)
        }
    }
}

private val EXPECTED_SERVICE_PATHS = setOf(
    "AbsenceClassifier.kt", "AdminFacade.kt", "AdoptionPass.kt", "AgentDirectCommitDecision.kt", "ApiTokenService.kt",
    "ApplyDisposition.kt", "BindingVisibility.kt", "CanonicalUrlBuilder.kt", "CitationFactory.kt", "FrontmatterPatcher.kt",
    "IdProvider.kt", "IdResolution.kt", "IndexBuilder.kt", "IndexIdentityAssignments.kt", "IndexInputs.kt",
    "IndexSnapshotAssembler.kt", "IndexSourceReader.kt",
    "LinkChecker.kt",
    "LinkResolver.kt", "LoginService.kt", "MutatingFacade.kt", "PageIdentityService.kt", "PageRootResolver.kt",
    "PageService.kt", "PolicyService.kt", "ProposalAuthorLabeler.kt", "ProposalBaseReader.kt", "ProposalFacade.kt",
    "ProposalIdProvider.kt", "ProposalService.kt", "ReadFacade.kt", "RebuildScheduler.kt", "RootLossClassifier.kt",
    "SearchIndexer.kt", "SearchService.kt", "SectionSplitter.kt", "SessionService.kt", "SetupService.kt", "TreeBuilder.kt",
    "UnifiedDiff.kt", "UrlAliasRegistry.kt", "UuidV7IdProvider.kt", "UuidV7ProposalIdProvider.kt", "WriteClass.kt",
    "WritePipeline.kt",
)

private val EXPECTED_INDEX_BUILDER_FIELDS = mapOf(
    "frontmatterParser" to "com.plainbase.domain.page.FrontmatterParser",
    "rendererFactory" to "kotlin.jvm.functions.Function1",
    "identity" to "com.plainbase.domain.service.PageIdentityService",
    "patcher" to "com.plainbase.domain.service.FrontmatterPatcher",
    "idMap" to "com.plainbase.domain.repository.IdMapRepository",
    "aliasRegistry" to "com.plainbase.domain.service.UrlAliasRegistry",
    "checkpoint" to "com.plainbase.domain.repository.PageCheckpointRepository",
    "citations" to "com.plainbase.domain.service.CitationFactory",
    "registeredRoots" to "java.util.Set",
    "listeners" to "java.util.List",
    "searchIndexer" to "com.plainbase.domain.service.SearchIndexer",
    "availability" to "com.plainbase.domain.root.RootAvailability",
    "retirements" to "com.plainbase.domain.repository.RetirementRepository",
    "limbo" to "com.plainbase.domain.root.RootLimbo",
    "epochs" to "com.plainbase.domain.root.ObservationEpoch",
    "bindings" to "com.plainbase.domain.root.BindingLatch",
    "sources" to "java.util.List",
    "sourcesByRoot" to "java.util.Map",
    "rootLoss" to "com.plainbase.domain.service.RootLossClassifier",
    "absence" to "com.plainbase.domain.service.AbsenceClassifier",
    "sourceReader" to "com.plainbase.domain.service.IndexSourceReader",
    "identityAssignments" to "com.plainbase.domain.service.IndexIdentityAssignments",
    "snapshotAssembler" to "com.plainbase.domain.service.IndexSnapshotAssembler",
    "corpusSeen" to "java.util.Set",
    "holder" to "java.util.concurrent.atomic.AtomicReference",
)

private val EXPECTED_SERVICE_NON_CLASS_DECLARATIONS = setOf(
    "AgentDirectCommitDecision.kt|fun agentWriteDecision",
    "ApplyDisposition.kt|fun dispositionOf",
    "PageIdentityService.kt|fun requireDistinctIds",
    "ProposalService.kt|fun syntheticEmail",
    "UnifiedDiff.kt|fun unifiedDiff",
    "UnifiedDiff.kt|fun replaceEverythingScript",
    "UnifiedDiff.kt|fun finalNewlineOnlyScript",
    "UnifiedDiff.kt|fun splitTrailingNewlineDisagreement",
    "UnifiedDiff.kt|val MAX_TRACE_INTS",
    "UnifiedDiff.kt|fun myers",
    "UnifiedDiff.kt|fun backtrack",
    "UnifiedDiff.kt|val CONTEXT",
    "UnifiedDiff.kt|fun render",
    "UnifiedDiff.kt|fun coalesce",
    "UnifiedDiff.kt|fun appendHunk",
    "UnifiedDiff.kt|val NO_NEWLINE_MARKER",
    "UnifiedDiff.kt|fun headerStart",
    "UnifiedDiff.kt|fun firstBaseLine",
    "UnifiedDiff.kt|fun firstProposedLine",
    "UnifiedDiff.kt|fun appendBodyLine",
    "UnifiedDiff.kt|fun splitLines",
    "WritePipeline.kt|fun commit",
)

private val EXPECTED_SERVICE_DECLARATIONS = setOf(
    "AbsenceClassifier.kt|AbsenceClassifier", "AbsenceClassifier.kt|AbsenceUnverified",
    "AdminFacade.kt|AdminFacade", "AdminFacade.kt|CreateUserOutcome", "AdminFacade.kt|CreateUserOutcome.Created",
    "AdminFacade.kt|CreateUserOutcome.UsernameExists", "AdminFacade.kt|CreatedApiToken",
    "AdoptionPass.kt|AdoptionPass", "AdoptionPass.kt|AdoptionPass.Source", "AdoptionPass.kt|AdoptionPass.Mode",
    "AdoptionPass.kt|AdoptionPass.Disposition", "AdoptionPass.kt|AdoptionPass.PageReport", "AdoptionPass.kt|AdoptionPass.Report",
    "AdoptionPass.kt|AdoptionPass.PlannedWrite", "AdoptionPass.kt|AdoptionPass.Plan", "AdoptionPass.kt|AdoptionPass.Draft",
    "AdoptionPass.kt|AdoptionPass.Companion", "AdoptionPass.kt|PlanStale", "AdoptionPass.kt|AdoptWriteFailed",
    "AgentDirectCommitDecision.kt|AgentWriteDecision", "AgentDirectCommitDecision.kt|AgentWriteDecision.DirectCommit",
    "AgentDirectCommitDecision.kt|AgentWriteDecision.DegradeToProposal", "AgentDirectCommitDecision.kt|CommitGlob",
    "AgentDirectCommitDecision.kt|CommitGlob.Companion",
    "ApiTokenService.kt|ApiTokenService", "ApiTokenService.kt|ApiTokenService.Companion",
    "ApplyDisposition.kt|ApplyDisposition", "ApplyDisposition.kt|ApplyDisposition.Applied",
    "ApplyDisposition.kt|ApplyDisposition.Conflicted",
    "ApplyDisposition.kt|ApplyDisposition.Failed", "BindingVisibility.kt|BindingVisibility", "CanonicalUrlBuilder.kt|CanonicalUrlBuilder",
    "CanonicalUrlBuilder.kt|CanonicalUrlBuilder.PageInput", "CanonicalUrlBuilder.kt|CanonicalUrlBuilder.Assignment",
    "CanonicalUrlBuilder.kt|CanonicalUrlBuilder.Result", "CanonicalUrlBuilder.kt|CanonicalUrlBuilder.Sibling",
    "CanonicalUrlBuilder.kt|CanonicalUrlBuilder.Collision", "CitationFactory.kt|CitationFactory",
    "FrontmatterPatcher.kt|FrontmatterPatcher", "FrontmatterPatcher.kt|FrontmatterPatcher.RefusalReason",
    "FrontmatterPatcher.kt|FrontmatterPatcher.PatchResult", "FrontmatterPatcher.kt|FrontmatterPatcher.PatchResult.Patched",
    "FrontmatterPatcher.kt|FrontmatterPatcher.PatchResult.AlreadyPresent", "FrontmatterPatcher.kt|FrontmatterPatcher.PatchResult.Refused",
    "FrontmatterPatcher.kt|FrontmatterPatcher.ContinuationShape", "FrontmatterPatcher.kt|FrontmatterPatcher.Companion",
    "IdProvider.kt|IdProvider", "IdResolution.kt|IdResolution", "IdResolution.kt|IdResolution.One",
    "IdResolution.kt|IdResolution.Ambiguous", "IdResolution.kt|IdResolution.None", "IdResolution.kt|AmbiguousPageId",
    "IndexBuilder.kt|IndexBuilder", "IndexBuilder.kt|IndexBuilder.Source", "IndexBuilder.kt|IndexBuilder.PublicationListener",
    "IndexBuilder.kt|IndexBuilder.Published", "IndexBuilder.kt|IndexBuilder.AbsencePass",
    "IndexBuilder.kt|IndexBuilder.AbsencePass.GitMint",
    "IndexBuilder.kt|IndexBuilder.AbsencePass.GitReads", "IndexBuilder.kt|IndexBuilder.AbsencePass.Companion",
    "IndexBuilder.kt|IndexBuilder.Companion",
    "IndexInputs.kt|Draft", "IndexInputs.kt|SourceScan", "IndexInputs.kt|Identity",
    "IndexIdentityAssignments.kt|IndexIdentityAssignments", "IndexSnapshotAssembler.kt|IndexSnapshotAssembler",
    "IndexSourceReader.kt|IndexSourceReader", "IndexSourceReader.kt|IndexSourceReader.Companion",
    "LinkChecker.kt|LinkChecker", "LinkChecker.kt|LinkChecker.Sweep", "LinkChecker.kt|LinkReport", "LinkChecker.kt|BrokenLink",
    "LinkChecker.kt|BrokenLinkReason", "LinkChecker.kt|BrokenLinkReason.Unresolved", "LinkChecker.kt|BrokenLinkReason.UnknownAnchor",
    "LinkResolver.kt|LinkResolver", "LinkResolver.kt|LinkResolver.Companion", "LoginService.kt|LoginService",
    "LoginService.kt|LoginOutcome",
    "LoginService.kt|LoginOutcome.Success", "LoginService.kt|LoginOutcome.InvalidCredentials", "LoginService.kt|LoginOutcome.Disabled",
    "MutatingFacade.kt|MutatingFacade", "MutatingFacade.kt|SaveRequest", "MutatingFacade.kt|WriteOrigin", "MutatingFacade.kt|SaveResult",
    "MutatingFacade.kt|SaveResult.PageNotFound", "MutatingFacade.kt|SaveResult.IdMismatch", "MutatingFacade.kt|SaveResult.Written",
    "MutatingFacade.kt|SaveResult.DegradedToProposal", "MutatingFacade.kt|SaveResult.DegradeStaleBase", "MutatingFacade.kt|CreateOutcome",
    "MutatingFacade.kt|CreateOutcome.DirectCreated", "MutatingFacade.kt|CreateOutcome.DegradedToProposal",
    "MutatingFacade.kt|CreateOutcome.InvalidContent",
    "MutatingFacade.kt|ReindexResult", "MutatingFacade.kt|ReindexResult.Done", "MutatingFacade.kt|ReindexResult.InFlight",
    "MutatingFacade.kt|AssetWriteOutcome", "MutatingFacade.kt|AssetWriteOutcome.Created",
    "MutatingFacade.kt|AssetWriteOutcome.WrittenButUnindexed",
    "MutatingFacade.kt|AssetWriteOutcome.Exists", "MutatingFacade.kt|AssetWriteOutcome.PageMissing",
    "MutatingFacade.kt|AssetWriteOutcome.Rejected",
    "MutatingFacade.kt|AssetWriteOutcome.Unreadable", "PageIdentityService.kt|PageIdentityService",
    "PageIdentityService.kt|PageIdentityService.Source",
    "PageIdentityService.kt|PageIdentityService.Assignment", "PageRootResolver.kt|PageRootResolver", "PageRootResolver.kt|RootStatus",
    "PageRootResolver.kt|ResolvedClaimants", "PageService.kt|PageService", "PageService.kt|PagePayload", "PageService.kt|PageHtmlPayload",
    "PolicyService.kt|PolicyService", "PolicyService.kt|PolicyService.Companion", "PolicyService.kt|Action",
    "PolicyService.kt|AccessDenied",
    "PolicyService.kt|DenyReason", "PolicyService.kt|RootUnavailable", "ProposalAuthorLabeler.kt|ProposalAuthorLabeler",
    "ProposalAuthorLabeler.kt|ProposalAuthorLabeler.Companion", "ProposalBaseReader.kt|ProposalBaseReader",
    "ProposalFacade.kt|ProposalFacade",
    "ProposalFacade.kt|ProposalCommandResource", "ProposalFacade.kt|ProposeCommand", "ProposalFacade.kt|ProposeCommand.Edit",
    "ProposalFacade.kt|ProposeCommand.Create", "ProposalIdProvider.kt|ProposalIdProvider", "ProposalService.kt|ProposalService",
    "ProposalService.kt|ProposalService.Companion", "ProposalService.kt|ProposalContentWriter", "ProposalService.kt|ProposalAuthor",
    "ProposalService.kt|ProposalApprover", "ProposalService.kt|ProposeOutcome", "ProposalService.kt|ProposeOutcome.Created",
    "ProposalService.kt|ProposeOutcome.StaleBase", "ProposalService.kt|ProposeOutcome.InvalidRequest",
    "ProposalService.kt|ProposeOutcome.InvalidCreateContent",
    "ProposalService.kt|ProposalGuard", "ProposalService.kt|ApplyOutcome", "ProposalService.kt|ApplyOutcome.Applied",
    "ProposalService.kt|ApplyOutcome.Conflicted", "ProposalService.kt|ApplyOutcome.Failed", "ProposalService.kt|ApplyOutcome.NotPending",
    "ProposalService.kt|ApplyOutcome.NotFound", "ProposalService.kt|RebaseOutcome", "ProposalService.kt|RebaseOutcome.Rebased",
    "ProposalService.kt|RebaseOutcome.NotConflicted", "ProposalService.kt|RebaseOutcome.Gone", "ProposalService.kt|RebaseOutcome.NotFound",
    "ProposalService.kt|RejectOutcome", "ProposalService.kt|RejectOutcome.Rejected", "ProposalService.kt|RejectOutcome.NotPending",
    "ProposalService.kt|RejectOutcome.NotFound", "ProposalService.kt|ProposalView", "ProposalService.kt|ProposalSummaryView",
    "ReadFacade.kt|ReadFacade", "ReadFacade.kt|PermalinkResolution", "ReadFacade.kt|PermalinkResolution.Found",
    "ReadFacade.kt|PermalinkResolution.LoserNoUrl",
    "ReadFacade.kt|PermalinkResolution.Retired", "ReadFacade.kt|PermalinkResolution.Unknown", "ReadFacade.kt|PermalinkResolution.Ambiguous",
    "ReadFacade.kt|AssetReadOutcome", "ReadFacade.kt|AssetReadOutcome.NotContentAsset", "ReadFacade.kt|AssetReadOutcome.Found",
    "ReadFacade.kt|AssetReadOutcome.IndexedButMissing", "RebuildScheduler.kt|RebuildScheduler",
    "RebuildScheduler.kt|RebuildScheduler.Alarm", "RebuildScheduler.kt|RebuildScheduler.Companion",
    "RootLossClassifier.kt|RootLossClassifier", "RootLossClassifier.kt|RootLossClassifier.Companion",
    "SearchIndexer.kt|SearchIndexer", "SearchIndexer.kt|SearchIndexer.Companion", "SearchService.kt|SearchService",
    "SearchService.kt|SearchService.Outcome", "SearchService.kt|SearchService.Outcome.Results",
    "SearchService.kt|SearchService.Outcome.InvalidQuery",
    "SearchService.kt|SearchPayload", "SearchService.kt|SearchHitPayload", "SearchService.kt|SearchService.Companion",
    "SectionSplitter.kt|SectionSplitter", "SectionSplitter.kt|SectionSplitter.Companion", "SessionService.kt|SessionService",
    "SessionService.kt|SessionService.Authenticated", "SessionService.kt|SessionService.Companion", "SetupService.kt|SetupService",
    "SetupService.kt|SetupService.Companion", "SetupService.kt|BootstrapOutcome", "SetupService.kt|BootstrapOutcome.Created",
    "SetupService.kt|BootstrapOutcome.UsernameExists", "SetupService.kt|BootstrapOutcome.TokenInvalid", "SetupService.kt|ChangeOutcome",
    "SetupService.kt|ChangeOutcome.Changed", "SetupService.kt|ChangeOutcome.WrongCurrentPassword",
    "SetupService.kt|ChangeOutcome.UserNotFound",
    "SetupService.kt|ResetOutcome", "SetupService.kt|ResetOutcome.Reset", "SetupService.kt|ResetOutcome.TokenInvalid",
    "TreeBuilder.kt|TreeNode",
    "TreeBuilder.kt|TreeNode.Folder", "TreeBuilder.kt|TreeNode.Page", "TreeBuilder.kt|TreeBuilder", "TreeBuilder.kt|TreeBuilder.Sortable",
    "UnifiedDiff.kt|Edit", "UnifiedDiff.kt|Edit.Keep", "UnifiedDiff.kt|Edit.Delete", "UnifiedDiff.kt|Edit.Insert", "UnifiedDiff.kt|Hunk",
    "UnifiedDiff.kt|Lines", "UrlAliasRegistry.kt|UrlAliasRegistry", "UuidV7IdProvider.kt|UuidV7IdProvider",
    "UuidV7ProposalIdProvider.kt|UuidV7ProposalIdProvider",
    "WriteClass.kt|WriteClass", "WriteClass.kt|WriteClass.PageEdit", "WriteClass.kt|WriteClass.PageCreate",
    "WriteClass.kt|WriteClass.AssetWrite",
    "WriteClass.kt|RootedResource", "WritePipeline.kt|WritePipeline", "WritePipeline.kt|WritePipeline.Companion",
    "WritePipeline.kt|FolderUrlWalk",
    "WritePipeline.kt|FolderUrlWalk.Unavailable", "WritePipeline.kt|FolderUrlWalk.Collision", "WritePipeline.kt|FolderUrlWalk.Available",
    "WritePipeline.kt|WriteIntent", "WritePipeline.kt|CreateIntent", "WritePipeline.kt|WriteHistoryHook",
)
