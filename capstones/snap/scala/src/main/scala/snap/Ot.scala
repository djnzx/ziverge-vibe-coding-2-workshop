package snap

import scala.annotation.tailrec

/** SPEC §6.3 — transform an incoming text edit `p` so it applies after the aggregate context edit `q`, both authored against the same base. Walks both streams
  * left to right, splitting counts as needed, applying the six rows in their stated precedence: `Q insert` first, then `P insert`, then the four retain/delete
  * pairs.
  */
object Ot:

  /** Prepend one single-count operation, merging it into the head of `acc` when both are the same kind. `acc` accumulates in reverse emission order, mirroring
    * `Diff.push`.
    */
  private def push(acc: List[EditOp], op: EditOp): List[EditOp] = (acc, op) match
    case (EditOp.Retain(c) :: rest, EditOp.Retain(c2)) => EditOp.Retain(c + c2) :: rest
    case (EditOp.Delete(c) :: rest, EditOp.Delete(c2)) => EditOp.Delete(c + c2) :: rest
    case (EditOp.Insert(t) :: rest, EditOp.Insert(t2)) => EditOp.Insert(t ++ t2) :: rest
    case _                                             => op :: acc

  /** Consume `n` from a `count`-sized retain/delete op, pushing the remainder (rebuilt via `mk`) back onto the stream when `n` does not exhaust it.
    */
  private def remainder(count: Long, n: Long, rest: List[EditOp], mk: Long => EditOp): List[EditOp] =
    if count > n then mk(count - n) :: rest else rest

  /** SPEC §6.3's six-row table, applied left to right until both streams end. */
  def transform(p: Vector[EditOp], q: Vector[EditOp]): Vector[EditOp] =
    @tailrec
    def loop(p: List[EditOp], q: List[EditOp], acc: List[EditOp]): List[EditOp] =
      (p, q) match
        case (Nil, Nil) => acc.reverse

        // Q insert: takes priority over whatever P has next, including a concurrent P insert.
        case (ps, EditOp.Insert(toks) :: qRest) =>
          loop(ps, qRest, push(acc, EditOp.Retain(toks.length.toLong)))

        // P insert: passes through unchanged.
        case (EditOp.Insert(toks) :: pRest, qs) =>
          loop(pRest, qs, push(acc, EditOp.Insert(toks)))

        case (EditOp.Retain(pc) :: pRest, EditOp.Retain(qc) :: qRest) =>
          val n = math.min(pc, qc)
          loop(remainder(pc, n, pRest, EditOp.Retain.apply), remainder(qc, n, qRest, EditOp.Retain.apply), push(acc, EditOp.Retain(n)))

        case (EditOp.Delete(pc) :: pRest, EditOp.Retain(qc) :: qRest) =>
          val n = math.min(pc, qc)
          loop(remainder(pc, n, pRest, EditOp.Delete.apply), remainder(qc, n, qRest, EditOp.Retain.apply), push(acc, EditOp.Delete(n)))

        case (EditOp.Retain(pc) :: pRest, EditOp.Delete(qc) :: qRest) =>
          val n = math.min(pc, qc)
          loop(remainder(pc, n, pRest, EditOp.Retain.apply), remainder(qc, n, qRest, EditOp.Delete.apply), acc)

        case (EditOp.Delete(pc) :: pRest, EditOp.Delete(qc) :: qRest) =>
          val n = math.min(pc, qc)
          loop(remainder(pc, n, pRest, EditOp.Delete.apply), remainder(qc, n, qRest, EditOp.Delete.apply), acc)

        // Only reachable when p and q do not consume the same base token count, which never happens for two
        // scripts diffed from the same base (§6.3's precondition); halt rather than throw.
        case _ => acc.reverse

    loop(p.toList, q.toList, Nil).toVector
