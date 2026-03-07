package com.nextvm.core.framework.parsing

import android.util.SparseArray
import timber.log.Timber

/**
 * Kotlin adaptation of Android 16's SplitDependencyLoader.java.
 *
 * Source: android16-frameworks-base/core/java/android/content/pm/split/SplitDependencyLoader.java
 *         (252 lines)
 *
 * Traverses the dependency tree for split APKs and ensures they are loaded
 * in the correct order (root-to-leaf). This is CRITICAL for modern apps
 * that use split APKs (dynamic delivery, feature modules, config splits).
 *
 * The real Android OS uses this exact algorithm at runtime when
 * ClassLoader hierarchies are built for apps with splits.
 */
object SplitDependencyResolver {

    private const val TAG = "SplitDepResolver"

    class IllegalDependencyException(message: String) : Exception(message)

    /**
     * Build the split dependency tree from a PackageLiteInfo.
     * Direct port of SplitDependencyLoader.createDependenciesFromPackage().
     *
     * @return SparseArray mapping split index → dependency indices.
     *         Index 0 = base APK. Split indices are 1-based (splitIndex + 1).
     */
    fun createDependencies(pkg: PackageLiteInfo): SparseArray<IntArray> {
        val splitDependencies = SparseArray<IntArray>()

        // Base depends on nothing
        splitDependencies.put(0, intArrayOf(-1))

        if (pkg.splitNames.isEmpty()) {
            return splitDependencies
        }

        // First: write uses-split dependencies (feature splits)
        for (splitIdx in pkg.splitNames.indices) {
            if (!pkg.isFeatureSplits[splitIdx]) continue

            val targetIdx: Int
            val splitDependency = pkg.usesSplitNames[splitIdx]
            if (splitDependency != null) {
                val depIdx = pkg.splitNames.binarySearch(splitDependency)
                if (depIdx < 0) {
                    throw IllegalDependencyException(
                        "Split '${pkg.splitNames[splitIdx]}' requires split " +
                        "'$splitDependency', which is missing."
                    )
                }
                targetIdx = depIdx + 1
            } else {
                // Implicitly depend on base
                targetIdx = 0
            }
            splitDependencies.put(splitIdx + 1, intArrayOf(targetIdx))
        }

        // Second: write configForSplit reverse-dependencies
        for (splitIdx in pkg.splitNames.indices) {
            if (pkg.isFeatureSplits[splitIdx]) continue

            val targetSplitIdx: Int
            val configForSplit = pkg.configForSplit[splitIdx]
            if (configForSplit != null) {
                val depIdx = pkg.splitNames.binarySearch(configForSplit)
                if (depIdx < 0) {
                    throw IllegalDependencyException(
                        "Split '${pkg.splitNames[splitIdx]}' targets split " +
                        "'$configForSplit', which is missing."
                    )
                }
                if (!pkg.isFeatureSplits[depIdx]) {
                    throw IllegalDependencyException(
                        "Split '${pkg.splitNames[splitIdx]}' declares itself as config " +
                        "for non-feature split '${pkg.splitNames[depIdx]}'"
                    )
                }
                targetSplitIdx = depIdx + 1
            } else {
                targetSplitIdx = 0
            }

            val existing = splitDependencies.get(targetSplitIdx)
            splitDependencies.put(targetSplitIdx, appendInt(existing, splitIdx + 1))
        }

        // Verify no cycles
        verifyCycleFreeDependencies(splitDependencies, pkg.splitNames)

        Timber.tag(TAG).d(
            "Built dependency tree for ${pkg.packageName}: " +
            "${splitDependencies.size()} entries"
        )

        return splitDependencies
    }

    /**
     * Get the ordered list of split indices that must be loaded before the given split.
     * Returns indices from root to leaf (load order).
     */
    fun getLoadOrder(
        splitIdx: Int,
        dependencies: SparseArray<IntArray>
    ): List<Int> {
        if (splitIdx == 0) return listOf(0) // Base has no deps

        val linearDeps = mutableListOf<Int>()
        linearDeps.add(splitIdx)

        var current = splitIdx
        while (true) {
            val deps = dependencies.get(current)
            current = if (deps != null && deps.isNotEmpty()) deps[0] else -1
            if (current < 0) break
            linearDeps.add(current)
        }

        // Reverse to get root-to-leaf order
        return linearDeps.reversed()
    }

    /**
     * Get config split indices for a given split.
     */
    fun getConfigSplitIndices(
        splitIdx: Int,
        dependencies: SparseArray<IntArray>
    ): IntArray {
        val deps = dependencies.get(splitIdx)
        if (deps == null || deps.size <= 1) return intArrayOf()
        return deps.copyOfRange(1, deps.size)
    }

    private fun appendInt(src: IntArray?, elem: Int): IntArray {
        if (src == null) return intArrayOf(elem)
        val dst = src.copyOf(src.size + 1)
        dst[src.size] = elem
        return dst
    }

    private fun verifyCycleFreeDependencies(
        deps: SparseArray<IntArray>,
        splitNames: List<String>
    ) {
        val visited = mutableSetOf<Int>()
        for (i in 0 until deps.size()) {
            var splitIdx = deps.keyAt(i)
            visited.clear()

            while (splitIdx != -1) {
                if (!visited.add(splitIdx)) {
                    throw IllegalDependencyException(
                        "Cycle detected in split dependencies"
                    )
                }
                val depArray = deps.get(splitIdx)
                splitIdx = depArray?.firstOrNull() ?: -1
            }
        }
    }
}
