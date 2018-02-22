package org.ym2149.hernia.util

import java.util.*
import java.util.stream.Stream

internal inline fun <reified T> Stream<out T>.toTypedArray() = toTypedArray(T::class.java)
// When toArray has filled in the array, the component type is no longer T? but T (that may itself be nullable):
internal fun <T> Stream<out T>.toTypedArray(componentType: Class<T>): Array<T> = toArray { size ->
    uncheckedCast<Any, Array<T?>>(java.lang.reflect.Array.newInstance(componentType, size))
}

internal fun <T> Stream<out T?>.filterNotNull(): Stream<T> = uncheckedCast(filter(Objects::nonNull))
internal fun <K, V> Stream<out Pair<K, V>>.toMap(): Map<K, V> = collect<LinkedHashMap<K, V>>(::LinkedHashMap, { m, (k, v) -> m.put(k, v) }, { m, t -> m.putAll(t) })
@Suppress("UNCHECKED_CAST")
internal fun <T, U : T> uncheckedCast(obj: T) = obj as U
