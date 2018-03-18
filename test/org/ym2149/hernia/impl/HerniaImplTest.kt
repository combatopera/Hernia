package org.ym2149.hernia.impl

import org.assertj.core.api.Assertions.catchThrowable
import org.hamcrest.CoreMatchers.*
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import org.ym2149.hernia.*
import org.ym2149.hernia.util.uncheckedCast
import java.io.Closeable
import java.io.IOException
import java.io.Serializable
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaMethod
import kotlin.test.assertEquals
import kotlin.test.fail

open class HerniaImplTest {
    private val mh = mutableHernia()

    class Config(val info: String)
    interface A
    interface B {
        val a: A
    }

    class AImpl(val config: Config) : A
    class BImpl(override val a: A) : B
    class Spectator {
        init {
            fail("Should not be instantiated.")
        }
    }

    @Test
    fun `basic functionality`() {
        val config = Config("woo")
        mh.obj(config)
        mh.impl(AImpl::class)
        mh.impl(BImpl::class)
        mh.impl(Spectator::class)
        val b = mh[B::class]
        // An impl is instantiated at most once per Hernia:
        assertSame(b.a, mh[A::class])
        assertSame(b, mh[B::class])
        // More specific type to expose config without casting:
        val a = mh[AImpl::class]
        assertSame(b.a, a)
        assertSame(config, a.config)
    }

    private fun createA(config: Config): A = AImpl(config) // Declared return type is significant.
    internal open fun createB(): B = fail("Should not be called.")
    @Test
    fun `factory works`() {
        mh.obj(Config("x"))
        mh.factory(this::createA) // Observe private is OK.
        assertSame(AImpl::class.java, mh[A::class].javaClass)
        // The factory declares A not AImpl as its return type, and mh doesn't try to be clever:
        catchThrowable { mh[AImpl::class] }.run {
            assertSame(NoSuchProviderException::class.java, javaClass)
            assertEquals(AImpl::class.toString(), message)
        }
    }

    @Ignore
    class Subclass : HerniaImplTest() { // Should not run as tests.
        @Suppress("unused")
        private fun createA(@Suppress("UNUSED_PARAMETER") config: Config): A = fail("Should not be called.")

        override fun createB() = BImpl(AImpl(Config("Subclass"))) // More specific return type is OK.
    }

    @Suppress("MemberVisibilityCanPrivate")
    internal fun addCreateATo(mh: MutableHernia) {
        mh.factory(this::createA)
    }

    @Suppress("MemberVisibilityCanPrivate")
    internal fun addCreateBTo(mh: MutableHernia) {
        mh.factory(this::createB)
    }

    @Test
    fun `private factory is not virtual`() {
        val baseMethod = this::createA.javaMethod!!
        // Check the Subclass version would override if baseMethod wasn't private:
        Subclass::class.java.getDeclaredMethod(baseMethod.name, *baseMethod.parameterTypes)
        mh.obj(Config("x"))
        Subclass().addCreateATo(mh)
        mh[A::class] // Should not blow up.
    }

    @Test
    fun `non-private factory is virtual`() {
        Subclass().addCreateBTo(mh)
        assertEquals("Subclass", (mh[B::class].a as AImpl).config.info) // Check overridden function was called.
        // The signature that was added declares B not BImpl as its return type:
        catchThrowable { mh[BImpl::class] }.run {
            assertSame(NoSuchProviderException::class.java, javaClass)
            assertEquals(BImpl::class.toString(), message)
        }
    }

    private fun returnsYay() = "yay"
    class TakesString(@Suppress("UNUSED_PARAMETER") text: String)

    @Test
    fun `too many providers`() {
        mh.obj("woo")
        mh.factory(this::returnsYay)
        mh.impl(TakesString::class)
        catchThrowable { mh[TakesString::class] }.run {
            assertSame(TooManyProvidersException::class.java, javaClass)
            assertEquals(TakesString::class.constructors.single().parameters[0].toString(), message)
            assertThat(message, containsString(" #0 "))
            assertThat(message, endsWith(TakesString::class.qualifiedName))
        }
    }

