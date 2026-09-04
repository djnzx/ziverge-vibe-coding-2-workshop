package snap

import scala.annotation.tailrec

/** SPEC §5 — the one deterministic token diff used by patch creation, displayed diffs, and OT.
  *
  * The `D(i, j)` recurrence and the delete-on-tie rule *define* the output; this is the literal memoized version, which serves as the oracle for any future
  * optimization (§5 permits a swap only if it agrees on every input, including repeated equal lines).
  */
object Diff:

  /** `d(i)(j)` is the minimum inserts/deletes needed to transform `old(i..)` into `neu(j..)`, per §5's recurrence. */
  private def distances(old: Vector[String], neu: Vector[String]): Array[Array[Int]] =
    val n = old.length
    val m = neu.length
    val d = Array.ofDim[Int](n + 1, m + 1)
    for i <- n to 0 by -1 do
      for j <- m to 0 by -1 do
        d(i)(j) =
          if i == n && j == m then 0
          else if i == n then m - j
          else if j == m then n - i
          else if old(i) == neu(j) then d(i + 1)(j + 1)
          else 1 + math.min(d(i + 1)(j), d(i)(j + 1))
    d

  /** Prepend one single-token operation, merging it into the head of `acc` when both are the same kind. `acc` accumulates in reverse emission order, so the
    * head is always the most recently emitted operation.
    */
  private def push(acc: List[EditOp], op: EditOp): List[EditOp] = (acc, op) match
    case (EditOp.Retain(c) :: rest, EditOp.Retain(c2)) => EditOp.Retain(c + c2) :: rest
    case (EditOp.Delete(c) :: rest, EditOp.Delete(c2)) => EditOp.Delete(c + c2) :: rest
    case (EditOp.Insert(t) :: rest, EditOp.Insert(t2)) => EditOp.Insert(t ++ t2) :: rest
    case _                                             => op :: acc

  /** SPEC §5 — the canonical token diff: walk from `(0, 0)`, retaining equal tokens, otherwise deleting when `D(i+1,j) <= D(i,j+1)` and inserting otherwise,
    * draining whichever side runs out first, with adjacent same-kind operations coalesced as they are produced.
    */
  def diff(old: Vector[String], neu: Vector[String]): Vector[EditOp] =
    val n = old.length
    val m = neu.length
    val d = distances(old, neu)

    @tailrec
    def walk(i: Int, j: Int, acc: List[EditOp]): List[EditOp] =
      if i == n && j == m then acc.reverse
      else if i < n && j < m && old(i) == neu(j) then walk(i + 1, j + 1, push(acc, EditOp.Retain(1)))
      else if i < n && (j == m || d(i + 1)(j) <= d(i)(j + 1)) then walk(i + 1, j, push(acc, EditOp.Delete(1)))
      else walk(i, j + 1, push(acc, EditOp.Insert(Vector(neu(j)))))

    walk(0, 0, Nil).toVector
