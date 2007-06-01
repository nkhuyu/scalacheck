package scalacheck


// Generation //////////////////////////////////////////////////////////////////

trait RandomGenerator {
  def choose(inclusiveRange: (Int,Int)): Int
}

object StdRand extends RandomGenerator {
  private val r = new java.util.Random
  def choose(range: (Int,Int)) = range match {
    case (low,high) => r.nextInt(low + high + 1) + low
  }
}

/** Record that encapsulates all parameters required for data generation */
case class GenPrms(size: Int, rand: RandomGenerator) {
  def resize(newSize: Int) = GenPrms(newSize,rand)
}

/** Dummy type that represents types that supports the arbitrary function.
 *  This could have been done more like a Haskell type class, with arbitrary as
 *  a member of Arbitrary, but placing the arbitrary function in the Gen object
 *  and adding implicit functions that converts an Arbitrary[T] to a Gen[T],
 *  helps Scala's type inference.
 *  To make your own "instance" of the Arbitrary class for a type U, define an
 *  implicit function that takes a value of type Arbitrary[U] and returns a
 *  value of type Gen[U]. The Arbitrary[U] value has no meaning in itself,
 *  its just there to make it a usable implicit function.
 */
sealed class Arbitrary[T] {}

/** Class that represents a generator. You shouldn't (and couldn't) make
 *  instances or subclasses of this class directly. To create custom
 *  generators, the combinators in the Gen object should be used.
 */
abstract sealed class Gen[+T](g: GenPrms => Option[T]) {

  def apply(prms: GenPrms) = g(prms)

  def map[U](f: T => U): Gen[U] = Gen.mkGen(prms => for {
    t <- this(prms)
  } yield f(t))

  def flatMap[U](f: T => Gen[U]): Gen[U] = Gen.mkGen(prms => for {
    t <- this(prms)
    u <- f(t)(prms)
  } yield u)

  def filter(p: T => Boolean): Gen[T] = Gen.mkGen(prms => for {
    t <- this(prms)
    u <- if (p(t)) Some(t) else None
  } yield u)

  def suchThat(p: T => Boolean): Gen[T] = filter(p)

}

/** Contains combinators for building generators, and has implicit functions
 *  for generating arbitrary values of common types.
 */
object Gen {

  // Internal support functions

  private def mkGen[T](g: GenPrms => Option[T]): Gen[T] = new Gen(g) {}

  private def consGen[T](gt: Gen[T], gts: Gen[List[T]]): Gen[List[T]] = for {
    t  <- gt
    ts <- gts
  } yield t::ts


  // Generator combinators

  /** Generates an arbitrary value of type T. It should be used as Gen[T],
   *  so there must exist an implicit function that can convert Arbitrary[T]
   *  into Gen[T].
   */
  def arbitrary[T]: Arbitrary[T] = new Arbitrary[T]

  /** A generator that always generates a given value */
  def value[T](x: T) = mkGen(p => Some(x))

  /** A generator that never generates a value */
  def fail[T]: Gen[T] = mkGen(p => None)

  /** A generator that generates a random integer in the given (inclusive)
   *  range.
   */
  def choose(inclusiveRange: (Int,Int)) =
    parameterized(prms => value(prms.rand.choose(inclusiveRange)))

  /** Creates a generator that can access its generation parameters
   */
  def parameterized[T](f: GenPrms => Gen[T]): Gen[T] =
    mkGen(prms => f(prms)(prms))

  /** Creates a generator that can access its generation size
   */
  def sized[T](f: Int => Gen[T]) = parameterized(prms => f(prms.size))

  /** Creates a resized version of a generator
   */
  def resize[T](s: Int, g: Gen[T]) = mkGen(prms => g(prms.resize(s)))

  /** A generator that returns a random element from a list
   */
  def elements[T](xs: Seq[T]) = for {
    i <- choose((0,xs.length-1))
  } yield xs(i)

  /** Picks a random generator from a list
   */
  def oneOf[T](gs: Seq[Gen[T]]) = for {
    i <- choose((0,gs.length-1))
    x <- gs(i)
  } yield x

  /** Generates a list of random length. The maximum length depends on the
   *  size parameter
   */
  def listOf[T](g: Gen[T]) = arbitraryList(null)(a => g)

  /** Generates a non-empty list of random length. The maximum length depends
   *  on the size parameter
   */
  def listOf1[T](g: Gen[T]) = for {
    x  <- g
    xs <- arbitraryList(null)(a => g)
  } yield x::xs

  /** Generates a list of the given length
   */
  def vectorOf[T](n: Int, g: Gen[T]): Gen[List[T]] =
    List.make(n,g).foldRight(emptyList[T])(consGen _)

  /** Generates an empty list of any type
   */
  def emptyList[T]: Gen[List[T]] = value(Nil)


  // Implicit generators for common types

  /** Generates an arbitrary integer */
  implicit def arbitraryInt(x: Arbitrary[Int]) = sized (s => choose((0,s)))

  /** Generates a list of arbitrary elements. The maximum length of the list
   *  depends on the size parameter.
   */
  implicit def arbitraryList[T](x: Arbitrary[List[T]])
    (implicit f: Arbitrary[T] => Gen[T]): Gen[List[T]] =
  {
    sized(s => List.make(s,f(arbitrary)).foldRight(emptyList[T])(consGen _))
  }

}


// Properties //////////////////////////////////////////////////////////////////

object Prop {

  import Gen.{arbitrary, value, fail}

  type Prop = Gen[PropRes]

  /** A result from a single test */
  case class PropRes(ok: Boolean, args: List[String])


  // Private support functions

  private def consPropRes(r: PropRes, as: Any*) =
    PropRes(r.ok, as.map(_.toString).toList ::: r.args)