    class TakesStringOrInt(val text: String) {
        @Suppress("unused")
        constructor(number: Int) : this(number.toString())
    }

    @Test
    fun `too many providers with alternate constructor`() {
        mh.obj("woo")
        mh.factory(this::returnsYay)
        mh.impl(TakesStringOrInt::class)
        val constructors = TakesStringOrInt::class.constructors.toList()
        catchThrowable { mh[TakesStringOrInt::class] }.run {
            assertSame(NoSuchProviderException::class.java, javaClass)
            assertEquals(constructors[0].parameters[0].toString(), message)
            assertThat(message, containsString(" #0 "))
            assertThat(message, endsWith(TakesStringOrInt::class.qualifiedName))
            suppressed.single().run {
                assertSame(TooManyProvidersException::class.java, javaClass)
                assertEquals(constructors[1].parameters[0].toString(), message)
                assertThat(message, containsString(" #0 "))
                assertThat(message, endsWith(TakesStringOrInt::class.qualifiedName))
            }
        }
        mh.obj(123)
        assertEquals("123", mh[TakesStringOrInt::class].text)
    }

    @Test
    fun genericClass() {
        class G<out T : Serializable>(val arg: T)
        mh.obj("arg")
        mh.impl(G::class)
        assertEquals("arg", mh[G::class].arg) // Can't inspect type arg T as no such thing exists.
    }

    private fun <X : Closeable, Y : X> ntv(a: Y) = a.toString()
    @Test
    fun `nested type variable`() {
        // First check it's actually legal to pass any old Closeable into the function:
        val arg = Closeable {}
        assertEquals(arg.toString(), ntv(arg))
        // Good, now check Hernia can do it:
        val ntv: Function1<Closeable, String> = this::ntv
        mh.factory(uncheckedCast<Any, KFunction<String>>(ntv))
        mh.obj(arg)
        assertEquals(arg.toString(), mh[String::class])
    }

    class PTWMB<out Y>(val arg: Y) where Y : Closeable, Y : Serializable
    private class CloseableAndSerializable : Closeable, Serializable {
        override fun close() {}
    }

    @Test
    fun `parameter type with multiple bounds in java`() {
        // At compile time we must pass something Closeable and Serializable into the constructor:
        CloseableAndSerializable().let { assertSame(it, PTWMB(it).arg) }
        // But at runtime only Closeable is needed (and Serializable is not enough) due to the leftmost bound erasure rule:
        mh.impl(PTWMB::class.java)
        mh.obj(object : Serializable {})
        catchThrowable { mh[PTWMB::class] }.run {
            assertSame(NoSuchProviderException::class.java, javaClass)
            assertThat(message, containsString(" #0 "))
            assertThat(message, endsWith(PTWMB::class.constructors.single().javaConstructor.toString()))
        }
        val arg = Closeable {}
        mh.obj(arg)
        assertSame(arg, mh[PTWMB::class].arg)
    }

    @Test
    fun `parameter type with multiple bounds in kotlin`() {
        mh.impl(PTWMB::class)
        mh.obj(object : Serializable {})
        catchThrowable { mh[PTWMB::class] }.run {
            assertSame(NoSuchProviderException::class.java, javaClass)
            assertEquals(PTWMB::class.constructors.single().parameters[0].toString(), message)
            assertThat(message, containsString(" #0 "))
            assertThat(message, containsString(PTWMB::class.qualifiedName))
        }
        val arg = Closeable {}
        mh.obj(arg)
        assertSame(arg, mh[PTWMB::class].arg)
    }

    private fun <Y> ptwmb(arg: Y) where Y : Closeable, Y : Serializable = arg.toString()
    @Test
    fun `factory parameter type with multiple bounds`() {
        val ptwmb: Function1<CloseableAndSerializable, String> = this::ptwmb
        val kFunction = uncheckedCast<Any, KFunction<String>>(ptwmb)
        mh.factory(kFunction)
        mh.obj(object : Serializable {})
        catchThrowable { mh[String::class] }.run {
            assertSame(NoSuchProviderException::class.java, javaClass)
            assertEquals(kFunction.parameters[0].toString(), message)
            assertThat(message, containsString(" #0 "))
            assertThat(message, endsWith(ptwmb.toString()))
        }
        val arg = Closeable {}
        mh.obj(arg)
        assertEquals(arg.toString(), mh[String::class])
    }

