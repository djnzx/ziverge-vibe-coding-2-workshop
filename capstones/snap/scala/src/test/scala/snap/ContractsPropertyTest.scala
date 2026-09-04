package snap

import java.nio.charset.StandardCharsets
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

class ContractsPropertyTest extends munit.ScalaCheckSuite:

  /** `Arbitrary[String]` essentially never emits a supplementary character or one from U+E000..U+FFFF, which is exactly where UTF-16 order and UTF-8 byte order
    * disagree — a generator built on it lets a `compareTo` ordering pass. Draw from a pool that straddles every UTF-8 length boundary instead.
    */
  private val pathText: Gen[String] =
    val codePoints = Gen.oneOf(
      Gen.choose(0x20, 0x7e),       // 1 byte
      Gen.choose(0x80, 0x7ff),      // 2 bytes
      Gen.choose(0x800, 0xd7ff),    // 3 bytes
      Gen.choose(0xe000, 0xffff),   // 3 bytes, above the surrogate range
      Gen.choose(0x10000, 0x10ffff) // 4 bytes
    )
    Gen.listOf(codePoints).map(points => points.map(Character.toChars(_).mkString).mkString)

  property("file bytes are equal exactly when their contents are"):
    forAll: (left: Array[Byte], right: Array[Byte]) =>
      (FileBytes(left) == FileBytes(right)) == left.sameElements(right)

  property("equal file bytes agree on hash code"):
    forAll: (bytes: Array[Byte]) =>
      FileBytes(bytes).hashCode == FileBytes(bytes.clone()).hashCode

  property("tracked path order agrees with comparing UTF-8 bytes"):
    Prop.forAll(pathText, pathText): (left, right) =>
      val ordering = summon[Ordering[TrackedPath]]
      val expected = java.util.Arrays.compareUnsigned(
        left.getBytes(StandardCharsets.UTF_8),
        right.getBytes(StandardCharsets.UTF_8)
      )
      ordering.compare(TrackedPath(left), TrackedPath(right)).sign == expected.sign

  property("tracked path order is antisymmetric"):
    Prop.forAll(pathText, pathText): (left, right) =>
      val ordering = summon[Ordering[TrackedPath]]
      ordering.compare(TrackedPath(left), TrackedPath(right)).sign ==
        -ordering.compare(TrackedPath(right), TrackedPath(left)).sign

  property("tracked path order is transitive"):
    Prop.forAll(pathText, pathText, pathText): (a, b, c) =>
      val ordering = summon[Ordering[TrackedPath]]
      val sorted   = Vector(a, b, c).map(TrackedPath.apply).sorted(using ordering)
      Prop.all(
        ordering.lteq(sorted(0), sorted(1)),
        ordering.lteq(sorted(1), sorted(2)),
        ordering.lteq(sorted(0), sorted(2))
      )
