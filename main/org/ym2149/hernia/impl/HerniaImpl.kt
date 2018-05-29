package org.ym2149.hernia.impl

import org.ym2149.hernia.impl.JConcrete.Companion.validate
import org.ym2149.hernia.impl.KConcrete.Companion.validate
import org.ym2149.hernia.impl.KConstructor.Companion.validate
import org.ym2149.hernia.*
import org.ym2149.hernia.util.filterNotNull
import org.ym2149.hernia.util.toTypedArray
import org.ym2149.hernia.util.uncheckedCast
import java.util.*
import java.util.concurrent.Callable
import java.util.stream.Stream
import kotlin.reflect.KClass
import kotlin.reflect.KFunction

/**
 * Create a new [MutableHernia] with no parent.
 *
 * Basic usage:
 * * Add classes/factories/objects to the Hernia using [MutableHernia.impl], [MutableHernia.factory] and [MutableHernia.obj]
 * * Then ask it for a type using [Hernia.get] and it will create (and cache) the object graph for you
 * * You can use [Hernia.getAll] to get all objects of a type, e.g. by convention pass in [Unit] to run side-effects
 *
 * How it works:
 * * [Hernia.get] finds the unique registered class/factory/object for the given type (or fails)
 * * If it's an object, that object is returned
 * * If it's a factory, it is executed with args obtained recursively from the same Hernia
 * * If it's a class, it is instantiated using a public constructor in the same way as a factory
 *     * Of the public constructors that can be satisfied, the one that consumes the most args is chosen
 *
 * Advanced usage:
 * * Use an array parameter to get one-or-more args of the component type, make it nullable for zero-or-more
 * * If a Hernia can't satisfy a type (or array param) and has a parent, it asks the parent
 *     * Typically the root Hernia in the hierarchy will manage all singletons of the process
 */
fun hernia(): MutableHernia = HerniaImpl(null)

private class SimpleProvider<T : Any>(override val obj: T) : Provider<T> {
    override val type get() = obj.javaClass
}

private class LazyProvider<T>(private val busyProviders: BusyProviders, private val underlying: Any?, override val type: Class<T>, val chooseInvocation: () -> Callable<T>) : Provider<T> {
    override val obj by lazy { busyProviders.runFactory(this) }
    override fun toString() = underlying.toString()
}

private class Invocation<P, T>(val constructor: PublicConstructor<P, T>, val argSuppliers: List<Pair<P, ArgSupplier>>) : Callable<T> {
    fun providerCount() = argSuppliers.stream().filter { (_, supplier) -> supplier.provider != null }.count() // Allow repeated providers.
    override fun call() = constructor(argSuppliers)
    override fun toString() = constructor.toString()
}

private class BusyProviders {
    private val busyProviders = mutableMapOf<LazyProvider<*>, Callable<*>>()
    fun <T> runFactory(provider: LazyProvider<T>): T {
        if (busyProviders.contains(provider)) throw CircularDependencyException("Provider '$provider' is already busy: ${busyProviders.values}")
        val invocation = provider.chooseInvocation()
        busyProviders.put(provider, invocation)
        try {
            return invocation.call()
        } finally {
            busyProviders.remove(provider)
        }
    }
}

private val autotypes: Map<Class<*>, Class<*>> = mutableMapOf<Class<*>, Class<*>>().apply {
    Arrays::class.java.declaredMethods.filter { it.name == "hashCode" }.map { it.parameterTypes[0].componentType }.filter { it.isPrimitive }.forEach {
        val boxed = java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(it, 1), 0).javaClass
        put(it, boxed)
        put(boxed, it)
    }
}

private infix fun Class<*>.isSatisfiedBy(clazz: Class<*>): Boolean {
    return isAssignableFrom(clazz) || autotypes[this] == clazz
}

private class HerniaImpl(private val parent: HerniaImpl?, private val busyProviders: BusyProviders = parent?.busyProviders ?: BusyProviders()) : MutableHernia {
    private val providers = mutableMapOf<Class<*>, MutableList<Provider<*>>>()
    private fun add(provider: Provider<*>, type: Class<*> = provider.type, registered: MutableSet<Class<*>> = mutableSetOf()) {
        if (!registered.add(type)) return
        providers[type]?.add(provider) ?: providers.put(type, mutableListOf(provider))
        Stream.concat(Arrays.stream(type.interfaces), Stream.of(type.superclass, autotypes[type]).filterNotNull()).forEach {
            add(provider, it, registered)
        }
    }

    /** The non-empty list of providers, or null. */
    private fun <T> findProviders(clazz: Class<T>): List<Provider<T>>? = uncheckedCast(providers[clazz]) ?: parent?.findProviders(clazz)