    private fun <Y> upt(a: Y) = a.toString()
    @Test
    fun `unbounded parameter type`() {
        val upt: Function1<Any, String> = this::upt
        val kFunction: KFunction<String> = uncheckedCast(upt)
        mh.factory(kFunction)
        // The only provider for Any is the factory, which is busy:
        catchThrowable { mh[String::class] }.run {
            assertSame(CircularDependencyException::class.java, javaClass)
            assertThat(message, containsString("'$upt'"))
            assertThat(message, endsWith(listOf(upt).toString()))
        }
        mh.obj(Any())
        // This time the factory isn't attempted:
        catchThrowable { mh[String::class] }.run {
            assertSame(TooManyProvidersException::class.java, javaClass)
            assertEquals(kFunction.parameters[0].toString(), message)
            assertThat(message, containsString(" #0 "))
            assertThat(message, endsWith(upt.toString()))
        }
    }

    open class NoPublicConstructor protected constructor()

    @Test
    fun `no public constructor`() {
        catchThrowable { mh.impl(NoPublicConstructor::class) }.run {
            assertSame(NoPublicConstructorsException::class.java, javaClass)
            assertEquals(NoPublicConstructor::class.toString(), message)
        }
        catchThrowable { mh.impl(NoPublicConstructor::class.java) }.run {
            assertSame(NoPublicConstructorsException::class.java, javaClass)
            assertEquals(NoPublicConstructor::class.toString(), message)
        }
    }

    private fun primitiveInt() = 1
    class IntConsumer(@Suppress("UNUSED_PARAMETER") i: Int)
    class IntegerConsumer(@Suppress("UNUSED_PARAMETER") i: Int?)

    @Test
    fun `boxed satisfies primitive`() {
        mh.obj(1)
        mh.impl(IntConsumer::class)
        mh[IntConsumer::class]
    }

    @Test
    fun `primitive satisfies boxed`() {
        mh.factory(this::primitiveInt)
        mh.impl(IntegerConsumer::class.java)
        mh[IntegerConsumer::class]
    }

    // The primary constructor takes two distinct providers:
    class TakesTwoThings(@Suppress("UNUSED_PARAMETER") first: String, @Suppress("UNUSED_PARAMETER") second: Int) {
        // This constructor takes one repeated provider but we count it both times so greediness is 2:
        @Suppress("unused")
        constructor(first: Int, second: Int) : this(first.toString(), second)

        // This constructor would be greediest but is not satisfiable:
        @Suppress("unused")
        constructor(first: Int, second: String, @Suppress("UNUSED_PARAMETER") third: Config) : this(second, first)
    }

    @Test
    fun `equally greedy constructors kotlin`() {
        mh.obj("str")
        mh.obj(123)
        mh.impl(TakesTwoThings::class)
        catchThrowable { mh[TakesTwoThings::class] }.run {
            assertSame(NoUniqueGreediestSatisfiableConstructorException::class.java, javaClass)
            val expected = TakesTwoThings::class.constructors.filter { it.parameters.size == 2 }
            assertEquals(2, expected.size)
            assertThat(message, endsWith(expected.toString()))
        }
    }

    @Test
    fun `equally greedy constructors java`() {
        mh.obj("str")
        mh.obj(123)
        mh.impl(TakesTwoThings::class.java)
        catchThrowable { mh[TakesTwoThings::class] }.run {
            assertSame(NoUniqueGreediestSatisfiableConstructorException::class.java, javaClass)
            val expected = TakesTwoThings::class.java.constructors.filter { it.parameters.size == 2 }
            assertEquals(2, expected.size)
            assertEquals(expected.toString(), message)
        }
    }

    private fun nrt(): String? = fail("Should not be invoked.")
    @Test
    fun `nullable return type is banned`() {
        catchThrowable { mh.factory(this::nrt) }.run {
            assertSame(NullableReturnTypeException::class.java, javaClass)
            assertThat(message, endsWith(this@HerniaImplTest::nrt.toString()))
        }
    }

