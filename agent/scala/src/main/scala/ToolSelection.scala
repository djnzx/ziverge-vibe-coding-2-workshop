package workshop.agent

enum ToolSelection:
  case All
  case Only(tools: Set[ToolName])

object ToolSelection {

  /** Parse a list of tool wire-names into a [[ToolSelection]], validating each against the given
    * registry. With [[ToolName]] now open-world, "known name" is registry- relative, so the
    * registry must be supplied — callers can pass [[ToolRegistry.default]].
    */
  def parse(
    values: Vector[String],
    registry: ToolRegistry = ToolRegistry.default,
    emptyMeansAll: Boolean = false
  ): Either[String, ToolSelection] =
    if (values.isEmpty) {
      if (emptyMeansAll) Right(ToolSelection.All)
      else Right(ToolSelection.Only(Set.empty))
    } else {
      val known   = registry.names.toSet
      val unknown = values.filterNot(v => known.contains(ToolName(v))).distinct
      if (unknown.nonEmpty) Left(s"Unknown tool name(s): ${unknown.mkString(", ")}")
      else Right(ToolSelection.Only(values.map(ToolName(_)).toSet))
    }
}
