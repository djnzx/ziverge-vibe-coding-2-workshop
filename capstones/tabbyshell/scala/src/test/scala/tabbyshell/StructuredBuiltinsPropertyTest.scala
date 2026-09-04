package tabbyshell

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Algebraic checks for the transform contracts in SPEC 5.7–5.10. */
class StructuredBuiltinsPropertyTest extends munit.ScalaCheckSuite:

  private val columns = Vector("id", "rank", "tag")

  private val rankedRows: Gen[Vector[(Long, Vector[Value])]] =
    Gen
      .choose(0, 40)
      .flatMap: size =>
        Gen
          .listOfN(size, Gen.choose(-1000L, 1000L))
          .map: ranks =>
            ranks.zipWithIndex
              .map: (rank, index) =>
                rank -> Vector(Value.Int(index), Value.Int(rank), Value.Str(s"tag-$index"))
              .toVector

  private val tableRows: Gen[Vector[Vector[Value]]] = rankedRows.map(_.map(_._2))

  private def table(rows: Vector[Vector[Value]]): Value.Table = Value.Table(columns, rows)

  property("select preserves the row count and its requested column order"):
    forAll(tableRows): rows =>
      Builtins.select(table(rows), Vector("tag", "id")) ==
        Right(Value.Table(Vector("tag", "id"), rows.map(row => Vector(row(2), row(0)))))

  property("sort-by is a stable ascending permutation"):
    forAll(rankedRows): ranked =>
      val rows     = ranked.map(_._2)
      val expected = ranked
        .groupBy(_._1)
        .toVector
        .sortBy(_._1)
        .flatMap(_._2.map(_._2))
      Builtins.sortBy(table(rows), "rank", reverse = false) == Right(Value.Table(columns, expected))

  property("first and last counts are bounded and preserve list order"):
    forAll(tableRows, Gen.choose(0L, 60L)): (rows, count) =>
      val items = rows.map(_(1))
      Builtins.first(Value.List(items), Some(count)) == Right(Value.List(items.take(count.toInt))) &&
      Builtins.last(Value.List(items), Some(count)) == Right(Value.List(items.takeRight(count.toInt)))