    @Test
    fun unsatisfiableArrayParam() {
        class Impl(@Suppress("UNUSED_PARAMETER") v: Array<String>)
        mh.impl(Impl::class)
        catchThrowable { mh[Impl::class] }.run {
            assertSame(UnsatisfiableArrayException::class.java, javaClass)
            assertEquals(Impl::class.constructors.single().parameters[0].toString(), message)
        }
        // Arrays are only special in real params, you should use getAll to get all the Strings:
        catchThrowable { mh[Array<String>::class] }.run {
            assertSame(NoSuchProviderException::class.java, javaClass)
            assertEquals(Array<String>::class.java.toString(), message)
        }
        assertEquals(emptyList(), mh.getAll(String::class))
    }

    @Test
    fun arrayParam1() {
        class Impl(val v: Array<String>)
        mh.impl(Impl::class)
        mh.obj("a")
        assertArrayEquals(arrayOf("a"), mh[Impl::class].v)
    }

    @Test
    fun arrayParam2() {
        class Impl(val v: Array<String>)
        mh.impl(Impl::class)
        mh.obj("y")
        mh.obj("x")
        assertArrayEquals(arrayOf("y", "x"), mh[Impl::class].v)
    }

    @Test
    fun nullableArrayParam() {
        class Impl(val v: Array<String>?)
        mh.impl(Impl::class)
        assertEquals(null, mh[Impl::class].v)
    }

    @Test
    fun arraysAreNotCached() {
        class B(val v: Array<String>)
        class A(val v: Array<String>, val b: B)
        class C(val v: Array<String>)
        class D(val v: Array<String>)
        mh.obj("x")
        mh.obj("y")
        mh.impl(A::class)
        mh.impl(B::class)
        val a = mh[A::class]
        a.run {
            assertArrayEquals(arrayOf("x", "y"), v)
            assertArrayEquals(arrayOf("x", "y"), b.v)
            assertNotSame(v, b.v)
        }
        assertSame(mh[B::class].v, a.b.v) // Because it's the same (cached) instance of B.
        mh.impl(C::class)
        mh[C::class].run {
            assertArrayEquals(arrayOf("x", "y"), v)
            assertNotSame(v, a.v)
            assertNotSame(v, a.b.v)
        }
        mh.obj("z")
        mh.impl(D::class)
        mh[D::class].run {
            assertArrayEquals(arrayOf("x", "y", "z"), v)
        }
    }

    class C1(@Suppress("UNUSED_PARAMETER") c2: C2)
    class C2(@Suppress("UNUSED_PARAMETER") c3: String)

    private fun c3(@Suppress("UNUSED_PARAMETER") c2: C2): String {
        fail("Should not be called.")
    }

    @Test
    fun `circularity error kotlin`() {
        mh.impl(C1::class)
        mh.impl(C2::class)
        mh.factory(this::c3)
        catchThrowable { mh[C1::class] }.run {
            assertSame(CircularDependencyException::class.java, javaClass)
            assertThat(message, containsString("'${C2::class}'"))
            assertThat(message, endsWith(listOf(C1::class.constructors.single(), C2::class.constructors.single(), this@HerniaImplTest::c3).toString()))
        }
    }

    @Test
    fun `circularity error java`() {
        mh.impl(C1::class.java)
        mh.impl(C2::class.java)
        mh.factory(this::c3)
        catchThrowable { mh[C1::class] }.run {
            assertSame(CircularDependencyException::class.java, javaClass)
            assertThat(message, containsString("'${C2::class}'"))
            assertThat(message, endsWith(listOf(C1::class.constructors.single().javaConstructor, C2::class.constructors.single().javaConstructor, this@HerniaImplTest::c3).toString()))
        }
    }

    @Test
    fun `ancestor hernia providers are visible`() {
        val c = Config("over here")
        mh.obj(c)
        mh.child().also {
            it.impl(AImpl::class)
            assertSame(c, it[AImpl::class].config)
        }
        mh.child().child().also {
            it.impl(AImpl::class)
            assertSame(c, it[AImpl::class].config)
        }
    }

