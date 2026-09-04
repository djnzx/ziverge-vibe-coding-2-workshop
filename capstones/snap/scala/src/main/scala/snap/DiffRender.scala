package snap

import scala.collection.immutable.SortedSet

/** Stable, repository-independent rendering for `snap diff`. */
object DiffRender:
  def render(before: Tree, after: Tree): String =
    val paths = SortedSet.from(before.keySet ++ after.keySet)
    paths.iterator
      .flatMap: path =>
        (before.get(path), after.get(path)) match
          case (Some(left), Some(right)) if left == right => None
          case (left, right)                              => Some(renderPath(path, left, right))
      .mkString

  private def renderPath(path: TrackedPath, before: Option[FileBytes], after: Option[FileBytes]): String =
    val oldName = before.fold("/dev/null")(_ => s"a/${path.value}")
    val newName = after.fold("/dev/null")(_ => s"b/${path.value}")

    (before, after) match
      case (left, right) if left.exists(bytes => !Text.isText(bytes)) || right.exists(bytes => !Text.isText(bytes)) =>
        s"Binary files $oldName and $newName differ\n"
      case _ =>
        val oldTokens = before.filter(Text.isText).fold(Vector.empty[String])(Text.tokens)
        val newTokens = after.filter(Text.isText).fold(Vector.empty[String])(Text.tokens)
        val rendered  = renderEdits(oldTokens, Diff.diff(oldTokens, newTokens))
        val marker    =
          if before.exists(bytes => Text.isText(bytes) && bytes.length > 0 && !endsInNewline(bytes)) ||
            after.exists(bytes => Text.isText(bytes) && bytes.length > 0 && !endsInNewline(bytes))
          then "\\ No newline at end of file\n"
          else ""
        s"--- $oldName\n+++ $newName\n@@ -1,${oldTokens.length} +1,${newTokens.length} @@\n$rendered$marker"

  private def renderEdits(old: Vector[String], edits: Vector[EditOp]): String =
    val lines  = Vector.newBuilder[String]
    var cursor = 0
    edits.foreach:
      case EditOp.Retain(count) =>
        lines ++= old.slice(cursor, cursor + count.toInt).map(token => s" ${displayToken(token)}\n")
        cursor += count.toInt
      case EditOp.Delete(count) =>
        lines ++= old.slice(cursor, cursor + count.toInt).map(token => s"-${displayToken(token)}\n")
        cursor += count.toInt
      case EditOp.Insert(tokens) => lines ++= tokens.map(token => s"+${displayToken(token)}\n")
    lines.result().mkString

  private def endsInNewline(bytes: FileBytes): Boolean =
    val array = bytes.toArray
    array.nonEmpty && array.last == '\n'.toByte

  private def displayToken(token: String): String =
    if token.endsWith("\n") then token.dropRight(1) else token