  // Property combinators

  def rejected = fail

  def ==> (b: Boolean, p: Prop): Prop = if (b) p else rejected

  def forAll[T](g: Gen[T])(f: T => Prop): Prop = for {
    t <- g
    r <- f(t)
  } yield r


  // Convenience functions

  implicit def extBoolean(b: Boolean) = new ExtBoolean(b)
  class ExtBoolean(b: Boolean) {
    def ==>(t: Prop) = Prop.==>(b,t)
  }


  // Implicit properties for common types

  implicit def propBoolean(b: Boolean) = value(PropRes(b,Nil))

  def property[A1]
    (f:  Function1[A1,Prop])(implicit
     g1: Arbitrary[A1] => Gen[A1]) = for
  {
    a1 <- g1(arbitrary)
    r  <- f(a1)
  } yield consPropRes(r, a1)

  def property[A1,A2]
    (f:  Function2[A1,A2,Prop])(implicit
     g1: Arbitrary[A1] => Gen[A1],
     g2: Arbitrary[A2] => Gen[A2]) = for
  {
    a1 <- g1(arbitrary)
    a2 <- g2(arbitrary)
    r  <- f(a1,a2)
  } yield consPropRes(r, a1, a2)

  def property[A1,A2,A3]
    (f:  Function3[A1,A2,A3,Prop])(implicit
     g1: Arbitrary[A1] => Gen[A1],
     g2: Arbitrary[A2] => Gen[A2],
     g3: Arbitrary[A3] => Gen[A3]) = for
  {
    a1 <- g1(arbitrary)
    a2 <- g2(arbitrary)
    a3 <- g3(arbitrary)
    r  <- f(a1,a2,a3)
  } yield consPropRes(r, a1, a2, a3)

  def property[A1,A2,A3,A4]
    (f:  Function4[A1,A2,A3,A4,Prop])(implicit
     g1: Arbitrary[A1] => Gen[A1],
     g2: Arbitrary[A2] => Gen[A2],
     g3: Arbitrary[A2] => Gen[A3],
     g4: Arbitrary[A3] => Gen[A4]) = for
  {
    a1 <- g1(arbitrary)
    a2 <- g2(arbitrary)
    a3 <- g3(arbitrary)
    a4 <- g4(arbitrary)
    r  <- f(a1,a2,a3,a4)
  } yield consPropRes(r, a1, a2, a3, a4)

}

// Testing /////////////////////////////////////////////////////////////////////


/** Test parameters */
case class TestPrms(minSuccessfulTests: Int, maxDiscardedTests: Int,
  maxSize: Int)

/** Test statistics */
case class TestStats(result: TestResult, succeeded: Int, discarded: Int)

abstract sealed class TestResult
case class TestPassed extends TestResult
case class TestFailed(failure: Prop.PropRes) extends TestResult
case class TestExhausted extends TestResult

object Test {

  import Prop.{Prop, PropRes}

  // Testing functions

  val defaultTestPrms = TestPrms(100,50000,100)

  type TestInspector = (Option[PropRes],Int,Int) => Unit

  /** Tests a property with the given testing parameters, and returns
   *  the test results.
   */
  def check(p: TestPrms, t: Prop): TestStats = check(p,t, (r,s,d) => ())

  /** Tests a property with the given testing parameters, and returns
   *  the test results. <code>f</code> is a function which is called each
   *  time the property is evaluted.
   */
  def check(prms: TestPrms, t: Prop, f: TestInspector): TestStats =
  {
    var discarded = 0
    var succeeded = 0
    var failure: PropRes = null

    while((failure == null) &&
          discarded < prms.maxDiscardedTests &&
          succeeded < prms.minSuccessfulTests)
    {
      val size = (succeeded * prms.maxSize) / prms.minSuccessfulTests + discarded / 10
      val res = t(GenPrms(size, StdRand))
      res match {
        case Some(r) => if(r.ok) succeeded = succeeded + 1 else failure = r
        case None => discarded = discarded + 1
      }
      f(res,succeeded,discarded)
    }

    val res = if(failure != null) TestFailed(failure)
              else if(succeeded >= prms.minSuccessfulTests) TestPassed
              else TestExhausted

    TestStats(res, succeeded, discarded)
  }

  /** Tests a property and prints results to the console
   */
  def check(t: Prop): TestStats =
  {
    def printTmp(res: Option[PropRes], succeeded: Int, discarded: Int) = {
      if(discarded > 0)
        Console.printf("\rPassed {0} tests; {1} discarded",succeeded,discarded)
      else Console.printf("\rPassed {0} tests",succeeded)
      Console.flush
    }

    val tr = check(defaultTestPrms,t,printTmp)

    tr.result match {
      case TestFailed(failure) =>
        Console.printf("\r*** Failed, after {0} tests:\n", tr.succeeded)
        Console.println(failure.args)
      case TestExhausted() =>
        Console.printf(
          "\r*** Gave up, after only {1} passed tests. {0} tests were discarded.\n",
          tr.discarded, tr.succeeded)
      case TestPassed() =>
        Console.printf("\r+++ OK, passed {0} tests.\n", tr.succeeded)
    }

    tr
  }

}


// Usage ///////////////////////////////////////////////////////////////////////

object TestIt extends Application {

  import Gen._
  import Prop._
  import Test._

  val n = arbitrary[Int]

  val l = arbitrary[List[Int]]

  val x = for {
    x <- elements(List(1,6,8)) suchThat (_ < 6)
    y <- arbitrary[Int]
  } yield (x, y)

  val prms = GenPrms(100, StdRand)


  val pf1 = (n:Int) => (n == 0) ==> true

  check(property(pf1))

}