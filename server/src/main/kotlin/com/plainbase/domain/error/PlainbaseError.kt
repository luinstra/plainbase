package com.plainbase.domain.error

/**
 * Root of the domain error hierarchy. Phase 0 only needs the shape;
 * concrete variants land with the features that produce them.
 */
sealed class PlainbaseError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class ConfigurationError(message: String, cause: Throwable? = null) : PlainbaseError(message, cause)
}
