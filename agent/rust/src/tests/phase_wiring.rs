// Regression: phase-scoped decorators silently never fire when the role-prompt
// path doesn't match the phase id. Workflow orchestrators materialize
// their inline role prompts to temp files like `phase-role-<pid>-<n>.md` or
// `<phase>.prompt.md`. The basename of those files isn't a phase id, so
// `current_phase_from_config` (which derived phase from the role-prompt
// basename) returned a value no decorator could match.
//
// The fix introduced an explicit `config.current_phase` field that callers
// set; `current_phase_from_config` prefers that over the basename heuristic.

use super::*;
use crate::decorators::{allow, deny, AgentState, DecoratorOutcome, ToolDecorator, ToolInvocation};
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

// ─────────────────────── current_phase_from_config unit ────────────────────

#[test]
fn current_phase_from_config_prefers_explicit_field() {
    // Mirrors the failure mode: a materialized inline-prompt path whose
    // basename ("phase-role-12345-9") is not a phase id. Without the
    // fix, the bogus basename would leak into AgentState.
    let mut config = AgentConfig::default_config();
    config.role_prompt = Some(PathBuf::from("/tmp/phase-role-12345-9.md"));
    config.current_phase = Some("codebase".to_string());
    assert_eq!(
        current_phase_from_config(&config).as_deref(),
        Some("codebase")
    );
}

#[test]
fn current_phase_from_config_falls_back_to_role_prompt_basename() {
    // Legacy path: clean filename like `spec.md` still works.
    let mut config = AgentConfig::default_config();
    config.role_prompt = Some(PathBuf::from("/some/dir/spec.md"));
    config.current_phase = None;
    assert_eq!(current_phase_from_config(&config).as_deref(), Some("spec"));
}

#[test]
fn current_phase_from_config_demonstrates_legacy_failure_mode_for_materialized_inline_prompts() {
    // This documents the bug: without the explicit field, the workflow's
    // temp file produces a phase id no decorator matches.
    let mut config = AgentConfig::default_config();
    config.role_prompt = Some(PathBuf::from("/tmp/phase-role-12345-9.md"));
    config.current_phase = None;
    let derived = current_phase_from_config(&config);
    let derived = derived.as_deref();
    assert!(derived != Some("codebase"));
    assert!(derived != Some("discovery"));
    assert!(derived != Some("implementation"));
    assert!(derived != Some("verification"));
}

#[test]
fn current_phase_from_config_returns_none_when_neither_field_is_set() {
    let mut config = AgentConfig::default_config();
    config.role_prompt = None;
    config.current_phase = None;
    assert_eq!(current_phase_from_config(&config), None);
}

// ─────────────────────────── handle_turn integration ───────────────────────

fn fixed_provider(responses: Vec<String>) -> MockProvider {
    MockProvider {
        responses: RefCell::new(responses),
    }
}

fn phase_probe_decorator(expected_phase: &'static str, counter: Arc<AtomicUsize>) -> ToolDecorator {
    let expected = expected_phase.to_string();
    let expected_for_applies = expected.clone();
    ToolDecorator {
        name: "phase-probe".to_string(),
        applies_to: Arc::new(move |inv: &ToolInvocation, state: &AgentState| {
            inv.name == "read_file"
                && state.current_phase.as_deref() == Some(expected_for_applies.as_str())
        }),
        before: Some(Arc::new(move |_inv, _state| {
            let c = counter.clone();
            Box::pin(async move {
                c.fetch_add(1, Ordering::SeqCst);
                deny("test-only sentinel: decorator fired")
            })
        })),
        after: None,
    }
}

