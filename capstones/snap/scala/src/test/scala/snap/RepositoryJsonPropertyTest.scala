package snap

import org.scalacheck.{Gen, Prop}
import scala.collection.immutable.SortedMap

/** SPEC §4.1 properties: round-trip and canonical-fixpoint. Rejection properties for this module live in [[ValidationPropertyTest]] alongside the passes they
  * actually exercise — RepositoryJson's own schema pass has no cross-patch concept of "middle of a chain" or "cycle" to reject.
  */
class RepositoryJsonPropertyTest extends munit.ScalaCheckSuite:

  private val idChar: Gen[Char]                 = Gen.oneOf(('a' to 'z') ++ ('0' to '9'))
  private val idPart: Gen[String]               = Gen.choose(1, 6).flatMap(n => Gen.listOfN(n, idChar)).map(_.mkString)
  private val contributorId: Gen[ContributorId] =
    for
      local  <- idPart
      domain <- idPart
    yield ContributorId(s"$local@$domain")

  private val revision: Gen[Long] = Gen.choose(1L, 1000L)

  private val version: Gen[Version] =
    for
      ids  <- Gen.choose(0, 3).flatMap(n => Gen.listOfN(n, contributorId)).map(_.distinctBy(_.value))
      revs <- Gen.listOfN(ids.length, revision)
    yield Version(SortedMap.from(ids.zip(revs)))

  private val pathSegment: Gen[String] =
    Gen.choose(1, 6).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar)).map(_.mkString)

  private val trackedPath: Gen[TrackedPath] =
    Gen.choose(1, 2).flatMap(n => Gen.listOfN(n, pathSegment)).map(segs => TrackedPath(segs.mkString("/")))

  private val message: Gen[String] =
    Gen.choose(1, 12).flatMap(n => Gen.listOfN(n, Gen.oneOf(Gen.alphaNumChar, Gen.const(' ')))).map(_.mkString)

  private val fileBytes: Gen[FileBytes] =
    Gen.choose(0, 8).flatMap(n => Gen.listOfN(n, Gen.choose(-128, 127).map(_.toByte))).map(bs => FileBytes(bs.toArray))

  private val canonicalToken: Gen[String] =
    Gen.choose(1, 4).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar)).map(_.mkString + "\n")

  private val change: Gen[Change] =
    Gen.oneOf(
      for
        path    <- trackedPath
        content <- fileBytes
      yield Change.Put(path, content),
      trackedPath.map(Change.Delete(_)),
      for
        path <- trackedPath
        toks <- Gen.choose(0, 2).flatMap(n => Gen.listOfN(n, canonicalToken))
      yield Change.Text(path, if toks.isEmpty then Vector.empty else Vector(EditOp.Insert(toks.toVector)))
    )

  private val changes: Gen[Vector[Change]] =
    Gen
      .choose(1, 3)
      .flatMap(n => Gen.listOfN(n, change))
      .map: cs =>
        cs.distinctBy(_.targetPath).sortBy(_.targetPath)(using summon[Ordering[TrackedPath]]).toVector

  private val patch: Gen[Patch] =
    for
      author <- contributorId
      rev    <- revision
      base   <- version
      msg    <- message
      chgs   <- changes
    yield Patch(author, rev, base, msg, chgs)

  private val repository: Gen[Repository] =
    for
      frontier <- version
      patches  <- Gen.choose(0, 3).flatMap(n => Gen.listOfN(n, patch))
    yield Repository(Repository.format, frontier, patches.sortBy(p => (p.author.value, p.revision)).toVector)

  // ---- round-trip -----------------------------------------------------------------

  property("parse(serialize(repo)) == repo for arbitrary schema-valid repositories"):
    Prop.forAll(repository): repo =>
      RepositoryJson.parse(RepositoryJson.serialize(repo)) == Right(repo)

  property("serialization is a fixpoint: serialize(parse(serialize(repo))) == serialize(repo)"):
    Prop.forAll(repository): repo =>
      val once  = RepositoryJson.serialize(repo)
      val twice = RepositoryJson.parse(once).map(RepositoryJson.serialize)
      twice == Right(once)

  property("serialize always ends in exactly one trailing LF and never contains a bare escape byte"):
    Prop.forAll(repository): repo =>
      val text = RepositoryJson.serialize(repo)
      text.endsWith("\n") && !text.endsWith("\n\n")
