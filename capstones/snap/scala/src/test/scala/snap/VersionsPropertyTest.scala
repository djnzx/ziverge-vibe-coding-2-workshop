package snap

import org.scalacheck.{Gen, Prop}
import scala.collection.immutable.SortedMap

/** SPEC §3 properties.
  *
  * Three axes, per `.claude/skills/property-testing-parsers`: round-trip, rejection, and totality. Rejection is the one that matters here — a parser that
  * accepted everything would satisfy every round-trip property below, and §3.2 is mostly a list of things to refuse.
  */
class VersionsPropertyTest extends munit.ScalaCheckSuite:

  // ---- generators ----------------------------------------------------------

  /** §3.1 confines ids to ASCII with no whitespace, control character, `,`, `(`, `)`, or `->`. `>` is excluded from the pool rather than filtered, so a
    * generated id can never accidentally spell `->`.
    */
  private val idChar: Gen[Char] =
    Gen.oneOf(('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9') ++ Seq('.', '_', '+', '-'))

  private val idPart: Gen[String] =
    Gen.choose(1, 8).flatMap(n => Gen.listOfN(n, idChar)).map(_.mkString)

  private val contributorId: Gen[ContributorId] =
    for
      local  <- idPart
      domain <- idPart
    yield ContributorId(s"$local@$domain")

  /** Biased toward small revisions, with the §3.1 boundary drawn often enough to matter.
    */
  private val revision: Gen[Long] =
    Gen.frequency(
      8 -> Gen.choose(1L, 1000L),
      1 -> Gen.choose(1L, Limits.maxSafeInteger),
      1 -> Gen.const(Limits.maxSafeInteger)
    )

  /** The generator pattern the skill calls for: emit the canonical **source text** alongside the value it must parse to, so round-trip properties do not depend
    * on `render` — the function under test.
    */
  private final case class VersionCase(text: String, value: Version)

  private val versionCase: Gen[VersionCase] =
    for
      count <- Gen.choose(0, 5)
      ids   <- Gen.listOfN(count, contributorId).map(_.distinctBy(_.value))
      revs  <- Gen.listOfN(ids.length, revision)
    yield
      val components = SortedMap.from(ids.zip(revs))
      val body       = components.toVector.map((id, rev) => s"${id.value}->$rev").mkString(",")
      VersionCase(s"($body)", Version(components))

  private val version: Gen[Version] = versionCase.map(_.value)

  /** Fuzz built from the grammar's own alphabet. `Arbitrary[String]` essentially never produces an unbalanced paren, a `->`, or an oversized numeric literal —
    * exactly the shapes that break this parser.
    */
  private val fragment: Gen[String] =
    Gen.oneOf(
      "(",
      ")",
      ",",
      "->",
      "-",
      ">",
      "@",
      "a",
      "x",
      "0",
      "1",
      "01",
      " ",
      "\t",
      "9007199254740991",
      "9007199254740992",
      "99999999999999999999999999",
      "a@x",
      "->1"
    )

  private val fuzz: Gen[String] =
    Gen.choose(0, 12).flatMap(n => Gen.listOfN(n, fragment)).map(_.mkString)

  // ---- round-trip ----------------------------------------------------------

  property("canonical text parses to the version it spells"):
    Prop.forAll(versionCase): generated =>
      Versions.parse(generated.text) == Right(generated.value)

  property("render produces exactly the canonical text"):
    Prop.forAll(versionCase): generated =>
      Versions.render(generated.value) == generated.text

  property("parse and render are inverse on valid versions"):
    Prop.forAll(version): value =>
      Versions.parse(Versions.render(value)) == Right(value)

  property("render is a fixpoint through parse"):
    Prop.forAll(versionCase): generated =>
      Versions.parse(generated.text).map(Versions.render) == Right(generated.text)

  // ---- rejection -----------------------------------------------------------

  /** Each shape §3.2 names, one property apiece. A parser that accepted everything would pass every round-trip property above and fail all of these.
    */

  property("an explicit zero component is rejected"):
    Prop.forAll(contributorId): id =>
      Versions.parse(s"(${id.value}->0)").isLeft

  property("a leading zero is rejected"):
    Prop.forAll(contributorId, Gen.choose(1L, 1000L)): (id, rev) =>
      Versions.parse(s"(${id.value}->0$rev)").isLeft

  property("a revision past the maximum safe integer is rejected, never wrapped"):
    Prop.forAll(contributorId, Gen.choose(Limits.maxSafeInteger + 1, Long.MaxValue)): (id, rev) =>
      Versions.parse(s"(${id.value}->$rev)").isLeft

  property("a revision far past Long range is rejected without throwing"):
    // maxSafeInteger has 16 digits, so 17 is the first length that always
    // overflows; Long itself runs out at 19, and BigInt must absorb the rest.
    Prop.forAll(contributorId, Gen.choose(17, 40)): (id, digits) =>
      Versions.parse(s"(${id.value}->${"9" * digits})").isLeft

  property("a duplicated contributor is rejected"):
    Prop.forAll(contributorId, revision, revision): (id, first, second) =>
      Versions.parse(s"(${id.value}->$first,${id.value}->$second)").isLeft

  property("noncanonical contributor ordering is rejected"):
    Prop.forAll(versionCase.suchThat(_.value.components.size >= 2)): generated =>
      val reversed = generated.value.components.toVector.reverse
      val body     = reversed.map((id, rev) => s"${id.value}->$rev").mkString(",")
      Versions.parse(s"($body)").isLeft

  property("injected whitespace is rejected"):
    val spaced =
      for
        generated <- versionCase.suchThat(_.text.length > 2)
        at        <- Gen.choose(0, generated.text.length)
        space     <- Gen.oneOf(" ", "\t", "\n")
      yield generated.text.take(at) + space + generated.text.drop(at)
    Prop.forAll(spaced)(text => Versions.parse(text).isLeft)

  property("dropping either parenthesis is rejected"):
    Prop.forAll(versionCase): generated =>
      Versions.parse(generated.text.drop(1)).isLeft &&
        Versions.parse(generated.text.dropRight(1)).isLeft

  property("trailing content after the closing paren is rejected"):
    Prop.forAll(versionCase, Gen.nonEmptyListOf(idChar)): (generated, extra) =>
      Versions.parse(generated.text + extra.mkString).isLeft

  property("a component with no arrow is rejected"):
    Prop.forAll(contributorId, revision): (id, rev) =>
      Versions.parse(s"(${id.value}$rev)").isLeft

  property("a component with no revision is rejected"):
    Prop.forAll(contributorId): id =>
      Versions.parse(s"(${id.value}->)").isLeft

  property("a signed or fractional revision is rejected"):
    Prop.forAll(contributorId, Gen.choose(1L, 1000L)): (id, rev) =>
      Versions.parse(s"(${id.value}->+$rev)").isLeft &&
        Versions.parse(s"(${id.value}->$rev.0)").isLeft

  property("an id that fails §3.1 is rejected inside a version too"):
    val badId = Gen.oneOf("noatsign", "two@@x", "a,b@x", "a(b)@x", "a->b@x", "@x", "a@")
    Prop.forAll(badId, revision): (id, rev) =>
      Versions.contributorId(id).isLeft && Versions.parse(s"($id->$rev)").isLeft

  property("an id over 254 bytes is rejected"):
    Prop.forAll(Gen.choose(255, 400)): size =>
      Versions.contributorId("a" * (size - 2) + "@x").isLeft

  // ---- error contract ------------------------------------------------------

  property("every version rejection reports the documented message shape"):
    val invalid = Gen.oneOf(
      fuzz,
      Gen.const(""),
      Gen.const("("),
      Gen.const(")"),
      Gen.const("(a@x->0)"),
      Gen.const("(b@x->1,a@x->1)"),
      Gen.const("(a@x->1, b@x->1)")
    )
    Prop.forAll(invalid): text =>
      Versions.parse(text) match
        case Right(_)    => Prop.passed
        case Left(error) => Prop(error.detail.matches("invalid version: .+"))

  property("every id rejection reports the documented message shape"):
    Prop.forAll(Gen.asciiStr): text =>
      Versions.contributorId(text) match
        case Right(_)    => Prop.passed
        case Left(error) => Prop(error.detail.matches("invalid contributor id: .+"))

  // ---- totality ------------------------------------------------------------

  property("parse returns a value for grammar-fragment fuzz, never throws"):
    Prop.forAll(fuzz)(text => Versions.parse(text).isLeft || Versions.parse(text).isRight)

  property("parse returns a value for arbitrary input, never throws"):
    Prop.forAll: (text: String) =>
      Versions.parse(text).isLeft || Versions.parse(text).isRight

  property("contributorId returns a value for arbitrary input, never throws"):
    Prop.forAll: (text: String) =>
      Versions.contributorId(text).isLeft || Versions.contributorId(text).isRight

  // ---- §3.3 algebra --------------------------------------------------------

  property("exactly one causality outcome holds for any pair"):
    Prop.forAll(version, version): (left, right) =>
      val outcome = Versions.compare(left, right)
      val equal   = left == right
      val before  = left != right && left.components.keySet
        .union(right.components.keySet)
        .forall(id => left.apply(id) <= right.apply(id))
      val after = left != right && left.components.keySet
        .union(right.components.keySet)
        .forall(id => left.apply(id) >= right.apply(id))
      outcome match
        case Causality.Equal      => equal
        case Causality.Before     => before
        case Causality.After      => after
        case Causality.Concurrent => !equal && !before && !after

  property("comparison is symmetric under swapping"):
    Prop.forAll(version, version): (left, right) =>
      (Versions.compare(left, right), Versions.compare(right, left)) match
        case (Causality.Equal, Causality.Equal)           => true
        case (Causality.Before, Causality.After)          => true
        case (Causality.After, Causality.Before)          => true
        case (Causality.Concurrent, Causality.Concurrent) => true
        case _                                            => false

  property("join is idempotent"):
    Prop.forAll(version)(value => Versions.join(value, value) == value)

  property("join is commutative"):
    Prop.forAll(version, version): (left, right) =>
      Versions.join(left, right) == Versions.join(right, left)

  property("join is associative"):
    Prop.forAll(version, version, version): (a, b, c) =>
      Versions.join(Versions.join(a, b), c) == Versions.join(a, Versions.join(b, c))

  property("join is an upper bound of both operands"):
    Prop.forAll(version, version): (left, right) =>
      val joined = Versions.join(left, right)
      Set(Causality.Before, Causality.Equal).contains(Versions.compare(left, joined)) &&
      Set(Causality.Before, Causality.Equal).contains(Versions.compare(right, joined))

  property("join is the least upper bound"):
    Prop.forAll(version, version, version): (left, right, candidate) =>
      val dominatesBoth =
        Set(Causality.Before, Causality.Equal).contains(Versions.compare(left, candidate)) &&
          Set(Causality.Before, Causality.Equal).contains(Versions.compare(right, candidate))
      Prop(!dominatesBoth) || Prop(
        Set(Causality.Before, Causality.Equal)
          .contains(Versions.compare(Versions.join(left, right), candidate))
      )

  property("a joined version has no zero component, so it stays canonical"):
    Prop.forAll(version, version): (left, right) =>
      Versions.join(left, right).components.values.forall(_ > 0L)

  // ---- §3.4 Snap order -----------------------------------------------------

  property("Snap order is antisymmetric"):
    Prop.forAll(version, version): (left, right) =>
      Versions.snapOrder.compare(left, right).sign == -Versions.snapOrder.compare(right, left).sign

  property("Snap order is transitive"):
    Prop.forAll(version, version, version): (a, b, c) =>
      val sorted = Vector(a, b, c).sorted(using Versions.snapOrder)
      Versions.snapOrder.lteq(sorted(0), sorted(1)) &&
      Versions.snapOrder.lteq(sorted(1), sorted(2)) &&
      Versions.snapOrder.lteq(sorted(0), sorted(2))

  property("Snap order agrees with equality"):
    Prop.forAll(version, version): (left, right) =>
      (Versions.snapOrder.compare(left, right) == 0) == (left == right)

  property("Snap order extends causal order"):
    Prop.forAll(version, version): (left, right) =>
      Versions.compare(left, right) match
        case Causality.Before     => Versions.snapOrder.lt(left, right)
        case Causality.After      => Versions.snapOrder.gt(left, right)
        case Causality.Equal      => Versions.snapOrder.compare(left, right) == 0
        case Causality.Concurrent => true

  property("Snap order totally orders distinct concurrent versions"):
    Prop.forAll(version, version): (left, right) =>
      Prop(Versions.compare(left, right) != Causality.Concurrent) ||
        Prop(Versions.snapOrder.compare(left, right) != 0)

  property("Snap order matches §3.4 read literally off the sorted union"):
    // An oracle: it builds the union as an explicitly sorted sequence and
    // compares counters positionally. The algebraic properties above hold for
    // any deterministic total order, so this is the only one that pins *which*
    // order §3.4 asks for.
    Prop.forAll(version, version): (left, right) =>
      val union = (left.components.keySet ++ right.components.keySet).toVector
        .sortBy(_.value)
      val expected = union
        .map(id => java.lang.Long.compare(left.apply(id), right.apply(id)))
        .find(_ != 0)
        .getOrElse(0)
      Versions.snapOrder.compare(left, right).sign == expected.sign