#[tokio::test(flavor = "current_thread")]
async fn handle_turn_fires_phase_scoped_decorator_when_explicit_current_phase_is_set() {
    // The killer regression test. Pre-fix: counter stays at 0 because
    // `current_phase_from_config` returns "phase-role-99999-42" (the
    // basename of the temp file), which doesn't match "codebase".
    let tmp = tempfile::tempdir().unwrap();
    let work = tmp.path().to_path_buf();
    std::fs::write(work.join("a.txt"), "hello").unwrap();
    // build_system_prompt reads role_prompt off disk, so materialize a real
    // file with a name that defeats the basename heuristic.
    let role_prompt_path = work.join("phase-role-99999-42.md");
    std::fs::write(
        &role_prompt_path,
        "# Role\n\nSynthetic role prompt for test.",
    )
    .unwrap();

    let counter = Arc::new(AtomicUsize::new(0));
    let dec = phase_probe_decorator("codebase", counter.clone());

    let provider = fixed_provider(vec![
        r#"<tool_call>{"name":"read_file","arguments":{"path":"a.txt"}}</tool_call>"#.into(),
        done_msg("done"),
    ]);
    let available_tools = tools();
    let mut config = AgentConfig::default_config();
    config.work_dir = work;
    config.role_prompt = Some(role_prompt_path);
    config.current_phase = Some("codebase".to_string());
    let mut conversation = initial_messages(&config, &available_tools);

    handle_turn(
        &provider,
        &config,
        &available_tools,
        &mut conversation,
        "do the thing",
        None,
        &[dec],
    )
    .await
    .unwrap();

    assert_eq!(
        counter.load(Ordering::SeqCst),
        1,
        "decorator must fire once when explicit current_phase matches"
    );
    let serialized = serde_json::to_string(&conversation.as_messages()).unwrap();
    assert!(
        serialized.contains("blocked by decorator phase-probe"),
        "expected decorator deny in conversation: {serialized}"
    );
}

#[tokio::test(flavor = "current_thread")]
async fn handle_turn_does_not_fire_phase_scoped_decorator_when_current_phase_is_unset_and_basename_does_not_match(
) {
    // Pre-fix behaviour, captured: basename of `phase-role-…md` is the
    // derived "phase" — appliesTo returns false, decorator never runs,
    // tool executes unblocked.
    let tmp = tempfile::tempdir().unwrap();
    let work = tmp.path().to_path_buf();
    std::fs::write(work.join("a.txt"), "hello").unwrap();
    // build_system_prompt reads role_prompt off disk, so materialize a real
    // file with a name that defeats the basename heuristic.
    let role_prompt_path = work.join("phase-role-99999-42.md");
    std::fs::write(
        &role_prompt_path,
        "# Role\n\nSynthetic role prompt for test.",
    )
    .unwrap();

    let counter = Arc::new(AtomicUsize::new(0));
    let dec = phase_probe_decorator("codebase", counter.clone());

    let provider = fixed_provider(vec![
        r#"<tool_call>{"name":"read_file","arguments":{"path":"a.txt"}}</tool_call>"#.into(),
        done_msg("done"),
    ]);
    let available_tools = tools();
    let mut config = AgentConfig::default_config();
    config.work_dir = work;
    config.role_prompt = Some(role_prompt_path);
    config.current_phase = None; // ← legacy callers, basename heuristic wins
    let mut conversation = initial_messages(&config, &available_tools);

    handle_turn(
        &provider,
        &config,
        &available_tools,
        &mut conversation,
        "do the thing",
        None,
        &[dec],
    )
    .await
    .unwrap();

    assert_eq!(
        counter.load(Ordering::SeqCst),
        0,
        "decorator must NOT fire when basename doesn't match and no explicit current_phase"
    );
}

#[test]
fn snapshot_agent_state_reflects_config_current_phase_end_to_end() {
    // Plumbing-only smoke: callers that build AgentState manually still see
    // the explicit field after `current_phase_from_config` resolves it.
    let mut config = AgentConfig::default_config();
    config.current_phase = Some("impact".to_string());
    let derived = current_phase_from_config(&config);
    let state = crate::decorators::snapshot_agent_state(
        Vec::new(),
        Vec::new(),
        config,
        derived,
        PathBuf::from("/tmp/x"),
    );
    assert_eq!(state.current_phase.as_deref(), Some("impact"));
}

#[allow(dead_code)]
fn _outcome_pattern_check(o: DecoratorOutcome) {
    // Compile-time assurance the imports are wired (debug helper).
    match o {
        DecoratorOutcome::Allow { invocation } => {
            let _ = allow(invocation);
        }
        DecoratorOutcome::Deny { reason: _ } => {}
    }
}
