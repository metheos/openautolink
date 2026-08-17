package com.openautolink.companion.network

/**
 * Serializes process-wide Android network binding around socket creation.
 *
 * Android's bindProcessToNetwork affects every socket created in the process, so
 * known socket creators share this lock. Existing sockets are not migrated.
 */
object ProcessNetworkBindingLock {
    private val lock = Any()

    fun <R> withLock(block: () -> R): R = synchronized(lock, block)
}

class ProcessNetworkRestoreException(message: String) : IllegalStateException(message)

class ProcessNetworkBinding<T>(
    private val currentNetwork: () -> T?,
    private val bindProcess: (T?) -> Boolean,
    private val warning: (String) -> Unit,
) {
    fun <R> withNetwork(
        targetNetwork: T?,
        onUnrestored: (R) -> Unit = {},
        block: () -> R,
    ): R = ProcessNetworkBindingLock.withLock {
        val previousNetwork = currentNetwork()
        if (previousNetwork == targetNetwork) {
            return@withLock block()
        }

        check(bindProcess(targetNetwork)) {
            "Target network is no longer valid"
        }

        var result: Any? = null
        var blockFailure: Throwable? = null
        try {
            result = block()
        } catch (failure: Throwable) {
            blockFailure = failure
        }

        val restoreResult = runCatching { bindProcess(previousNetwork) }
        if (restoreResult.getOrDefault(false) != true) {
            val clearResult = runCatching { bindProcess(null) }
            val cleared = clearResult.getOrDefault(false) == true
            val reason = restoreResult.exceptionOrNull()?.message
                ?: "restore returned false"
            val clearError = clearResult.exceptionOrNull()?.message
                ?.let { ", clear error=$it" }
                .orEmpty()
            warning(
                "Could not restore previous process network ($reason); " +
                    "fallback clear attempted (cleared=$cleared$clearError)",
            )
            if (!cleared) {
                @Suppress("UNCHECKED_CAST")
                if (blockFailure == null) runCatching { onUnrestored(result as R) }
                val failure = ProcessNetworkRestoreException(
                    "Process network remained bound after listener creation",
                )
                blockFailure?.let(failure::addSuppressed)
                throw failure
            }
        }

        blockFailure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        result as R
    }
}
