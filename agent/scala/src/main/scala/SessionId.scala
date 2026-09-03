package workshop.agent

opaque type SessionId = String

object SessionId {
  def fresh: SessionId            = java.util.UUID.randomUUID().toString
  def parse(s: String): SessionId = s

  extension (id: SessionId) {
    def value: String = id
  }
}
