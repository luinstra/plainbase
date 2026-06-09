package com.plainbase.frameworks.koin

import com.plainbase.domain.principal.PasswordHasher
import com.plainbase.frameworks.security.Argon2PasswordHasher
import org.koin.dsl.module

val securityModule = module {
    single<PasswordHasher> { Argon2PasswordHasher() }
}
