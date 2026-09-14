package com.plainbase.frameworks.runtime

import com.plainbase.domain.repository.DirtyPageRepository
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.repository.RootTopologyRepository
import com.plainbase.domain.repository.UrlAliasRepository
import com.plainbase.frameworks.sqldelight.PlainbaseDb
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRetirementRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRootTopologyRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository

/** Shared content repositories over a caller-owned database. */
internal class ContentRepositories(db: PlainbaseDb) {
    val idMap: IdMapRepository = SqlDelightIdMapRepository(db)
    val aliases: UrlAliasRepository = SqlDelightUrlAliasRepository(db)
    val checkpoints: PageCheckpointRepository = SqlDelightPageCheckpointRepository(db)
    val dirtyPages: DirtyPageRepository = SqlDelightDirtyPageRepository(db)
    val retirements: RetirementRepository = SqlDelightRetirementRepository(db)
    val topology: RootTopologyRepository = SqlDelightRootTopologyRepository(db)
}
