use super::*;
use std::cell::RefCell;
use tempfile::tempdir;

mod exercise01_check_tool_permission {
    use super::*;

    #[test]
    fn allows_all_when_both_empty() {
        let r = check_tool_permission("shell", "ls", &[], &[]);
        assert!(r.allowed);
    }

    #[test]
    fn allow_only_filters() {
        let allow = vec!["shell".into()];
        assert!(check_tool_permission("shell", "ls", &allow, &[]).allowed);
        assert!(!check_tool_permission("read_file", "x", &allow, &[]).allowed);
    }

    #[test]
    fn deny_blocks_matching() {
        let deny = vec!["shell(rm *)".into()];
        assert!(check_tool_permission("shell", "ls", &[], &deny).allowed);
        assert!(!check_tool_permission("shell", "rm -rf /", &[], &deny).allowed);
    }

    #[test]
    fn deny_vetoes_allow() {
        let allow = vec!["shell".into()];
        let deny = vec!["shell(rm *)".into()];
        assert!(!check_tool_permission("shell", "rm -rf /", &allow, &deny).allowed);
    }

    #[test]
    fn message_user_always_allowed() {
        let deny = vec!["message_user".into()];
        assert!(check_tool_permission("message_user", "hi", &[], &deny).allowed);
    }

    #[test]
    fn returns_reason() {
        let r = check_tool_permission("shell", "rm -rf", &[], &["shell(rm *)".into()]);
        assert!(!r.reason.is_empty());
    }
}

mod exercise02_enforce_sandbox {
    use super::*;

    #[test]
    fn allows_path_within_workdir() {
        let r = enforce_sandbox(
            "read_file",
            &serde_json::json!({"path": "foo.txt"}),
            Path::new("/home/user/project"),
            &[],
        );
        assert!(r.allowed);
    }

    #[test]
    fn denies_path_escape() {
        let r = enforce_sandbox(
            "read_file",
            &serde_json::json!({"path": "../../etc/passwd"}),
            Path::new("/home/user/project"),
            &[],
        );
        assert!(!r.allowed);
    }

    #[test]
    fn denies_write_to_protected() {
        let r = enforce_sandbox(
            "write_file",
            &serde_json::json!({"path": "secret.key"}),
            Path::new("/home/user/project"),
            &["secret.key".into()],
        );
        assert!(!r.allowed);
    }

    #[test]
    fn allows_read_of_protected() {
        let r = enforce_sandbox(
            "read_file",
            &serde_json::json!({"path": "secret.key"}),
            Path::new("/home/user/project"),
            &["secret.key".into()],
        );
        assert!(r.allowed);
    }

    #[test]
    fn allows_non_file_tools() {
        let r = enforce_sandbox(
            "shell",
            &serde_json::json!({"command": "rm -rf /"}),
            Path::new("/home/user"),
            &[],
        );
        assert!(r.allowed);
    }

    #[tokio::test(flavor = "current_thread")]
    async fn analyze_shell_returns_allowed_on_no() {
        let provider = MockProvider {
            responses: RefCell::new(vec!["no".into()]),
        };
        let r = analyze_shell_sandbox(&provider, "ls", Path::new("/home"))
            .await
            .unwrap();
        assert!(r.allowed);
    }

    #[tokio::test(flavor = "current_thread")]
    async fn analyze_shell_returns_denied_on_yes() {
        let provider = MockProvider {
            responses: RefCell::new(vec!["yes".into()]),
        };
        let r = analyze_shell_sandbox(&provider, "cat /etc/passwd", Path::new("/home"))
            .await
            .unwrap();
        assert!(!r.allowed);
    }

    #[tokio::test(flavor = "current_thread")]
    async fn analyze_shell_returns_denied_on_unknown() {
        let provider = MockProvider {
            responses: RefCell::new(vec!["unknown".into()]),
        };
        let r = analyze_shell_sandbox(&provider, "eval stuff", Path::new("/home"))
            .await
            .unwrap();
        assert!(!r.allowed);
    }

