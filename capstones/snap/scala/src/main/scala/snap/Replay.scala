package snap

import scala.annotation.tailrec
import scala.collection.immutable.SortedSet
import scala.collection.mutable

/** SPEC §6.1/§6.2/§6.4 — materialize a version from the empty tree, resolving concurrency deterministically and collecting warnings.
  *
  * Everything here is pure: no filesystem, no environment, no clock. The result depends only on the *set* of patches and the target version, never on the order
  * they arrive in — `materialize` takes a `Vector` rather than a `Set` so `ReplayPropertyTest` can state that as a property it can actually falsify.
  *
  * This module also owns the §4.3 authored-result rules ([[authored]], [[authoredTree]]), because both replay and `Validation`'s pass 5 need them and the
  * dependency has to run one way: `Validation` calls `Replay`, never the reverse.
  */
object Replay:

  private def invalid(detail: String): SnapError = SnapError(detail)

  // ---- §4.3 — the authored result of a change against its exact base tree -----------

  /** SPEC §4.3 — what one change makes of its path in `base`: `None` for a delete, `Some(bytes)` otherwise. A delete requires the path to be present, and a
    * change that alters neither existence nor bytes is invalid — except an empty text edit creating an empty file, which never equals an absent path and so
    * never trips the check.
    */
  def authored(base: Tree, change: Change): Either[SnapError, Option[FileBytes]] = change match
    case Change.Delete(path) =>
      if base.contains(path) then Right(None) else Left(invalid(s"delete of absent path: ${path.value}"))
    case Change.Put(path, content) =>
      if base.get(path).contains(content) then Left(invalid("no-op change")) else Right(Some(content))
    case Change.Text(path, edit) =>
      val current   = base.get(path)
      val oldTokens = current match
        case None                              => Right(Vector.empty[String])
        case Some(bytes) if Text.isText(bytes) => Right(Text.tokens(bytes))
        case Some(_)                           => Left(invalid(s"${path.value} is not text"))
      oldTokens
        .flatMap(old => Text.apply(edit, old))
        .flatMap: result =>
          val bytes = Text.untokens(result)
          if current.contains(bytes) then Left(invalid("no-op change")) else Right(Some(bytes))

  /** SPEC §4.3/§6.2 — the tree one patch authors from its exact base: every change resolved against `base` itself, never against a partially updated tree, then
    * applied together. A patch holds at most one change per path, so the two readings agree; stating the simultaneous one keeps it that way.
    *
    * Used both as replay's authored result `T` and as `Validation`'s pass-5 check that a patch is applicable to its own base at all.
    */
  def authoredTree(base: Tree, patch: Patch): Either[SnapError, Tree] =
    patch.changes
      .foldLeft[Either[SnapError, Tree]](Right(base)): (acc, change) =>
        acc.flatMap: tree =>
          authored(base, change).map:
            case None        => tree - change.targetPath
            case Some(bytes) => tree.updated(change.targetPath, bytes)
      .flatMap(requirePrefixFree)

  private def requirePrefixFree(tree: Tree): Either[SnapError, Tree] =
    Paths.prefixFree(tree.keySet) match
      case Left(_)  => Left(invalid("tree paths conflict"))
      case Right(_) => Right(tree)

  // ---- §6.1 — selection and the three-key ready ordering ----------------------------

  /** SPEC §6.1 — the least ready patch is chosen by Snap order of result versions, then unsigned UTF-8 author, then numeric revision. Valid histories normally
    * decide at the first key; the other two exist so the answer is total.
    */
  private val readyOrdering: Ordering[Patch] =
    Ordering
      .by[Patch, Version](_.result)(using Versions.snapOrder)
      .orElseBy(_.author)
      .orElseBy(_.revision)

  /** SPEC §6.1/§4.1 — every patch `(c, n)` with `n <= target[c]`, provided the selection is materializable: `target` names a patch that exists, and so does
    * every selected patch's base.
    *
    * Only each base's *own* dot is checked, not its whole chain: patch `(c, n)` declares `base[c] = n - 1` (§4.2), so a present `(c, n - 1)` is itself selected
    * and checked, and the induction runs down to revision 1.
    */
  private def select(all: Vector[Patch], target: Version): Either[SnapError, Vector[Patch]] =
    val selected = all.filter(patch => patch.revision <= target(patch.author))
    val dots     = selected.map(_.dot).toSet

    def present(version: Version): Boolean =
      version.components.forall((author, revision) => revision <= 0 || dots.contains(Dot(author, revision)))

    if present(target) && selected.forall(patch => present(patch.base)) then Right(selected)
    else Left(invalid(s"unknown version: ${Versions.render(target)}"))

  private def isReady(patch: Patch, integrated: Set[Dot]): Boolean =
    patch.base.components.forall((author, revision) => revision <= 0 || integrated.contains(Dot(author, revision)))

  /** Patches in the deterministic order in which they are integrated to reach `target`. Command rendering uses this same causal order as replay rather than
    * inventing a separate version sort.
    */
  def integrationOrder(all: Vector[Patch], target: Version): Either[SnapError, Vector[Patch]] =
    select(all, target).flatMap: selected =>
      @tailrec
      def loop(remaining: Vector[Patch], integrated: Set[Dot], result: Vector[Patch]): Either[SnapError, Vector[Patch]] =
        if remaining.isEmpty then Right(result)
        else
          remaining.filter(patch => isReady(patch, integrated)).minOption(using readyOrdering) match
            case None       => Left(invalid("cyclic or incomplete patch history"))
            case Some(next) => loop(remaining.filterNot(_.dot == next.dot), integrated + next.dot, result :+ next)

      loop(selected, Set.empty, Vector.empty)

  // ---- §6.2/§6.4 — integrating one patch --------------------------------------------

  private def authoredResults(base: Tree, patch: Patch): Either[SnapError, Vector[(Change, Option[FileBytes])]] =
    patch.changes.foldLeft[Either[SnapError, Vector[(Change, Option[FileBytes])]]](Right(Vector.empty)): (acc, change) =>
      for
        soFar <- acc
        value <- authored(base, change)
      yield soFar :+ (change, value)

  private def conflicting(path: TrackedPath, tree: Tree): Set[TrackedPath] =
    Paths.ancestor(path, tree).toSet ++ Paths.descendants(path, tree)

  /** SPEC §6.2 — one patch against the canonical tree so far.
    *
    * Namespace conflicts are resolved for the patch as a whole *first*, and those decisions override the per-path rules: `S` is every path the patch makes
    * present, `C'` is the canonical tree minus everything the patch deletes, and any path in `S` with a different ancestor or descendant in `C'` is installed
    * as its authored result while every path it collides with is removed, each removal emitting `namespace-wins`. Every remaining changed path is then
    * evaluated against the same `B` and `C` — never against a tree already updated by a sibling path — and all the resulting changes are applied together.
    */
  private def integrate(base: Tree, current: Tree, patch: Patch): Either[SnapError, (Tree, SortedSet[Warning])] =
    authoredResults(base, patch).flatMap: results =>
      val deleted    = results.collect { case (change, None) => change.targetPath }.toSet
      val pruned     = current -- deleted
      val collisions = results.collect { case (change, Some(_)) => change.targetPath -> conflicting(change.targetPath, pruned) }.toMap
      val installed  = collisions.collect { case (path, others) if others.nonEmpty => path }.toSet
      val removed    = collisions.values.flatten.toSet

      val start: Either[SnapError, (Tree, SortedSet[Warning])] =
        Right((current -- removed, SortedSet.from(removed.map(Warning(_, Reason.NamespaceWins)))))

      results
        .foldLeft(start): (acc, entry) =>
          acc.flatMap: (tree, warnings) =>
            val (change, value) = entry
            val path            = change.targetPath
            if installed.contains(path) then Right((value.fold(tree)(tree.updated(path, _)), warnings))
            else
              resolve(change, base.get(path), current.get(path), value).map: (resolved, reason) =>
                (resolved.fold(tree - path)(tree.updated(path, _)), warnings ++ reason.map(Warning(path, _)))
        .flatMap((tree, warnings) => requirePrefixFree(tree).map((_, warnings)))

  /** SPEC §6.2's four per-path cases, in order: identical in `B` and `C` applies the authored change directly; identical in `C` and `T` keeps `C`, which
    * collapses identical concurrent changes *before* OT rather than duplicating their effect; three-way text goes through §6.3; anything else falls to §6.4.
    */
  private def resolve(
    change: Change,
    base: Option[FileBytes],
    current: Option[FileBytes],
    target: Option[FileBytes]
  ): Either[SnapError, (Option[FileBytes], Option[Reason])] =
    if base == current then Right((target, None))
    else if current == target then Right((current, None))
    else
      lineMerge(change, base, current, target) match
        case Some(merged) => merged.map(bytes => (Some(bytes), None))
        case None         => Right(ladder(change, base, current, target))

  /** SPEC §6.2 case 3 / §6.3 — `Some` only when `B`, `C`, and `T` are all text and the incoming change is textual. The context edit is the *aggregate*
    * `diff(B, C)`, derived once; §6.3 is explicit that the transform is not repeated per historical patch. Line OT emits no warning.
    */
  private def lineMerge(
    change: Change,
    base: Option[FileBytes],
    current: Option[FileBytes],
    target: Option[FileBytes]
  ): Option[Either[SnapError, FileBytes]] =
    change match
      case Change.Text(_, edit) =>
        (base, current, target) match
          case (Some(b), Some(c), Some(t)) if Text.isText(b) && Text.isText(c) && Text.isText(t) =>
            val context = Text.tokens(c)
            Some(Text.apply(Ot.transform(edit, Diff.diff(Text.tokens(b), context)), context).map(Text.untokens))
          case _ => None
      case _ => None

  /** SPEC §6.4's six path-level rules, in the order the spec states them. Each rule that discards a whole effect names the reason it discarded it. */
  private def ladder(
    change: Change,
    base: Option[FileBytes],
    current: Option[FileBytes],
    target: Option[FileBytes]
  ): (Option[FileBytes], Option[Reason]) =
    if current == target then (current, None)
    else if target.isEmpty then (None, Some(Reason.DeleteWins))
    else if base.isDefined && current.isEmpty then (None, Some(Reason.DeleteWins))
    else if base.isEmpty && current.isDefined then (target, Some(Reason.LaterCreateWins))
    else
      change match
        case Change.Put(_, _) => (target, Some(Reason.LaterPutWins))
        case _                => (current, Some(Reason.PutWins))

  // ---- §6.1/§6.5 — the replay itself -------------------------------------------------

  /** SPEC §6.1 — materialize `target` from the empty tree, returning the canonical tree and the unique warnings §6.4 collected, sorted by path then reason.
    *
    * Each patch is integrated against its own *exact base tree*, which is a replay in its own right; those nested replays are memoized by version, without
    * which a forked history costs exponential time. Their warnings are deliberately **not** collected: a base replay is a way to compute `B`, and the warnings
    * this replay reports are the ones its own integrations produced. Merge relies on that (§6.4: it prints the pairs a joined replay has that the pre-merge
    * local replay did not).
    */
  def materialize(all: Vector[Patch], target: Version): Either[SnapError, (Tree, SortedSet[Warning])] =
    val cache = mutable.Map.empty[Version, Either[SnapError, Tree]]

    def treeOf(version: Version): Either[SnapError, Tree] =
      cache.get(version) match
        case Some(cached) => cached
        case None         =>
          val computed = replay(version).map((tree, _) => tree)
          cache.update(version, computed)
          computed

    def replay(version: Version): Either[SnapError, (Tree, SortedSet[Warning])] =
      select(all, version).flatMap: selected =>
        @tailrec
        def loop(
          remaining: Vector[Patch],
          integrated: Set[Dot],
          tree: Tree,
          warnings: SortedSet[Warning]
        ): Either[SnapError, (Tree, SortedSet[Warning])] =
          if remaining.isEmpty then Right((tree, warnings))
          else
            remaining.filter(patch => isReady(patch, integrated)).minOption(using readyOrdering) match
              case None       => Left(invalid("cyclic or incomplete patch history"))
              case Some(next) =>
                treeOf(next.base).flatMap(base => integrate(base, tree, next)) match
                  case Left(error)                    => Left(error)
                  case Right((nextTree, newWarnings)) =>
                    loop(remaining.filterNot(_.dot == next.dot), integrated + next.dot, nextTree, warnings ++ newWarnings)

        loop(selected, Set.empty, Tree.empty, SortedSet.empty)

    replay(target)
