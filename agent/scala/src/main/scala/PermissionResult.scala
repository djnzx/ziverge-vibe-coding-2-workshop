package workshop.agent

enum PermissionResult(val reason: String) {
  case Allow(why: String) extends PermissionResult(why)
  case Deny(why: String)  extends PermissionResult(why)

  def allowed: Boolean = this match {
    case _: Allow => true
    case _: Deny  => false
  }
}
