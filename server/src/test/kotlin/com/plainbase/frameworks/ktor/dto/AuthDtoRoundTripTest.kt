package com.plainbase.frameworks.ktor.dto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException

/**
 * The A4a auth DTOs encode/decode through the scoped [RestJson] faithfully (the `PageCreateRoutes` manual-decode
 * idiom — NOT content-negotiation, so NO reflect-config triple is needed; the native round-trip is the §0.12
 * proof). A malformed body throws [SerializationException] (the route maps to 400, never 500).
 */
class AuthDtoRoundTripTest : FunSpec({

    test("LoginRequest round-trips through RestJson") {
        val json = RestJson.encodeToString(LoginRequest.serializer(), LoginRequest("alice", "pw"))
        RestJson.decodeFromString(LoginRequest.serializer(), json) shouldBe LoginRequest("alice", "pw")
    }

    test("LoginResponse + SessionResponse use snake_case csrf_token and round-trip") {
        val resp = RestJson.encodeToString(LoginResponse.serializer(), LoginResponse("abc"))
        (resp.contains("csrf_token")) shouldBe true
        RestJson.decodeFromString(LoginResponse.serializer(), resp) shouldBe LoginResponse("abc")

        val session = SessionResponse(authenticated = true, username = "alice", csrfToken = "abc", authMode = "builtin")
        val sj = RestJson.encodeToString(SessionResponse.serializer(), session)
        sj.contains("auth_mode") shouldBe true
        RestJson.decodeFromString(SessionResponse.serializer(), sj) shouldBe session
    }

    test("the setup/reset/change/admin DTOs round-trip with their snake_case wire fields") {
        val setup = SetupConsumeRequest("tok", "alice", "pw")
        RestJson.decodeFromString(
            SetupConsumeRequest.serializer(),
            RestJson.encodeToString(SetupConsumeRequest.serializer(), setup),
        ) shouldBe
            setup

        val reset = ResetConsumeRequest("tok", "new")
        val rj = RestJson.encodeToString(ResetConsumeRequest.serializer(), reset)
        (rj.contains("new_password")) shouldBe true
        RestJson.decodeFromString(ResetConsumeRequest.serializer(), rj) shouldBe reset

        val change = ChangePasswordRequest("old", "new")
        val cj = RestJson.encodeToString(ChangePasswordRequest.serializer(), change)
        (cj.contains("current_password") && cj.contains("new_password")) shouldBe true

        val create = CreateUserRequest("bob", "Bob", "editor")
        RestJson.decodeFromString(CreateUserRequest.serializer(), RestJson.encodeToString(CreateUserRequest.serializer(), create)) shouldBe
            create
    }

    test("the user list/create responses never carry a password/hash field") {
        val created = RestJson.encodeToString(CreatedUserResponse.serializer(), CreatedUserResponse("u1", "bob", "tok"))
        (created.contains("password") || created.contains("hash")) shouldBe false
        val list = RestJson.encodeToString(UserListResponse.serializer(), UserListResponse(listOf(UserResponse("u1", "bob", null, false))))
        (list.contains("password") || list.contains("hash")) shouldBe false
    }

    test("a malformed LoginRequest body throws SerializationException (the route's 400, never 500)") {
        shouldThrow<SerializationException> {
            RestJson.decodeFromString(LoginRequest.serializer(), """{"username":"alice"}""") // missing password
        }
    }
})