    @Test
    fun `descendant hernia providers are not visible`() {
        val child = mh.child()
        child.obj(Config("over here"))
        mh.impl(AImpl::class)
        catchThrowable { mh[AImpl::class] }.run {
            assertSame(NoSuchProviderException::class.java, javaClass)
            assertEquals(AImpl::class.constructors.single().parameters.single().toString(), message)
        }
        // Fails even though we go via the child, as the cached AImpl in mh shouldn't have collaborators from descendant hernias:
        catchThrowable { child[AImpl::class] }.run {
            assertSame(NoSuchProviderException::class.java, javaClass)
            assertEquals(AImpl::class.constructors.single().parameters.single().toString(), message)
        }
    }

    class AllConfigs(val configs: Array<Config>)

    @Test
    fun `nearest ancestor with at least one provider wins`() {
        mh.obj(Config("deep"))
        mh.child().also {
            it.child().also {
                it.impl(AllConfigs::class)
                assertEquals(listOf("deep"), it[AllConfigs::class].configs.map { it.info })
            }
            it.obj(Config("shallow1"))
            it.obj(Config("shallow2"))
            it.child().also {
                it.impl(AllConfigs::class)
                assertEquals(listOf("shallow1", "shallow2"), it[AllConfigs::class].configs.map { it.info })
            }
            it.child().also {
                it.obj(Config("local"))
                it.impl(AllConfigs::class)
                assertEquals(listOf("local"), it[AllConfigs::class].configs.map { it.info })
            }
        }
    }

    @Test
    fun `abstract type`() {
        catchThrowable { mh.impl(Runnable::class) }.run {
            assertSame(AbstractTypeException::class.java, javaClass)
            assertEquals(Runnable::class.toString(), message)
        }
        catchThrowable { mh.impl(Runnable::class.java) }.run {
            assertSame(AbstractTypeException::class.java, javaClass)
            assertEquals(Runnable::class.java.toString(), message)
        }
    }

    private interface Service
    open class GoodService : Service
    abstract class BadService1 : Service
    class BadService2 private constructor() : Service

    private fun badService3(): Service? = fail("Should not be called.")
    @Test
    fun `existing providers not removed if new type is bad`() {
        mh.impl(GoodService::class)
        catchThrowable { mh.impl(Service::class, BadService1::class) }.run {
            assertSame(AbstractTypeException::class.java, javaClass)
            assertEquals(BadService1::class.toString(), message)
        }
        catchThrowable { mh.impl(Service::class, BadService2::class) }.run {
            assertSame(NoPublicConstructorsException::class.java, javaClass)
            assertEquals(BadService2::class.toString(), message)
        }
        catchThrowable { mh.impl(Service::class, BadService2::class.java) }.run {
            assertSame(NoPublicConstructorsException::class.java, javaClass)
            assertEquals(BadService2::class.toString(), message)
        }
        // Type system won't let you pass in badService3, but I still want validation up-front:
        catchThrowable { mh.factory(Service::class, uncheckedCast(this::badService3)) }.run {
            assertSame(NullableReturnTypeException::class.java, javaClass)
            assertEquals(this@HerniaImplTest::badService3.toString(), message)
        }
        assertSame(GoodService::class.java, mh[Service::class].javaClass)
    }

    class GoodService2 : GoodService()

    @Test
    fun `service providers are removed completely`() {
        mh.impl(GoodService::class)
        assertSame(GoodService::class.java, mh[Service::class].javaClass)
        mh.impl(GoodService::class, GoodService2::class)
        // In particular, GoodService is no longer registered against Service (or Any):
        assertSame(GoodService2::class.java, mh[Service::class].javaClass)
        assertSame(GoodService2::class.java, mh[Any::class].javaClass)
    }

    class JParamExample(@Suppress("UNUSED_PARAMETER") str: String, @Suppress("UNUSED_PARAMETER") num: Int)