    #[tokio::test(flavor = "current_thread")]
    async fn analyze_shell_returns_denied_on_unexpected_response() {
        let provider = MockProvider {
            responses: RefCell::new(vec!["maybe".into()]),
        };
        let r = analyze_shell_sandbox(&provider, "ls", Path::new("/home"))
            .await
            .unwrap();
        assert!(!r.allowed);
    }
}

mod exercise03_redact_secrets {
    use super::*;

    #[test]
    fn redacts_api_key() {
        let result = redact_secrets("key=sk-abc123def456ghi789", &["sk-[a-zA-Z0-9]{10,}".into()]);
        assert!(result.contains("[REDACTED]"));
        assert!(!result.contains("sk-abc123"));
    }

    #[test]
    fn leaves_clean_text() {
        let text = "hello world";
        assert_eq!(redact_secrets(text, &["sk-[a-zA-Z0-9]{10,}".into()]), text);
    }

    #[test]
    fn redacts_multiple_patterns() {
        let text = "api_key=sk-abc123def456 password=hunter2";
        let result = redact_secrets(
            text,
            &["sk-[a-zA-Z0-9]{10,}".into(), "password\\s*=\\s*\\S+".into()],
        );
        assert!(!result.contains("sk-abc123"));
        assert!(!result.contains("hunter2"));
    }

    #[test]
    fn handles_invalid_regex() {
        assert_eq!(redact_secrets("safe", &["[invalid".into()]), "safe");
    }
}

mod exercise04_log_audit_event {
    use super::*;

    #[test]
    fn appends_json_line() {
        let dir = tempdir().unwrap();
        let log_path = dir.path().join("audit.jsonl");
        let event = AuditEvent {
            timestamp: "2024-01-01T00:00:00Z".into(),
            event: "tool_call".into(),
            details: serde_json::json!({"tool": "shell"}),
        };
        log_audit_event(&log_path, &event).unwrap();
        let content = fs::read_to_string(&log_path).unwrap();
        let parsed: serde_json::Value = serde_json::from_str(content.trim()).unwrap();
        assert_eq!(parsed["event"], "tool_call");
    }

    #[test]
    fn creates_file_if_missing() {
        let dir = tempdir().unwrap();
        let log_path = dir.path().join("sub").join("audit.jsonl");
        log_audit_event(
            &log_path,
            &AuditEvent {
                timestamp: "t".into(),
                event: "tool_call".into(),
                details: serde_json::json!({}),
            },
        )
        .unwrap();
        assert!(log_path.exists());
    }

    #[test]
    fn multiple_events_multiple_lines() {
        let dir = tempdir().unwrap();
        let log_path = dir.path().join("audit.jsonl");
        log_audit_event(
            &log_path,
            &AuditEvent {
                timestamp: "t1".into(),
                event: "tool_call".into(),
                details: serde_json::json!({}),
            },
        )
        .unwrap();
        log_audit_event(
            &log_path,
            &AuditEvent {
                timestamp: "t2".into(),
                event: "tool_result".into(),
                details: serde_json::json!({}),
            },
        )
        .unwrap();
        let content = fs::read_to_string(&log_path).unwrap();
        let lines: Vec<&str> = content.trim().split('\n').collect();
        assert_eq!(lines.len(), 2);
    }
}

mod exercise05_apply_tool_decorators {
    use super::*;
    use crate::decorators::{deny, AgentState, ToolDecorator, ToolInvocation};
    use std::sync::Arc;

    #[tokio::test(flavor = "current_thread")]
    async fn before_deny_prevents_execution_and_feeds_back_the_reason() {
        let dir = tempfile::tempdir().unwrap();
        let provider = MockProvider {
            responses: RefCell::new(vec![
                r#"<tool_call>{"name":"shell","arguments":{"command":"touch must-not-exist"}}</tool_call>"#.into(),
                done_msg("blocked"),
            ]),
        };
        let no_shell = ToolDecorator {
            name: "no-shell".to_string(),
            applies_to: Arc::new(|invocation: &ToolInvocation, _: &AgentState| {
                invocation.name == "shell"
            }),
            before: Some(Arc::new(|_, _| {
                Box::pin(async { deny("shell forbidden in tests") })
            })),
            after: None,
        };
        let available_tools = tools();
        let mut config = AgentConfig::default_config();
        config.work_dir = dir.path().to_path_buf();
        let mut conversation = initial_messages(&config, &available_tools);
        handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut conversation,
            "use shell",
            None,
            &[no_shell],
        )
        .await
        .unwrap();

