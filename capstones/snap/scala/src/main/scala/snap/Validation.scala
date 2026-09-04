package snap

import scala.annotation.tailrec

/** SPEC §4.5 — the five cross-patch validation passes that come after RepositoryJson's schema pass (§4.5 pass 1), plus the §3.5 corruption check and the §4.1
  * `known` query. Everything here is pure: no filesystem, no environment, no clock.
  *
  * Passes 2–4 are pure graph shape and need no tree materialization. Passes 5–6 need to know what a version's tree actually looks like, which in general
  * requires §6's full replay — concurrency resolution, namespace conflicts, and OT — so both call `Replay.materialize`, and the §4.3 authored-result rules
  * (what one patch makes of its own exact base) live in `Replay` too. The dependency runs one way on purpose: `Validation` calls `Replay`, never the reverse.
  */
object Validation:

  private def invalid(detail: String): SnapError = SnapError(detail)

  private def renderDot(dot: Dot): String = s"${dot.author.value}->${dot.revision}"

  /** SPEC §4.5 passes 2–6, in order. Assumes `repo` already passed RepositoryJson's schema pass (pass 1). */
  def validate(repo: Repository): Either[SnapError, Unit] =
    for
      _ <- checkPatchOrder(repo.patches)
      _ <- checkBaseClosure(repo.patches)
      _ <- checkAcyclic(repo.patches)
      _ <- checkChangesAgainstBase(repo.patches)
      _ <- checkFrontierClosure(repo)
    yield ()

  // ---- pass 2: patch sorting, one value per dot ---------------------------------

  private given dotOrdering: Ordering[Dot] =
    Ordering.by[Dot, ContributorId](_.author).orElseBy(_.revision)

  /** SPEC §4.1/§4.5 pass 2 — `patches` sorted by author then numeric revision, with no duplicate dot. Contiguity (item 2's other half) is not checked
    * separately: [[checkBaseClosure]]'s `revision = base[author] + 1` plus its base-existence check together force it by induction down to revision 1, so a
    * standalone contiguity scan would only ever fire where that check already does.
    */
  private def checkPatchOrder(patches: Vector[Patch]): Either[SnapError, Unit] =
    val dots            = patches.map(_.dot)
    val strictlyOrdered = dots.indices.drop(1).forall(i => dotOrdering.lt(dots(i - 1), dots(i)))
    if strictlyOrdered then Right(())
    else Left(invalid("patches is not canonical: must be sorted by author then revision, with no duplicate dot"))

  // ---- pass 3: base closure and the revision formula -----------------------------

  /** SPEC §4.2/§4.5 pass 3 — `revision = base[author] + 1`, and every base component with a positive revision must name a patch actually present in this
    * repository.
    */
  private def checkBaseClosure(patches: Vector[Patch]): Either[SnapError, Unit] =
    val present = patches.map(_.dot).toSet
    patches.foldLeft[Either[SnapError, Unit]](Right(())): (acc, patch) =>
      acc.flatMap: _ =>
        val expected = patch.base(patch.author) + 1
        if patch.revision != expected then Left(invalid(s"${renderDot(patch.dot)} does not follow its own base: expected revision $expected"))
        else
          patch.base.components.collectFirst { case (author, rev) if rev > 0 && !present.contains(Dot(author, rev)) => author } match
            case Some(author) => Left(invalid(s"base closure is missing ${author.value}"))
            case None         => Right(())

  // ---- pass 4: acyclic causality --------------------------------------------------

  /** SPEC §4.5 pass 4 — a Kahn's-algorithm ready-set walk over direct base-dot dependencies (already known present by [[checkBaseClosure]]). If a round finds
    * nothing ready while patches remain, the causal graph has a cycle.
    */
  private def checkAcyclic(patches: Vector[Patch]): Either[SnapError, Unit] =
    val dependenciesOf: Map[Dot, Set[Dot]] = patches.map { patch =>
      patch.dot -> patch.base.components.collect { case (author, rev) if rev > 0 => Dot(author, rev) }.toSet
    }.toMap

    @tailrec
    def loop(remaining: Set[Dot], resolved: Set[Dot]): Either[SnapError, Unit] =
      if remaining.isEmpty then Right(())
      else
        val ready = remaining.filter(dot => dependenciesOf(dot).subsetOf(resolved))
        if ready.isEmpty then Left(invalid("cyclic or incomplete patch history"))
        else loop(remaining -- ready, resolved ++ ready)

    loop(patches.map(_.dot).toSet, Set.empty)

  // ---- passes 5 and 6 materialize through Replay ---------------------------------

  /** SPEC §6 — the tree a version denotes, discarding replay's warnings: validation asks only whether the history *can* be materialized, never how the
    * concurrency in it resolved.
    */
  private def materialize(patches: Vector[Patch], target: Version): Either[SnapError, Tree] =
    Replay.materialize(patches, target).map((tree, _) => tree)

  // ---- pass 5: every change against its materialized exact base ------------------

  private def checkChangesAgainstBase(patches: Vector[Patch]): Either[SnapError, Unit] =
    patches.foldLeft[Either[SnapError, Unit]](Right(())): (acc, patch) =>
      acc.flatMap: _ =>
        materialize(patches, patch.base).flatMap(base => Replay.authoredTree(base, patch)).map(_ => ())

  // ---- pass 6: deterministic replay of the declared frontier ---------------------

  /** SPEC §4.1/§4.5 pass 6 — `patches` is exactly the causal closure of `frontier` (no `unreachable patch`), the frontier is itself materializable (every base
    * component a selected patch needs is also within the frontier), and replaying it succeeds.
    */
  private def checkFrontierClosure(repo: Repository): Either[SnapError, Unit] =
    val unreachable = repo.patches.find(p => p.revision > repo.frontier(p.author))
    unreachable match
      case Some(patch) => Left(invalid(s"unreachable patch: ${renderDot(patch.dot)}"))
      case None        =>
        val outOfFrontier = repo.patches.iterator
          .flatMap(_.base.components.iterator)
          .exists((author, rev) => rev > repo.frontier(author))
        if outOfFrontier then Left(invalid(s"unknown version: ${Versions.render(repo.frontier)}"))
        else materialize(repo.patches, repo.frontier).map(_ => ())

  // ---- SPEC §3.5 — cross-repository dot collision ---------------------------------

  /** SPEC §3.5 — the same dot carrying structurally different patch values is corruption, checked before any write. `Patch`'s case-class equality already
    * compares the parsed typed value, so whitespace and JSON key order never cause a false collision.
    */
  def dotCollision(local: Vector[Patch], incoming: Vector[Patch]): Either[SnapError, Unit] =
    val byDot = local.map(p => p.dot -> p).toMap
    incoming.foldLeft[Either[SnapError, Unit]](Right(())): (acc, patch) =>
      acc.flatMap: _ =>
        byDot.get(patch.dot) match
          case Some(existing) if existing != patch =>
            Left(invalid(s"patch collision: ${patch.author.value} revision ${patch.revision}"))
          case _ => Right(())

  // ---- SPEC §4.1 — the `known` (materializable) query -----------------------------

  /** SPEC §4.1 — `v` is known in `repo` when every patch `(c, n)` it selects (`n <= v[c]`) exists, and the selected set contains the complete base of every
    * selected patch. `v` need not equal the frontier or any one patch's result.
    */
  def known(repo: Repository, v: Version): Boolean =
    val present             = repo.patches.map(_.dot).toSet
    val selected            = repo.patches.filter(p => p.revision <= v(p.author))
    val everySelectedExists = v.components.forall((author, rev) => (1L to rev).forall(n => present.contains(Dot(author, n))))
    val basesComplete       = selected.forall: p =>
      p.base.components.forall((author, rev) => rev == 0 || selected.exists(s => s.dot == Dot(author, rev)))
    everySelectedExists && basesComplete
