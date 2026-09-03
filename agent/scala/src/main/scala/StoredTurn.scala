package workshop.agent

import zio.blocks.schema.Schema

/** A single role/text pair as persisted to a session file. The structural pair carried by
  * [[StoredSession]]; see [[Conversation]]/[[TurnMessage]] for the in-memory form.
  */
final case class StoredTurn(role: String, content: String) derives Schema