    @Test
    fun `JParam has useful toString`() {
        val c = JParamExample::class.java.constructors.single()
        // Parameter doesn't expose its index, here we deliberately pass in the wrong one to see what happens:
        val text = JParam(c.parameters[0], 1, IOException::class.java).toString()
        assertThat(text, containsString(" #1 "))
        assertThat(text, anyOf(containsString(" str "), containsString(" arg0 ")))
        assertThat(text, endsWith(c.toString()))
    }

    private val sideEffects = mutableListOf<Int>()
    private fun sideEffect1() {
        sideEffects.add(1)
    }

    private fun sideEffect2() {
        sideEffects.add(2)
    }

    @Test
    fun `side-effects are idempotent as a consequence of caching of results`() {
        mh.factory(this::sideEffect1)
        assertEquals(listOf(Unit), mh.getAll(Unit::class))
        assertEquals(listOf(1), sideEffects)
        mh.factory(this::sideEffect2)
        assertEquals(listOf(Unit, Unit), mh.getAll(Unit::class)) // Get both results.
        assertEquals(listOf(1, 2), sideEffects) // sideEffect1 didn't run again.
    }

    @Test
    fun `getAll returns empty list when there is nothing to return`() {
        // This is in contrast to the exception thrown by an array param, which would not be useful to replicate here:
        assertEquals(emptyList(), mh.getAll(IOException::class))
    }

    // Two params needed to make primary constructor the winner when both are satisfiable.
    // It's probably true that the secondary will always trigger a CircularDependencyException, but Hernia isn't clever enough to tell.
    class InvocationSwitcher(@Suppress("UNUSED_PARAMETER") s: String, @Suppress("UNUSED_PARAMETER") t: String) {
        @Suppress("unused")
        constructor(same: InvocationSwitcher) : this(same.toString(), same.toString())
    }

    @Test
    fun `chosen constructor is not set in stone`() {
        mh.impl(InvocationSwitcher::class)
        assertSame(CircularDependencyException::class.java, catchThrowable { mh[InvocationSwitcher::class] }.javaClass)
        mh.obj("alt")
        mh[InvocationSwitcher::class] // Succeeds via other constructor.
    }

    class GreedinessUnits(@Suppress("UNUSED_PARAMETER") v: Array<String>, @Suppress("UNUSED_PARAMETER") z: Int) {
        // Two greediness units even though it's one provider repeated:
        @Suppress("unused")
        constructor(z1: Int, z2: Int) : this(emptyArray(), z1 + z2)
    }

    @Test
    fun `array param counts as one greediness unit`() {
        mh.obj("x")
        mh.obj("y")
        mh.obj(100)
        mh.impl(GreedinessUnits::class)
        assertSame(NoUniqueGreediestSatisfiableConstructorException::class.java, catchThrowable { mh[GreedinessUnits::class] }.javaClass)
    }

    interface TriangleBase
    interface TriangleSide : TriangleBase
    class TriangleImpl : TriangleBase, TriangleSide

    @Test
    fun `provider registered exactly once against each supertype`() {
        mh.impl(TriangleImpl::class)
        mh[TriangleBase::class] // Don't throw TooManyProvidersException.
    }

    interface Service1
    interface Service2
    class ServiceImpl1 : Service1, Service2
    class ServiceImpl2 : Service2

    @Test
    fun `do not leak empty provider list`() {
        mh.impl(ServiceImpl1::class)
        mh.impl(Service2::class, ServiceImpl2::class)
        assertSame(NoSuchProviderException::class.java, catchThrowable { mh[Service1::class] }.javaClass)
    }

    class Global
    class Session(val global: Global, val local: Int)

    @Test
    fun `child can be used to create a scope`() {
        mh.impl(Global::class)
        mh.factory(mh.child().also {
            it.obj(1)
            it.impl(Session::class)
        }, Session::class)
        mh.factory(mh.child().also {
            it.obj(2)
            it.impl(Session::class)
        }, Session::class)
        val sessions = mh.getAll(Session::class)
        val g = mh[Global::class]
        sessions.forEach { assertSame(g, it.global) }
        assertEquals(listOf(1, 2), sessions.map { it.local })
    }
}
