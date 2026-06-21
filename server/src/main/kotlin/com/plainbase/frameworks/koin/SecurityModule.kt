package com.plainbase.frameworks.koin

import com.plainbase.domain.principal.PasswordHasher
import com.plainbase.domain.principal.TokenMinter
import com.plainbase.domain.principal.TokenSecretHasher
import com.plainbase.domain.repository.ApiTokenRepository
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.frameworks.security.ApiTokenMinter
import com.plainbase.frameworks.security.Argon2PasswordHasher
import com.plainbase.frameworks.security.TokenHasher
import org.koin.dsl.module
import kotlin.time.Clock

val securityModule = module {
    single<PasswordHasher> { Argon2PasswordHasher() }
    single<TokenSecretHasher> { TokenHasher() }
    single<TokenMinter> { ApiTokenMinter(get()) }
    single { ApiTokenService(minter = get(), hasher = get(), tokens = get<ApiTokenRepository>(), clock = Clock.System) }
}
