/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls.tower


sealed class TowerGroupKind(val index: Byte) : Comparable<TowerGroupKind> {
    abstract class WithDepth(index: Byte, val depth: Int) : TowerGroupKind(index)

    object Start : TowerGroupKind(0b0)

    object ClassifierPrioritized : TowerGroupKind(1)

    object Qualifier : TowerGroupKind(2)

    object Classifier : TowerGroupKind(3)

    class TopPrioritized(depth: Int) : WithDepth(4, depth)

    object Member : TowerGroupKind(5)

    class Local(depth: Int) : WithDepth(6, depth)

    class ImplicitOrNonLocal(depth: Int, val kindForDebugSake: String) : WithDepth(7, depth)

    object InvokeExtension : TowerGroupKind(8)

    object QualifierValue : TowerGroupKind(9)

    object Last : TowerGroupKind(0b1111)

    override fun compareTo(other: TowerGroupKind): Int {
        val indexResult = index.compareTo(other.index)
        if (indexResult != 0) return indexResult
        if (this is WithDepth && other is WithDepth) {
            return depth.compareTo(other.depth)
        }
        return 0
    }

    @Suppress("FunctionName")
    companion object {
        // These two groups intentionally have the same priority
        fun Implicit(depth: Int): TowerGroupKind = ImplicitOrNonLocal(depth, "Implicit")
        fun NonLocal(depth: Int): TowerGroupKind = ImplicitOrNonLocal(depth, "NonLocal")
    }
}

@Suppress("FunctionName", "unused", "PropertyName")
class TowerGroup
private constructor(
    private val code: Long,
    private val invokeResolvePriority: InvokeResolvePriority = InvokeResolvePriority.NONE
) : Comparable<TowerGroup> {
    companion object {

        private const val KIND_MASK = 0b1111
        private val KIND_SIZE_BITS: Int = Integer.bitCount(KIND_MASK)
        private const val DEPTH_MASK = 0b1111111111
        private val DEPTH_SIZE_BITS: Int = Integer.bitCount(DEPTH_MASK)
        private const val USED_BITS_MASK: Long = 0b111111 // max size 64 bits
        private const val TOTAL_BITS = 64
        private val USABLE_BITS = java.lang.Long.numberOfLeadingZeros(USED_BITS_MASK)


        private fun subscript(code: Long, kind: TowerGroupKind): TowerGroup {
            val usedBits = (code and USED_BITS_MASK).toInt()
            return when (kind) {
                is TowerGroupKind.WithDepth -> {
                    val kindUsedBits = usedBits + KIND_SIZE_BITS
                    val depthUsedBits = kindUsedBits + DEPTH_SIZE_BITS
                    require(kind.depth <= DEPTH_MASK) {
                        "Depth overflow: requested: ${kind.depth}, allowed: $DEPTH_MASK"
                    }
                    require(depthUsedBits <= USABLE_BITS) {
                        "BitGroup overflow: newUsedBits: $depthUsedBits, original: ${code.toULong().toString(2)}, usedBits: $usedBits"
                    }
                    TowerGroup(
                        code or kind.index.toLong().shl(TOTAL_BITS - kindUsedBits)
                                or kind.depth.toLong().shl(TOTAL_BITS - depthUsedBits)
                                or depthUsedBits.toLong()
                    )
                }
                else -> {
                    val usesBits = usedBits + KIND_SIZE_BITS

                    require(usesBits <= USABLE_BITS)
                    TowerGroup(
                        code or kind.index.toLong().shl(TOTAL_BITS - usesBits) or usesBits.toLong()
                    )
                }
            }
        }

        private fun kindOf(kind: TowerGroupKind): TowerGroup {
            return subscript(0, kind)
        }

        val EmptyRoot = TowerGroup(0)

        val Start = kindOf(TowerGroupKind.Start)

        val ClassifierPrioritized = kindOf(TowerGroupKind.ClassifierPrioritized)

        val Qualifier = kindOf(TowerGroupKind.Qualifier)

        val Classifier = kindOf(TowerGroupKind.Classifier)

        val QualifierValue = kindOf(TowerGroupKind.QualifierValue)

        val Member = kindOf(TowerGroupKind.Member)

        fun Local(depth: Int) = kindOf(TowerGroupKind.Local(depth))

        fun Implicit(depth: Int) = kindOf(TowerGroupKind.Implicit(depth))
        fun NonLocal(depth: Int) = kindOf(TowerGroupKind.NonLocal(depth))

        fun TopPrioritized(depth: Int) = kindOf(TowerGroupKind.TopPrioritized(depth))

        val Last = kindOf(TowerGroupKind.Last)
    }

    private fun kindOf(kind: TowerGroupKind): TowerGroup = subscript(code, kind)

    val Member get() = kindOf(TowerGroupKind.Member)

    fun Local(depth: Int) = kindOf(TowerGroupKind.Local(depth))

    fun Implicit(depth: Int) = kindOf(TowerGroupKind.Implicit(depth))
    fun NonLocal(depth: Int) = kindOf(TowerGroupKind.NonLocal(depth))

    val InvokeExtension get() = kindOf(TowerGroupKind.InvokeExtension)

    fun TopPrioritized(depth: Int) = kindOf(TowerGroupKind.TopPrioritized(depth))

    // Treating `a.foo()` common calls as more prioritized than `a.foo.invoke()`
    // It's not the same as TowerGroupKind because it's not about tower levels, but rather a different dimension semantically.
    // It could be implemented via another TowerGroupKind, but it's not clear what priority should be assigned to the new TowerGroupKind
    fun InvokeResolvePriority(invokeResolvePriority: InvokeResolvePriority): TowerGroup {
        if (invokeResolvePriority == InvokeResolvePriority.NONE) return this
        return TowerGroup(code, invokeResolvePriority)
    }

    override fun compareTo(other: TowerGroup): Int {
        val result = java.lang.Long.compareUnsigned(code, other.code)
        if (result != 0) return result
        return invokeResolvePriority.compareTo(other.invokeResolvePriority)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TowerGroup

        if (code != other.code) return false
        if (invokeResolvePriority != other.invokeResolvePriority) return false

        return true
    }

    override fun toString(): String {
        return "TowerGroup(code=${code.toString(2)}, invokeResolvePriority=$invokeResolvePriority)"
    }

    override fun hashCode(): Int {
        var result = code.hashCode()
        result = 31 * result + invokeResolvePriority.hashCode()
        return result
    }


}

enum class InvokeResolvePriority {
    NONE, INVOKE_RECEIVER, COMMON_INVOKE, INVOKE_EXTENSION;
}
