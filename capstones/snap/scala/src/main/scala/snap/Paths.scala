package snap

/** SPEC §2 — tracked-path validation, prefix-freedom, and the ancestor/descendant queries §6.2 needs.
  *
  * Everything here is pure: no filesystem, no environment, no clock.
  */
object Paths:

  /** Renders untrusted text for an error message, matching `Versions.describe`. */
  private def describe(text: String): String =
    if text.isEmpty then "(empty)"
    else
      text.flatMap:
        case '\\'                   => "\\\\"
        case '\t'                   => "\\t"
        case '\n'                   => "\\n"
        case char if char.isControl => f"\\u${char.toInt}%04x"
        case char                   => char.toString

  private def invalidPath(text: String): SnapError =
    SnapError(s"invalid path: ${describe(text)}")

  // ---- §2 tracked-path grammar ----------------------------------------------

  /** SPEC §2. A UTF-8 relative path with `/` separators: nonempty, no ASCII control character or backslash, no empty, `.`, or `..` segment, and no first
    * segment equal to `.snap`. No Unicode or case normalization.
    */
  def trackedPath(text: String): Either[SnapError, TrackedPath] =
    if isValidPath(text) then Right(TrackedPath(text)) else Left(invalidPath(text))

  private def isValidPath(text: String): Boolean =
    val segments = segmentsOf(text)
    text.nonEmpty
    && !text.contains('\\')
    && !text.exists(_.isControl)
    && segments.forall(segment => segment.nonEmpty && segment != "." && segment != "..")
    && segments.head != ".snap"

  private def segmentsOf(text: String): Vector[String] =
    text.split("/", -1).toVector

  // ---- prefix relations ------------------------------------------------------

  /** SPEC §2/§6.2 — segment-wise: `ancestor`'s segments are a proper prefix of `descendant`'s. `a/bc` is therefore not a descendant of `a/b`, unlike a plain
    * `startsWith` on the raw text.
    */
  def isPrefixOf(ancestor: TrackedPath, descendant: TrackedPath): Boolean =
    val ancestorSegments   = segmentsOf(ancestor.value)
    val descendantSegments = segmentsOf(descendant.value)
    ancestorSegments.length < descendantSegments.length
    && ancestorSegments == descendantSegments.take(ancestorSegments.length)

  /** SPEC §2 — a tracked tree is prefix-free by path segment: no path is a segment-wise ancestor of another.
    *
    * Checked pairwise rather than via sorted adjacency: `Ordering[TrackedPath]` sorts by raw UTF-8 bytes, and an unrelated path can sort between an ancestor
    * and its descendant (`a` < `a!` < `a/b`, since `!` is 0x21 and `/` is 0x2F), which would make an adjacent-pairs check miss the conflict.
    */
  def prefixFree(paths: Set[TrackedPath]): Either[SnapError, Unit] =
    val ordered  = paths.toVector
    val conflict = ordered.indices.iterator
      .flatMap(i => ordered.indices.iterator.filter(_ != i).map(j => (ordered(i), ordered(j))))
      .find((left, right) => isPrefixOf(left, right))
    conflict match
      case Some((ancestor, descendant)) =>
        Left(SnapError(s"${descendant.value} is not prefix-free with ${ancestor.value}"))
      case None => Right(())

  // ---- the ancestor/descendant queries §6.2 needs ----------------------------

  /** SPEC §6.2 — the one existing path in `tree` that is a segment-wise ancestor of `candidate`, if any.
    *
    * At most one can exist: `tree` is itself prefix-free, so two ancestors of `candidate` would be ancestors of each other.
    */
  def ancestor(candidate: TrackedPath, tree: Tree): Option[TrackedPath] =
    tree.keys.find(existing => isPrefixOf(existing, candidate))

  /** SPEC §6.2 — every existing path in `tree` that is a segment-wise descendant of `candidate`. */
  def descendants(candidate: TrackedPath, tree: Tree): Set[TrackedPath] =
    tree.keys.filter(existing => isPrefixOf(candidate, existing)).toSet
