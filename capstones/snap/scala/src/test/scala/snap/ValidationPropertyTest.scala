package snap

import org.scalacheck.{Gen, Prop}
import scala.collection.immutable.SortedMap

/** SPEC §4.5 properties. The generator builds a **causally simple** repository on purpose: each contributor's patches form their own linear `put` chain against
  * a path unique to that contributor, so no two contributors ever touch the same path and every base is either empty or the author's own immediate predecessor.
  * That is deliberately short of Phase 7's "valid causal patch graph" generator (arbitrary concurrent forks and merges) — this phase's own
  * [[Validation.materialize]] is a placeholder that cannot resolve a real namespace or content conflict, so a generator that could produce one would not be
  * testing this phase's code, it would be testing Phase 7's. What it *does* let a property say, honestly: for any such repository, deleting a patch from the
  * middle of a chain, renumbering a revision, appending an unreachable patch, or introducing a cycle is always rejected.
  */
class ValidationPropertyTest extends munit.ScalaCheckSuite:

  private val idChar: Gen[Char]                 = Gen.oneOf(('a' to 'z') ++ ('0' to '9'))
  private val idPart: Gen[String]               = Gen.choose(1, 5).flatMap(n => Gen.listOfN(n, idChar)).map(_.mkString)
  private val contributorId: Gen[ContributorId] =
    for
      local  <- idPart
      domain <- idPart
    yield ContributorId(s"$local@$domain")

  private def chainOf(author: ContributorId, length: Int): Vector[Patch] =
    (1 to length).toVector.map: revision =>
      val base = if revision == 1 then Version.empty else Version(SortedMap(author -> (revision - 1).toLong))
      Patch(
        author,
        revision.toLong,
        base,
        s"revision $revision",
        Vector(Change.Put(TrackedPath(s"${author.value.replace('@', '-')}-$revision"), FileBytes.utf8(s"content $revision")))
      )

  /** A repository built from 1–3 independent contributors, each with a linear chain of length 1–4, frontier set to exactly each chain's last revision. By
    * construction this always validates: `checkPatchOrder` holds because chains are emitted author-major, `checkBaseClosure`/`checkAcyclic` hold because each
    * patch's only dependency is its own immediate predecessor, `checkChangesAgainstBase` holds because every path is unique to its author, and
    * `checkFrontierClosure` holds because the frontier is exactly each chain's own length.
    */
  private final case class ValidRepo(chains: Vector[Vector[Patch]]):
    def repository: Repository =
      // §4.1 requires patches sorted by author then revision; chains are built one author-major block at a time, but the *blocks* themselves must still be
      // emitted in contributor order, which the order `authors` happened to generate in does not guarantee.
      val patches  = chains.flatten.sortBy(p => (p.author.value, p.revision))
      val frontier = Version(SortedMap.from(chains.collect { case chain if chain.nonEmpty => chain.last.author -> chain.last.revision }))
      Repository(Repository.format, frontier, patches)

  private val validRepo: Gen[ValidRepo] =
    for
      authors <- Gen.choose(1, 3).flatMap(n => Gen.listOfN(n, contributorId)).map(_.distinctBy(_.value))
      lengths <- Gen.listOfN(authors.length, Gen.choose(1, 4))
    yield ValidRepo(authors.zip(lengths).map((author, len) => chainOf(author, len)).toVector)

  private val nonTrivialValidRepo: Gen[ValidRepo] =
    validRepo.suchThat(_.chains.exists(_.length >= 2))

  // ---- sanity: the generator itself produces valid repositories ------------------

  property("every generated repository validates"):
    Prop.forAll(validRepo): vr =>
      Validation.validate(vr.repository) == Right(())

  // ---- rejection -------------------------------------------------------------------

  property("deleting a non-last patch from a chain is always rejected"):
    Prop.forAll(nonTrivialValidRepo): vr =>
      val chainIndex = vr.chains.indexWhere(_.length >= 2)
      val chain      = vr.chains(chainIndex)
      // Drop the second-to-last revision, keeping the last: the last patch's base then names a dot that no longer exists.
      val mutatedChains = vr.chains.updated(chainIndex, chain.take(chain.length - 2) ++ chain.takeRight(1))
      val mutated       = vr.copy(chains = mutatedChains).repository
      Validation.validate(mutated).isLeft

  property("renumbering a patch's revision without updating its base is always rejected"):
    Prop.forAll(nonTrivialValidRepo): vr =>
      val chainIndex = vr.chains.indexWhere(_.length >= 2)
      val chain      = vr.chains(chainIndex)
      val renumbered = chain.updated(0, chain(0).copy(revision = chain(0).revision + 100))
      val mutated    = vr.copy(chains = vr.chains.updated(chainIndex, renumbered)).repository
      Validation.validate(mutated).isLeft

  property("a patch appended beyond the frontier is unreachable"):
    Prop.forAll(validRepo): vr =>
      // Build directly from `vr.repository`'s own frontier and patches rather than mutating `chains` and re-deriving: `ValidRepo.repository` computes the
      // frontier from each chain's *last* patch, so appending to a chain would move the frontier right along with it and never test this case at all.
      val original = vr.repository
      val chain    = vr.chains.head
      val author   = chain.headOption.map(_.author).getOrElse(ContributorId("solo@x"))
      val nextRev  = chain.length.toLong + 1
      val base     = if chain.isEmpty then Version.empty else Version(SortedMap(author -> chain.length.toLong))
      val extra    = Patch(author, nextRev, base, "extra", Vector(Change.Put(TrackedPath(s"extra-$nextRev"), FileBytes.utf8("x"))))
      val patches  = (original.patches :+ extra).sortBy(p => (p.author.value, p.revision))
      val mutated  = original.copy(patches = patches)
      Validation.validate(mutated).left.exists(_.detail.startsWith("unreachable patch:"))

  property("two contributors whose sole patches mutually depend on each other's revision form a cycle"):
    Prop.forAll(contributorId, contributorId): (a, bRaw) =>
      val b        = if bRaw == a then ContributorId(s"other-${a.value}") else bRaw
      val patchA   = Patch(a, 1, Version(SortedMap(b -> 1L)), "cycle a", Vector(Change.Put(TrackedPath("a-file"), FileBytes.utf8("a"))))
      val patchB   = Patch(b, 1, Version(SortedMap(a -> 1L)), "cycle b", Vector(Change.Put(TrackedPath("b-file"), FileBytes.utf8("b"))))
      val frontier = Version(SortedMap(a -> 1L, b -> 1L))
      val repo     = Repository(Repository.format, frontier, Vector(patchA, patchB).sortBy(p => (p.author.value, p.revision)))
      Validation.validate(repo).left.exists(_.detail.contains("cyclic or incomplete patch history"))
