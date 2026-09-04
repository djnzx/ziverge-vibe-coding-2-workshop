package snap

import scala.collection.immutable.{SortedMap, SortedSet}

/** SPEC §3 — contributor IDs, canonical version syntax, causal comparison, join, and Snap order.
  *
  * Everything here is pure: no filesystem, no environment, no clock.
  */
object Versions:

  /** Renders untrusted text for an error message.
    *
    * §10 says an error is **one line** (`snap: <detail>`), and the text quoted here is whatever the user typed — a contributor id or version argument that may
    * hold a newline, a tab, or any other control character. Echoing it raw would split one error across two lines and break both §10 and the acceptance suite's
    * `^snap: … .+\n$` matchers, so control characters are escaped in §7.4's order: backslash first, then tab and LF, then anything else control by code point.
    * An empty input still has to say something.
    */
  private def describe(text: String): String =
    if text.isEmpty then "(empty)"
    else
      text.flatMap:
        case '\\'                   => "\\\\"
        case '\t'                   => "\\t"
        case '\n'                   => "\\n"
        case char if char.isControl => f"\\u${char.toInt}%04x"
        case char                   => char.toString

  private def invalidId(text: String): SnapError =
    SnapError(s"invalid contributor id: ${describe(text)}")

  private def invalidVersion(text: String): SnapError =
    SnapError(s"invalid version: ${describe(text)}")

  // ---- §3.1 contributor IDs ------------------------------------------------

  /** SPEC §3.1. An ASCII, email-shaped string: exactly one `@` with nonempty text on both sides, no control character, whitespace, `,`, `(`, `)`, or the
    * substring `->`, and at most 254 bytes. Spelling is preserved exactly — Snap performs no normalization.
    */
  def contributorId(text: String): Either[SnapError, ContributorId] =
    if isValidId(text) then Right(ContributorId(text)) else Left(invalidId(text))

  private def isValidId(text: String): Boolean =
    // §3.1 says ASCII, so the byte length equals the character count and a
    // char-wise scan is a byte-wise scan.
    val at = text.indexOf('@')
    text.nonEmpty
    && text.length <= Limits.maxContributorIdBytes
    && at > 0
    && at < text.length - 1
    && text.indexOf('@', at + 1) < 0
    && !text.contains("->")
    && text.forall(isAllowedIdChar)

  private def isAllowedIdChar(char: Char): Boolean =
    // Printable ASCII only. That single range already excludes every control
    // character and every form of whitespace, so only the three punctuation
    // exclusions §3.1 names need spelling out.
    char >= 0x21 && char <= 0x7e && char != ',' && char != '(' && char != ')'

  // ---- §3.2 canonical syntax -----------------------------------------------

  /** SPEC §3.2. Parses the CLI form `(a@x->1,b@x->2)`, with `()` for the empty version. Duplicate IDs, explicit zeroes, leading zeroes, overflow, invalid IDs,
    * whitespace, and noncanonical ordering are all errors.
    */
  def parse(text: String): Either[SnapError, Version] =
    if !text.startsWith("(") || !text.endsWith(")") || text.length < 2 then Left(invalidVersion(text))
    else
      val body = text.substring(1, text.length - 1)
      if body.isEmpty then Right(Version.empty)
      else
        // `split` with a negative limit keeps trailing empty fields, so
        // "(a@x->1,)" yields an empty component and is rejected below rather
        // than silently dropped.
        val components = body.split(",", -1).toVector
        components
          .foldLeft[Either[SnapError, Vector[(ContributorId, Long)]]](Right(Vector.empty)): (accumulated, component) =>
            accumulated.flatMap(parsed => parseComponent(component).map(parsed :+ _))
          .flatMap(parsed => assembleCanonical(parsed, text))
          .left
          .map(_ => invalidVersion(text))

  private def parseComponent(component: String): Either[SnapError, (ContributorId, Long)] =
    val arrow = component.indexOf("->")
    if arrow < 0 then Left(invalidVersion(component))
    else
      for
        id       <- contributorId(component.substring(0, arrow))
        revision <- parseRevision(component.substring(arrow + 2))
      yield (id, revision)

  /** SPEC §3.1/§3.2 — a positive integer with no sign, no leading zero, and no value above `Limits.maxSafeInteger`.
    *
    * Widened through `BigInt` on purpose: `toLong` on a 30-digit literal throws, and a wrap would silently admit a version the spec forbids.
    */
  private def parseRevision(text: String): Either[SnapError, Long] =
    if text.isEmpty || !text.forall(_.isDigit) then Left(invalidVersion(text))
    else if text.length > 1 && text.startsWith("0") then Left(invalidVersion(text))
    else
      val magnitude = BigInt(text)
      if magnitude <= 0 || magnitude > BigInt(Limits.maxSafeInteger) then Left(invalidVersion(text))
      else Right(magnitude.toLong)

  /** Rejects duplicate contributors and noncanonical ordering. Both are checked against the *authored* sequence, not the sorted map, because a `SortedMap`
    * would silently repair either one.
    */
  private def assembleCanonical(
    parsed: Vector[(ContributorId, Long)],
    text: String
  ): Either[SnapError, Version] =
    val ids       = parsed.map((id, _) => id)
    val canonical = ids.sorted(using summon[Ordering[ContributorId]])
    if ids.distinct.length != ids.length then Left(invalidVersion(text))
    else if ids != canonical then Left(invalidVersion(text))
    else Right(Version(SortedMap.from(parsed)))

  /** SPEC §3.2 — the canonical spelling: contributors sorted by unsigned UTF-8 bytes, no spaces, `()` when empty.
    */
  def render(version: Version): String =
    version.components.toVector
      .map((id, revision) => s"${id.value}->$revision")
      .mkString("(", ",", ")")

  // ---- §3.3 causal comparison and join -------------------------------------

  /** The union of both versions' contributors, **in canonical order**.
    *
    * The `SortedSet` return type is load-bearing, not decoration: §3.4 reads counters off the *sorted* union, and this is where that sortedness comes from.
    * `SortedMap.keySet` is a `TreeSet` and `union` preserves it, so declaring `Set` here would still work today by accident and break silently the day someone
    * rebuilds this from a `HashSet`.
    */
  private def contributors(left: Version, right: Version): SortedSet[ContributorId] =
    left.components.keySet.union(right.components.keySet)

  /** SPEC §3.3. An absent component is zero. All four outcomes are distinct: concurrency is never reported as before or after.
    */
  def compare(left: Version, right: Version): Causality =
    val ids              = contributors(left, right)
    val everyLeftAtMost  = ids.forall(id => left.apply(id) <= right.apply(id))
    val everyLeftAtLeast = ids.forall(id => left.apply(id) >= right.apply(id))
    if everyLeftAtMost && everyLeftAtLeast then Causality.Equal
    else if everyLeftAtMost then Causality.Before
    else if everyLeftAtLeast then Causality.After
    else Causality.Concurrent

  /** SPEC §3.3 — `join(V, W)[c] = max(V[c], W[c])`. */
  def join(left: Version, right: Version): Version =
    Version(SortedMap.from(contributors(left, right).map { id =>
      id -> math.max(left.apply(id), right.apply(id))
    }))

  // ---- §3.4 Snap order -----------------------------------------------------

  /** SPEC §3.4 — an arbitrary total order used only to sequence concurrent patches during replay.
    *
    * Take the sorted union of contributor IDs and compare the counter at each; the first unequal counter decides. It extends causal order, but its ordering of
    * concurrent versions carries no chronological or authorship meaning — never present it to a user as history.
    */
  given snapOrder: Ordering[Version] with
    def compare(left: Version, right: Version): Int =
      val ids = contributors(left, right).toVector
      ids
        .map(id => java.lang.Long.compare(left.apply(id), right.apply(id)))
        .find(_ != 0)
        .getOrElse(0)

  // §3.2's repository-JSON spelling of a version — an ordered array of
  // `[id, revision]` pairs — belongs to `RepositoryJson` in Phase 6, and is
  // deliberately not anticipated here.