    private fun dropAll(serviceClass: Class<*>) {
        val removed = mutableSetOf<Provider<*>>()
        providers.iterator().run {
            while (hasNext()) {
                val entry = next()
                if (serviceClass isSatisfiedBy entry.key) {
                    removed.addAll(entry.value)
                    remove()
                }
            }
        }
        providers.values.iterator().run {
            while (hasNext()) {
                val providers = next()
                providers.removeAll(removed)
                if (providers.isEmpty()) remove()
            }
        }
    }

    override fun <T> getOrNull(clazz: Class<T>) = findProviders(clazz)?.run { (singleOrNull() ?: throw TooManyProvidersException(clazz.toString())).obj }
    override fun <T> getAll(clazz: Class<T>) = findProviders(clazz)?.map { it.obj } ?: emptyList()
    override fun child(): MutableHernia = HerniaImpl(this)
    override fun obj(obj: Any) = add(SimpleProvider(obj))
    override fun <T : Any> obj(service: KClass<T>, obj: T) {
        dropAll(service.java)
        obj(obj)
    }

    override fun <S : Any, T : S> factory(service: KClass<S>, factory: KFunction<T>) = factory.validate().let {
        dropAll(service.java)
        addFactory(it)
    }

    override fun <S : Any, T : S> impl(service: KClass<S>, impl: KClass<T>) = impl.validate().let {
        dropAll(service.java)
        addConcrete(it)
    }

    override fun <S : Any, T : S> impl(service: KClass<S>, impl: Class<T>) = impl.validate().let {
        dropAll(service.java)
        addConcrete(it)
    }

    override fun factory(factory: KFunction<*>) = addFactory(factory.validate())
    private fun <T> addFactory(factory: KConstructor<T>) {
        val type = factory.kFunction.returnType.toJavaType().let { if (it == Void.TYPE) Unit::class.java else it as Class<*> }
        add(LazyProvider(busyProviders, factory, uncheckedCast(type)) { factory.toInvocation() })
    }

    override fun factory(h: Hernia, type: KClass<*>) = addFactory(h, type)
    private fun <T : Any> addFactory(h: Hernia, type: KClass<T>) {
        add(LazyProvider(busyProviders, h, type.java) { Callable { h[type] } })
    }

    override fun impl(impl: KClass<*>) = implGeneric(impl)
    private fun <T : Any> implGeneric(type: KClass<T>) = addConcrete(type.validate())
    override fun impl(impl: Class<*>) = implGeneric(impl)
    private fun <T> implGeneric(type: Class<T>) = addConcrete(type.validate())
    private fun <P : Param, T, C : PublicConstructor<P, T>> addConcrete(concrete: Concrete<T, C>) {
        add(LazyProvider(busyProviders, concrete, concrete.clazz) {
            var fail: UnsatisfiableParamException? = null
            val satisfiable = concrete.publicConstructors.mapNotNull { constructor ->
                try {
                    constructor.toInvocation()
                } catch (e: UnsatisfiableParamException) {
                    fail?.addSuppressed(e) ?: run { fail = e }
                    null
                }
            }
            if (satisfiable.isEmpty()) throw fail!!
            val greediest = mutableListOf(satisfiable[0])
            var providerCount = greediest[0].providerCount()
            satisfiable.stream().skip(1).forEach next@ {
                val pc = it.providerCount()
                if (pc < providerCount) return@next
                if (pc > providerCount) {
                    greediest.clear()
                    providerCount = pc
                }
                greediest += it
            }
            greediest.singleOrNull() ?: throw NoUniqueGreediestSatisfiableConstructorException(greediest.toString())
        })
    }

    private fun <T> arrayProvider(arrayType: Class<*>, componentType: Class<T>): LazyProvider<Array<T>>? {
        val providers = findProviders(componentType) ?: return null
        return LazyProvider(busyProviders, null, uncheckedCast(arrayType)) {
            Callable { providers.stream().map { it.obj }.toTypedArray(componentType) }
        }
    }

    private fun <P : Param, T> PublicConstructor<P, T>.toInvocation() = Invocation(this, params.mapNotNull { param ->
        if (param.type.isArray) {
            val provider = arrayProvider(param.type, param.type.componentType)
            when (provider) {
                null -> param.supplierWhenUnsatisfiable()?.let { param to it }
                else -> param to ArgSupplier(provider)
            }
        } else {
            val providers = findProviders(param.type)
            when (providers?.size) {
                null -> param.supplierWhenUnsatisfiable()?.let { param to it }
                1 -> param to ArgSupplier(providers[0])
                else -> throw TooManyProvidersException(param.toString())
            }
        }
    })
}
