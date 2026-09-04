package snap

import java.nio.charset.StandardCharsets
import java.util.Arrays
import scala.collection.immutable.SortedMap

// ---------------------------------------------------------------------------
// Snap's public domain model — see ../SPEC.md.
//
// Data and canonical orderings only. Parsing, validation, replay, and rendering
// belong in the modules listed in AGENTS.md; nothing here reads the filesystem,
// the clock, or the environment.
// ---------------------------------------------------------------------------

/** SPEC §3.1 — an ASCII, email-shaped contributor ID.
  *
  * This constructor performs no validation: build IDs through `Versions`, which enforces the single `@`, the forbidden characters, and the 254-byte limit.
  */
final case class ContributorId(value: String)

object ContributorId:
  /** SPEC §3.2 — contributors sort by unsigned UTF-8 bytes. §3.1 confines IDs to ASCII, where that coincides with `String` order.
    */
  given Ordering[ContributorId] = Ordering.by(_.value)

/** SPEC §3 — a vector clock: a finite map from contributor to latest revision.
  *
  * An absent component is zero and a stored component is always positive, so `()` is the empty map. Iteration order is canonical (§3.2) because the underlying
  * map is sorted.
  */
final case class Version(components: SortedMap[ContributorId, Long]):
  def apply(id: ContributorId): Long = components.getOrElse(id, 0L)

object Version:
  val empty: Version = Version(SortedMap.empty)

/** SPEC §3.3 — the four outcomes a version comparison must preserve.
  *
  * Concurrency is a distinct answer, never a stand-in for before or after.
  */
enum Causality:
  case Equal, Before, After, Concurrent

/** SPEC §4.2 — the `(contributor, revision)` pair a patch owns exactly one of. */
final case class Dot(author: ContributorId, revision: Long)

/** SPEC §2 — a tracked path: a UTF-8 relative path with `/` separators.
  *
  * This constructor performs no validation: build paths through `Paths`, which enforces nonemptiness, the forbidden characters, the segment rules, and the
  * `.snap` exclusion.
  */
final case class TrackedPath(value: String)

object TrackedPath:
  /** SPEC §2 — paths sort by unsigned lexicographic UTF-8 bytes.
    *
    * This is *not* `String` order: `compareTo` compares UTF-16 code units, which puts supplementary characters before U+E000..U+FFFF while their UTF-8 bytes
    * put them after. Never sort tracked paths as plain strings.
    */
  given Ordering[TrackedPath] with
    def compare(left: TrackedPath, right: TrackedPath): Int =
      Arrays.compareUnsigned(
        left.value.getBytes(StandardCharsets.UTF_8),
        right.value.getBytes(StandardCharsets.UTF_8)
      )

/** The exact bytes of one tracked file.
  *
  * `Array[Byte]` compares by reference, and SPEC §2 defines a clean working tree as one whose path/byte map *equals* the current tree, so raw arrays cannot be
  * tree values. This wrapper copies on the way in and out and compares by content.
  */
final class FileBytes private (private val underlying: Array[Byte]):
  def length: Int          = underlying.length
  def toArray: Array[Byte] = underlying.clone()

  override def equals(other: Any): Boolean = other match
    case that: FileBytes => Arrays.equals(underlying, that.underlying)
    case _               => false

  override def hashCode: Int    = Arrays.hashCode(underlying)
  override def toString: String = s"FileBytes(${underlying.length} bytes)"

object FileBytes:
  val empty: FileBytes = new FileBytes(Array.emptyByteArray)

  def apply(bytes: Array[Byte]): FileBytes = new FileBytes(bytes.clone())

  def utf8(text: String): FileBytes = new FileBytes(text.getBytes(StandardCharsets.UTF_8))

/** A materialized file tree (SPEC §2): tracked path to exact bytes.
  *
  * Directories are implicit and empty directories are absent. Every tree Snap builds is prefix-free by segment — validated per patch and enforced during replay
  * by §6.4.
  */
type Tree = SortedMap[TrackedPath, FileBytes]

object Tree:
  val empty: Tree = SortedMap.empty

/** SPEC §4.4 — one operation of a text edit script.
  *
  * Counts are positive and bounded by [[Limits.maxSafeInteger]]. Adjacent operations of the same kind are forbidden, and the script must consume the complete
  * old token sequence: there is no implicit trailing retain.
  */
