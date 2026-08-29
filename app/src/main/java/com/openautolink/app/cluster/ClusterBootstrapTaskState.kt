package com.openautolink.app.cluster

/** Generation-owned state for the invisible CarAppActivity task. */
internal class ClusterBootstrapTaskState<T : Any> {

    data class Target<T : Any>(
        val generation: Long,
        val owner: T,
    )

    private var generation = Long.MIN_VALUE
    private var owner: T? = null
    private var backgroundRequestedGeneration = Long.MIN_VALUE

    @Synchronized
    fun protect(generation: Long, owner: T) {
        if (generation < this.generation) return
        if (generation > this.generation) {
            backgroundRequestedGeneration = Long.MIN_VALUE
        }
        this.generation = generation
        this.owner = owner
    }

    @Synchronized
    fun requestBackground(generation: Long): Target<T>? {
        if (this.generation != generation) return null
        backgroundRequestedGeneration = generation
        return owner?.let { Target(generation, it) }
    }

    @Synchronized
    fun pendingTargetFor(owner: T): Target<T>? {
        if (this.owner !== owner || backgroundRequestedGeneration != generation) return null
        return Target(generation, owner)
    }

    @Synchronized
    fun isCurrent(target: Target<T>): Boolean =
        generation == target.generation && owner === target.owner &&
            backgroundRequestedGeneration == target.generation

    @Synchronized
    fun destroy(owner: T) {
        if (this.owner === owner) this.owner = null
    }
}
