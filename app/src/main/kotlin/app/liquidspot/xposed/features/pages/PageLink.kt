package app.liquidspot.xposed.features.pages

import android.os.Bundle
import android.view.View
import java.lang.reflect.Modifier

object PageLink {
    private val URI = Regex("spotify:(album|playlist|artist|show|episode|track)[:/]([A-Za-z0-9]{22})")
    private const val CONTAINER = "androidx.fragment.app.FragmentContainerView"
    private const val MAX_DEPTH = 3
    private const val MAX_OBJECTS = 300

    fun find(view: View): String? {
        var parent = view.parent
        while (parent is View) {
            if (parent.javaClass.name == CONTAINER) {
                val fragment = runCatching { parent.javaClass.getMethod("getFragment").invoke(parent) }.getOrNull()
                fragment?.let { scanObject(it) }?.let { return it }
            }
            parent = parent.parent
        }
        return null
    }

    private fun link(text: String): String? = URI.find(text)?.let { "https://open.spotify.com/${it.groupValues[1]}/${it.groupValues[2]}" }

    private fun scanObject(root: Any): String? {
        bundlesOf(root).forEach { bundle -> scanBundle(bundle, 0)?.let { return it } }
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val queue = ArrayDeque<Pair<Any, Int>>()
        queue.add(root to 0)
        seen.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_OBJECTS) {
            val (target, depth) = queue.removeFirst()
            var type: Class<*>? = target.javaClass
            while (type != null && type != Any::class.java) {
                for (field in type.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                    val value = runCatching { field.isAccessible = true; field.get(target) }.getOrNull() ?: continue
                    when (value) {
                        is String -> link(value)?.let { return it }
                        is Bundle -> scanBundle(value, 0)?.let { return it }
                        is View, is Class<*> -> Unit
                        else -> if (depth < MAX_DEPTH && isAppObject(value) && seen.add(value)) queue.add(value to depth + 1)
                    }
                }
                type = type.superclass
            }
        }
        return null
    }

    private fun bundlesOf(target: Any): List<Bundle> {
        val out = ArrayList<Bundle>()
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.type != Bundle::class.java) continue
                (runCatching { field.isAccessible = true; field.get(target) }.getOrNull() as? Bundle)?.let(out::add)
            }
            type = type.superclass
        }
        return out
    }

    private fun isAppObject(value: Any): Boolean {
        val name = value.javaClass.name
        return !name.startsWith("java.") && !name.startsWith("android.") && !name.startsWith("kotlin.") && !name.startsWith("kotlinx.")
    }

    private fun scanBundle(bundle: Bundle, depth: Int): String? {
        for (key in runCatching { bundle.keySet().toList() }.getOrDefault(emptyList())) {
            @Suppress("DEPRECATION")
            val value = runCatching { bundle.get(key) }.getOrNull() ?: continue
            when (value) {
                is String -> link(value)?.let { return it }
                is Bundle -> if (depth < MAX_DEPTH) scanBundle(value, depth + 1)?.let { return it }
                else -> link(value.toString())?.let { return it }
            }
        }
        return null
    }
}