enum EditOp:
  case Retain(count: Long)
  case Delete(count: Long)
  case Insert(tokens: Vector[String])

/** SPEC §4.3 — the three change variants a patch may carry for one path. */
enum Change:
  case Text(path: TrackedPath, edit: Vector[EditOp])
  case Put(path: TrackedPath, content: FileBytes)
  case Delete(path: TrackedPath)

  /** Named `targetPath` rather than `path` so it does not shadow the field each case already exposes.
    */
  def targetPath: TrackedPath = this match
    case Text(path, _) => path
    case Put(path, _)  => path
    case Delete(path)  => path

/** SPEC §4.1/§4.2 — one patch: a dot, its exact causal base, and its changes.
  *
  * `changes` is nonempty, sorted by path, and holds at most one change per path. `revision` is always `base(author) + 1`.
  */
final case class Patch(
  author: ContributorId,
  revision: Long,
  base: Version,
  message: String,
  changes: Vector[Change]
):
  def dot: Dot = Dot(author, revision)

  /** SPEC §4.2 — the version this patch produces from its base. */
  def result: Version = Version(base.components.updated(author, revision))

/** SPEC §4.1 — the complete repository value stored in `.snap/repository.json`.
  *
  * `patches` is exactly the causal closure of `frontier`, sorted by author then numeric revision, with no unreachable patch.
  */
final case class Repository(format: Int, frontier: Version, patches: Vector[Patch])

object Repository:
  /** The only `format` this version of Snap reads or writes. */
  val format: Int = 1

  val empty: Repository = Repository(format, Version.empty, Vector.empty)

/** SPEC §6.4 — why replay discarded a whole concurrent effect.
  *
  * Line-level OT emits no warning; only these whole-effect resolutions do.
  */
enum Reason(val token: String):
  case DeleteWins      extends Reason("delete-wins")
  case LaterCreateWins extends Reason("later-create-wins")
  case LaterPutWins    extends Reason("later-put-wins")
  case NamespaceWins   extends Reason("namespace-wins")
  case PutWins         extends Reason("put-wins")

/** SPEC §6.4 — one `(path, reason)` pair from a replay. */
final case class Warning(path: TrackedPath, reason: Reason)

object Warning:
  /** SPEC §6.4 — unique pairs sorted by path, then reason.
    *
    * "Then reason" is ordered by the wire token here. Confirm against `../tests/` before relying on it: declaration order would give a different answer for two
    * reasons on one path.
    */
  given Ordering[Warning] =
    Ordering.by[Warning, TrackedPath](_.path).orElseBy(_.reason.token)

/** SPEC §7.3 — a working-tree change code. */
enum StatusCode(val code: String):
  case Added    extends StatusCode("A")
  case Modified extends StatusCode("M")
  case Deleted  extends StatusCode("D")

/** SPEC §7.3 — one row of `snap status`, sorted by path. */
final case class StatusEntry(code: StatusCode, path: TrackedPath)

/** SPEC §8 — the shape of `.snap/config.json` and `$HOME/.snapconfig.json`. */
final case class Configuration(contributorId: Option[ContributorId])

object Configuration:
  val empty: Configuration = Configuration(None)

/** SPEC §7.11 — the selected output presentation.
  *
  * Choosing one MUST NOT change execution, repository or filesystem effects, warning selection or order, or exit status.
  */
enum Presentation:
  case Plain, Terminal

/** SPEC §10 — an expected failure.
  *
  * Rendered as `snap: <detail>` in plain mode and exits 1. Unexpected internal failures exit 2 and are not modelled here: they are the ones we did not
  * anticipate.
  */
final case class SnapError(detail: String)

/** Numeric bounds fixed by the specification. */
object Limits:
  /** SPEC §3.1 — revisions and edit counts are safe integers. */
  val maxSafeInteger: Long = 9007199254740991L

  /** SPEC §7.5 — the limit on a user-supplied commit message, in UTF-8 bytes. */
  val maxCommitMessageBytes: Int = 4096

  /** SPEC §3.1 — the limit on a contributor ID, in bytes. */
  val maxContributorIdBytes: Int = 254