        assert!(!dir.path().join("must-not-exist").exists());
        let serialized = serde_json::to_string(&conversation.as_messages()).unwrap();
        assert!(serialized.contains("shell forbidden in tests"));
    }
}

mod exercise06_exit_gate {
    use crate::config::AgentConfig;
    use crate::decorators::{snapshot_agent_state, AgentState, DecoratorOutcome, ToolInvocation};
    use crate::exercises::exit_gate;
    use crate::tools::ToolResult;
    use std::path::PathBuf;

    fn state_at(work_dir: PathBuf) -> AgentState {
        let mut cfg = AgentConfig::default_config();
        cfg.work_dir = work_dir.clone();
        snapshot_agent_state(
            vec![],
            vec!["message_user".to_string()],
            cfg,
            None,
            work_dir,
        )
    }

    fn inv() -> ToolInvocation {
        ToolInvocation {
            name: "message_user".to_string(),
            arguments: serde_json::json!({"message": "done"}),
        }
    }

    fn ok_result() -> ToolResult {
        ToolResult::Ok("done".to_string())
    }

    #[tokio::test(flavor = "current_thread")]
    async fn applies_to_only_on_message_user_with_commands() {
        let d = exit_gate(vec!["true".to_string()]);
        assert!((d.applies_to)(&inv(), &state_at(std::env::temp_dir())));
        let other = ToolInvocation {
            name: "shell".to_string(),
            arguments: serde_json::json!({}),
        };
        assert!(!(d.applies_to)(&other, &state_at(std::env::temp_dir())));
        let empty = exit_gate(vec![]);
        assert!(!(empty.applies_to)(&inv(), &state_at(std::env::temp_dir())));
    }

