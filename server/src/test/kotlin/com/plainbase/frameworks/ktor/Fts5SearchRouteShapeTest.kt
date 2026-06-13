package com.plainbase.frameworks.ktor

/**
 * The embedded engine's leaf of the route-level shape contract: production route wiring via the
 * chunk-6 harness. Lives with the route harness per the plan's §S3 home for the
 * route-level contract (`src/test/.../ktor/`); the abstract spec stays engine-blind by import.
 */
class Fts5SearchRouteShapeTest : SearchRouteShapeContract(
    ServeSearchRoute { root, block -> restTest(root) { block(client) } },
)
