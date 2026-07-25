package com.plainbase.domain.service

import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import java.nio.file.Path

/** A local test root: [name] over [path] (the nativeTest twin of the JVM harness helper). */
fun localRoot(name: String, path: Path): Root =
    Root(RootName.require(name), RootBackend.Local(path), editable = true, history = HistoryMode.OFF)