    #[tokio::test(flavor = "current_thread")]
    async fn allow_when_all_gates_succeed() {
        let d = exit_gate(vec!["true".to_string(), "echo ok".to_string()]);
        let after = d.after.unwrap();
        let s = state_at(std::env::temp_dir());
        match after(&inv(), &ok_result(), &s).await {
            DecoratorOutcome::Allow { .. } => {}
            DecoratorOutcome::Deny { reason } => panic!("expected allow, got deny: {reason}"),
        }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn deny_with_exit_code_and_output_on_failure() {
        let d = exit_gate(vec!["echo to-stderr >&2 && exit 7".to_string()]);
        let after = d.after.unwrap();
        let s = state_at(std::env::temp_dir());
        match after(&inv(), &ok_result(), &s).await {
            DecoratorOutcome::Deny { reason } => {
                assert!(reason.contains("Exit-gate failed"), "reason: {reason}");
                assert!(reason.contains("exited 7"), "reason: {reason}");
                assert!(reason.contains("to-stderr"), "reason: {reason}");
            }
            _ => panic!("expected deny"),
        }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn short_circuits_after_first_failure() {
        let dir = std::env::temp_dir().join(format!("exit-gate-rs-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let sentinel = dir.join("must-not-exist");
        let _ = std::fs::remove_file(&sentinel);
        let touch = format!("touch {}", sentinel.display());
        let d = exit_gate(vec!["exit 1".to_string(), touch]);
        let after = d.after.unwrap();
        let s = state_at(dir.clone());
        match after(&inv(), &ok_result(), &s).await {
            DecoratorOutcome::Deny { .. } => {}
            _ => panic!("expected deny"),
        }
        assert!(!sentinel.exists(), "second gate should not have run");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn gates_run_in_work_dir() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("marker"), "x").unwrap();
        let d = exit_gate(vec!["test -f marker".to_string()]);
        let after = d.after.unwrap();
        let s = state_at(dir.path().to_path_buf());
        match after(&inv(), &ok_result(), &s).await {
            DecoratorOutcome::Allow { .. } => {}
            DecoratorOutcome::Deny { reason } => panic!("expected allow, got deny: {reason}"),
        }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn truncates_long_output() {
        let d = exit_gate(vec!["yes A | head -c 10000; exit 1".to_string()]);
        let after = d.after.unwrap();
        let s = state_at(std::env::temp_dir());
        match after(&inv(), &ok_result(), &s).await {
            DecoratorOutcome::Deny { reason } => {
                assert!(reason.contains("truncated"), "reason: {reason}");
                assert!(reason.len() < 4000, "reason too long: {}", reason.len());
            }
            _ => panic!("expected deny"),
        }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn missing_command_denies() {
        let d = exit_gate(vec!["definitely-not-a-real-command-for-m03".to_string()]);
        let after = d.after.unwrap();
        let s = state_at(std::env::temp_dir());
        match after(&inv(), &ok_result(), &s).await {
            DecoratorOutcome::Deny { reason } => assert!(reason.contains("exited")),
            DecoratorOutcome::Allow { .. } => panic!("expected deny"),
        }
    }
}

mod exercise07_checkpoints {
    use super::*;

    #[test]
    fn create_copies_files() {
        let dir = tempdir().unwrap();
        let work_dir = dir.path().join("work");
        let cp_dir = dir.path().join("checkpoints");
        fs::create_dir(&work_dir).unwrap();
        fs::write(work_dir.join("file.txt"), "original").unwrap();
        let info = create_checkpoint(&work_dir, &cp_dir).unwrap();
        assert!(info.id.starts_with("cp-"));
        assert!(info.path.join("file.txt").exists());
    }

    #[test]
    fn restore_overwrites() {
        let dir = tempdir().unwrap();
        let work_dir = dir.path().join("work");
        let cp_dir = dir.path().join("checkpoints");
        fs::create_dir(&work_dir).unwrap();
        fs::write(work_dir.join("file.txt"), "original").unwrap();
        let info = create_checkpoint(&work_dir, &cp_dir).unwrap();
        fs::write(work_dir.join("file.txt"), "modified").unwrap();
        assert!(restore_checkpoint(&work_dir, &info.id, &cp_dir));
        assert_eq!(
            fs::read_to_string(work_dir.join("file.txt")).unwrap(),
            "original"
        );
    }

    #[test]
    fn list_returns_checkpoints() {
        let dir = tempdir().unwrap();
        let work_dir = dir.path().join("work");
        let cp_dir = dir.path().join("checkpoints");
        fs::create_dir(&work_dir).unwrap();
        fs::write(work_dir.join("a.txt"), "a").unwrap();
        let cp1 = create_checkpoint(&work_dir, &cp_dir).unwrap();
        let cp2 = create_checkpoint(&work_dir, &cp_dir).unwrap();
        let list = list_checkpoints(&cp_dir);
        assert!(list.len() >= 2);
        assert!(list.iter().any(|c| c.id == cp1.id));
        assert!(list.iter().any(|c| c.id == cp2.id));
        assert_eq!(
            list.iter()
                .take(2)
                .map(|c| c.id.clone())
                .collect::<Vec<_>>(),
            vec![cp2.id.clone(), cp1.id.clone()],
            "list_checkpoints should return newest checkpoints first"
        );
    }

    #[test]
    fn restore_nonexistent_returns_false() {
        let dir = tempdir().unwrap();
        assert!(!restore_checkpoint(dir.path(), "nonexistent", dir.path()));
    }
}

mod exercise08_sandboxed_shell {
    use super::*;
    use std::fs;

    fn fake_limactl(dir: &Path, body: &str) -> std::path::PathBuf {
        use std::os::unix::fs::PermissionsExt;

        let executable = dir.join("limactl");
        fs::write(&executable, format!("#!/bin/sh\n{body}\n")).unwrap();
        let mut permissions = fs::metadata(&executable).unwrap().permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(&executable, permissions).unwrap();
        executable
    }

    #[test]
    fn passes_exact_limactl_arguments_and_returns_stdout() {
        let dir = tempdir().unwrap();
        let args_path = dir.path().join("limactl-args.txt");
        let executable = fake_limactl(
            dir.path(),
            format!(
                "printf '%s\\n' \"$@\" > \"{}\"\nprintf 'sandbox ok\\n'",
                args_path.display()
            )
            .as_str(),
        );

        let result = execute_sandboxed_shell_with(
            executable.to_str().unwrap(),
            "printf '%s' done",
            Path::new("/tmp/work dir"),
        );

        match result {
            ToolResult::Ok(output) => assert_eq!(output, "sandbox ok\n"),
            ToolResult::Err(error) => {
                panic!("expected stub limactl execution to succeed, got error: {error}");
            }
        }

        let args = fs::read_to_string(&args_path).unwrap();
        assert_eq!(
            args.lines().collect::<Vec<_>>(),
            vec![
                "shell",
                "default",
                "--",
                "bash",
                "-c",
                "cd -- '/tmp/work dir' && printf '%s' done"
            ],
            "execute_sandboxed_shell should pass expected limactl arguments"
        );
    }

    #[test]
    fn reports_nonzero_limactl_exit() {
        let dir = tempdir().unwrap();
        let executable = fake_limactl(dir.path(), "printf 'remote failed\\n' >&2\nexit 7");
        match execute_sandboxed_shell_with(
            executable.to_str().unwrap(),
            "false",
            Path::new("/tmp/work"),
        ) {
            ToolResult::Err(error) => assert!(error.contains("remote failed")),
            ToolResult::Ok(output) => panic!("expected failure, got output: {output}"),
        }
    }

    #[test]
    fn reports_limactl_start_failure() {
        match execute_sandboxed_shell_with(
            "/definitely/missing/limactl",
            "true",
            Path::new("/tmp/work"),
        ) {
            ToolResult::Err(error) => assert!(error.starts_with("sandboxed shell error:")),
            ToolResult::Ok(output) => panic!("expected failure, got output: {output}"),
        }
    }
}

mod integration_handle_turn_code_decorators {
    use super::*;
    use crate::decorators::{
        allow, deny, AgentState, DecoratorOutcome, ToolDecorator, ToolInvocation,
    };
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;

    fn fixed_provider(responses: Vec<String>) -> MockProvider {
        MockProvider {
            responses: RefCell::new(responses),
        }
    }

    fn pass_through_dec(name: &str, counter: Arc<AtomicUsize>) -> ToolDecorator {
        ToolDecorator {
            name: name.to_string(),
            applies_to: Arc::new(|_inv: &ToolInvocation, _state: &AgentState| true),
            before: Some(Arc::new(move |inv, _state| {
                let c = counter.clone();
                let inv = inv.clone();
                Box::pin(async move {
                    c.fetch_add(1, Ordering::SeqCst);
                    allow(inv)
                })
            })),
            after: None,
        }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn empty_code_decorator_list_unchanged_behavior() {
        let tmp = tempfile::tempdir().unwrap();
        let work = tmp.path().to_path_buf();
        std::fs::write(work.join("a.txt"), "hello").unwrap();
        let provider = fixed_provider(vec![
            r#"<tool_call>{"name":"read_file","arguments":{"path":"a.txt"}}</tool_call>"#.into(),
            done_msg("ok"),
        ]);
        let available_tools = tools();
        let mut config = AgentConfig::default_config();
        config.work_dir = work;
        let mut conversation = initial_messages(&config, &available_tools);
        let result = handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut conversation,
            "read",
            None,
            &[],
        )
        .await
        .unwrap();
        assert_eq!(result.content, "ok");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn before_deny_blocks_execution_and_pushes_reason() {
        let tmp = tempfile::tempdir().unwrap();
        let work = tmp.path().to_path_buf();
        let provider = fixed_provider(vec![
            r#"<tool_call>{"name":"shell","arguments":{"command":"echo hi"}}</tool_call>"#.into(),
            done_msg("aborted"),
        ]);
        let no_shell = ToolDecorator {
            name: "no-shell".to_string(),
            applies_to: Arc::new(|inv, _state| inv.name == "shell"),
            before: Some(Arc::new(|_inv, _state| {
                Box::pin(async move { deny("shell forbidden in tests") })
            })),
            after: None,
        };
        let available_tools = tools();
        let mut config = AgentConfig::default_config();
        config.work_dir = work;
        let mut conversation = initial_messages(&config, &available_tools);
        handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut conversation,
            "use shell",
            None,
            &[no_shell],
        )
        .await
        .unwrap();
        let serialized = serde_json::to_string(&conversation.as_messages()).unwrap();
        assert!(
            serialized.contains("blocked by decorator no-shell"),
            "expected no-shell deny in conversation: {serialized}"
        );
        assert!(
            serialized.contains("shell forbidden in tests"),
            "expected deny reason in conversation: {serialized}"
        );
        assert!(
            !serialized.contains("Tool shell returned:"),
            "shell tool must not have executed"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn before_can_rewrite_arguments() {
        let tmp = tempfile::tempdir().unwrap();
        let work = tmp.path().to_path_buf();
        std::fs::write(work.join("real.txt"), "real-content").unwrap();
        std::fs::write(work.join("fake.txt"), "fake-content").unwrap();
        let provider = fixed_provider(vec![
            r#"<tool_call>{"name":"read_file","arguments":{"path":"fake.txt"}}</tool_call>"#.into(),
            done_msg("done"),
        ]);
        let real_path = work.join("real.txt").to_string_lossy().to_string();
        let swap = ToolDecorator {
            name: "swap-path".to_string(),
            applies_to: Arc::new(|inv, _state| inv.name == "read_file"),
            before: Some(Arc::new(move |inv, _state| {
                let path = real_path.clone();
                let inv = inv.clone();
                Box::pin(async move {
                    let mut new_args = inv.arguments.clone();
                    if let Some(obj) = new_args.as_object_mut() {
                        obj.insert("path".to_string(), serde_json::Value::String(path));
                    }
                    allow(ToolInvocation {
                        name: inv.name,
                        arguments: new_args,
                    })
                })
            })),
            after: None,
        };
        let available_tools = tools();
        let mut config = AgentConfig::default_config();
        config.work_dir = work;
        let mut conversation = initial_messages(&config, &available_tools);
        handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut conversation,
            "read fake",
            None,
            &[swap],
        )
        .await
        .unwrap();
        let serialized = serde_json::to_string(&conversation.as_messages()).unwrap();
        assert!(
            serialized.contains("real-content"),
            "decorator should have swapped to real.txt: {serialized}"
        );
        assert!(
            !serialized.contains("fake-content"),
            "fake.txt should not have been read"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn after_deny_replaces_tool_output() {
        let tmp = tempfile::tempdir().unwrap();
        let work = tmp.path().to_path_buf();
        std::fs::write(work.join("secrets.txt"), "PASSWORD=supersecret").unwrap();
        let provider = fixed_provider(vec![
            r#"<tool_call>{"name":"read_file","arguments":{"path":"secrets.txt"}}</tool_call>"#
                .into(),
            done_msg("ok"),
        ]);
        let scrubber = ToolDecorator {
            name: "no-secrets".to_string(),
            applies_to: Arc::new(|inv, _state| inv.name == "read_file"),
            before: None,
            after: Some(Arc::new(|_inv, result, _state| {
                let text = match result {
                    crate::tools::ToolResult::Ok(s) | crate::tools::ToolResult::Err(s) => s.clone(),
                };
                Box::pin(async move {
                    if text.contains("PASSWORD=") {
                        deny("output contains a secret")
                    } else {
                        allow(ToolInvocation {
                            name: "read_file".to_string(),
                            arguments: serde_json::Value::Object(Default::default()),
                        })
                    }
                })
            })),
        };
        let available_tools = tools();
        let mut config = AgentConfig::default_config();
        config.work_dir = work;
        let mut conversation = initial_messages(&config, &available_tools);
        handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut conversation,
            "read secrets",
            None,
            &[scrubber],
        )
        .await
        .unwrap();
        let serialized = serde_json::to_string(&conversation.as_messages()).unwrap();
        assert!(
            !serialized.contains("supersecret"),
            "secret must not reach LLM history"
        );
        assert!(
            serialized.contains("output blocked by decorator no-secrets"),
            "expected after-deny reason: {serialized}"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn first_deny_short_circuits_chain() {
        let tmp = tempfile::tempdir().unwrap();
        let work = tmp.path().to_path_buf();
        let provider = fixed_provider(vec![
            r#"<tool_call>{"name":"shell","arguments":{"command":"echo hi"}}</tool_call>"#.into(),
            done_msg("ok"),
        ]);
        let second_count = Arc::new(AtomicUsize::new(0));
        let first = ToolDecorator {
            name: "first".to_string(),
            applies_to: Arc::new(|_inv, _state| true),
            before: Some(Arc::new(|_inv, _state| {
                Box::pin(async move { deny("first denied") })
            })),
            after: None,
        };
        let second = pass_through_dec("second", second_count.clone());
        let available_tools = tools();
        let mut config = AgentConfig::default_config();
        config.work_dir = work;
        let mut conversation = initial_messages(&config, &available_tools);
        handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut conversation,
            "shell",
            None,
            &[first, second],
        )
        .await
        .unwrap();
        assert_eq!(
            second_count.load(Ordering::SeqCst),
            0,
            "second decorator must not run after first deny"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn applies_to_false_skips_both_before_and_after() {
        let tmp = tempfile::tempdir().unwrap();
        let work = tmp.path().to_path_buf();
        std::fs::write(work.join("x.txt"), "hi").unwrap();
        let provider = fixed_provider(vec![
            r#"<tool_call>{"name":"read_file","arguments":{"path":"x.txt"}}</tool_call>"#.into(),
            done_msg("ok"),
        ]);
        let before_count = Arc::new(AtomicUsize::new(0));
        let after_count = Arc::new(AtomicUsize::new(0));
        let before_count_cl = before_count.clone();
        let after_count_cl = after_count.clone();
        let shell_only = ToolDecorator {
            name: "shell-only".to_string(),
            applies_to: Arc::new(|inv, _state| inv.name == "shell"),
            before: Some(Arc::new(move |inv, _state| {
                let c = before_count_cl.clone();
                let inv = inv.clone();
                Box::pin(async move {
                    c.fetch_add(1, Ordering::SeqCst);
                    allow(inv)
                })
            })),
            after: Some(Arc::new(move |inv, _result, _state| {
                let c = after_count_cl.clone();
                let inv = inv.clone();
                Box::pin(async move {
                    c.fetch_add(1, Ordering::SeqCst);
                    allow(inv)
                })
            })),
        };
        let available_tools = tools();
        let mut config = AgentConfig::default_config();
        config.work_dir = work;
        let mut conversation = initial_messages(&config, &available_tools);
        handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut conversation,
            "read",
            None,
            &[shell_only],
        )
        .await
        .unwrap();
        assert_eq!(
            before_count.load(Ordering::SeqCst),
            0,
            "before must not run"
        );
        assert_eq!(after_count.load(Ordering::SeqCst), 0, "after must not run");
    }

    fn _ensure_outcome_pattern() {
        let _ = match deny("x") {
            DecoratorOutcome::Allow { .. } => 1,
            DecoratorOutcome::Deny { .. } => 2,
        };
    }

    #[tokio::test(flavor = "current_thread")]
    async fn artifact_hook_after_deny_on_message_user_continues_loop() {
        let tmp = tempfile::tempdir().unwrap();
        let work = tmp.path().to_path_buf();
        // First message_user → "bad" → decorator denies. Second → "ok" → allows.
        let provider = MockProvider {
            responses: RefCell::new(vec![
                r#"<tool_call>{"name":"message_user","arguments":{"message":"bad"}}</tool_call>"#
                    .into(),
                r#"<tool_call>{"name":"message_user","arguments":{"message":"ok"}}</tool_call>"#
                    .into(),
            ]),
        };
        let calls = Arc::new(AtomicUsize::new(0));
        let calls_cl = calls.clone();
        let deny_bad = ToolDecorator {
            name: "deny-bad-artifact".to_string(),
            applies_to: Arc::new(|inv, _| inv.name == "message_user"),
            before: None,
            after: Some(Arc::new(move |inv, result, _state| {
                let c = calls_cl.clone();
                let inv = inv.clone();
                let text = match result {
                    crate::tools::ToolResult::Ok(s) | crate::tools::ToolResult::Err(s) => s.clone(),
                };
                Box::pin(async move {
                    c.fetch_add(1, Ordering::SeqCst);
                    if text == "bad" {
                        deny("artifact says 'bad'")
                    } else {
                        allow(inv)
                    }
                })
            })),
        };
        let available_tools = tools();
        let mut config = AgentConfig::default_config();
        config.work_dir = work;
        let mut conversation = initial_messages(&config, &available_tools);
        let result = handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut conversation,
            "go",
            None,
            &[deny_bad],
        )
        .await
        .unwrap();
        assert_eq!(
            calls.load(Ordering::SeqCst),
            2,
            "decorator fires on both attempts"
        );
        assert_eq!(result.content, "ok", "second attempt delivered");
        let serialized = serde_json::to_string(&conversation.as_messages()).unwrap();
        assert!(
            serialized.contains("blocked by decorator deny-bad-artifact"),
            "deny msg must appear in conversation as a revision prompt: {serialized}"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn chain_composition_allow_with_modified_args_flows_to_next_decorator() {
        let tmp = tempfile::tempdir().unwrap();
        let work = tmp.path().to_path_buf();
        std::fs::write(work.join("real.txt"), "real-content").unwrap();
        let provider = MockProvider {
            responses: RefCell::new(vec![
                r#"<tool_call>{"name":"read_file","arguments":{"path":"fake.txt"}}</tool_call>"#
                    .into(),
                done_msg("done"),
            ]),
        };
        let real_path = work.join("real.txt").to_string_lossy().to_string();
        let swap = ToolDecorator {
            name: "swap-path".to_string(),
            applies_to: Arc::new(|inv, _| inv.name == "read_file"),
            before: Some(Arc::new(move |inv, _state| {
                let path = real_path.clone();
                let inv = inv.clone();
                Box::pin(async move {
                    let mut args = inv.arguments.clone();
                    if let Some(obj) = args.as_object_mut() {
                        obj.insert("path".to_string(), serde_json::Value::String(path));
                    }
                    allow(ToolInvocation {
                        name: inv.name,
                        arguments: args,
                    })
                })
            })),
            after: None,
        };
        let observed: Arc<std::sync::Mutex<Option<String>>> = Arc::new(std::sync::Mutex::new(None));
        let observed_cl = observed.clone();
        let observe = ToolDecorator {
            name: "observe-path".to_string(),
            applies_to: Arc::new(|inv, _| inv.name == "read_file"),
            before: Some(Arc::new(move |inv, _state| {
                let obs = observed_cl.clone();
                let inv = inv.clone();
                Box::pin(async move {
                    if let Some(p) = inv.arguments.get("path").and_then(|v| v.as_str()) {
                        *obs.lock().unwrap() = Some(p.to_string());
                    }
                    allow(inv)
                })
            })),
            after: None,
        };
        let available_tools = tools();
        let mut config = AgentConfig::default_config();
        config.work_dir = work.clone();
        let mut conversation = initial_messages(&config, &available_tools);
        handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut conversation,
            "read fake",
            None,
            &[swap, observe],
        )
        .await
        .unwrap();
        let got = observed.lock().unwrap().clone();
        assert_eq!(
            got,
            Some(work.join("real.txt").to_string_lossy().to_string()),
            "second decorator should see the path the first one rewrote"
        );
    }
}
