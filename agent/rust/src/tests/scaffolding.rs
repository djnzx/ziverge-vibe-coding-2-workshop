use super::*;

mod config {
    use super::*;

    #[test]
    fn default_config_has_sensible_defaults() {
        let config = AgentConfig::default_config();
        let expected_model =
            env::var("MODEL").unwrap_or_else(|_| "anthropic/claude-sonnet-4".to_string());

        assert_eq!(config.model, expected_model);
        assert_eq!(config.max_iterations, 20);
        assert_eq!(config.temperature, 0.0);
    }

    #[test]
    fn default_config_module02_fields() {
        let config = AgentConfig::default_config();
        assert!(config.instructions.is_none());
        assert!(config.skills_dir.is_none());
        assert!(config.history_dir.is_none());
        assert!(config.max_context_chars.is_none());
    }

    #[test]
    fn default_config_module03_fields() {
        let config = AgentConfig::default_config();
        assert!(config.allow_tools.is_empty());
        assert!(config.deny_tools.is_empty());
        assert!(config.protected_files.is_empty());
        assert!(!config.secret_patterns.is_empty());
        assert!(config.audit_log.is_none());
    }

    #[test]
    fn parse_cli_module02_flags() {
        let args: Vec<String> = vec![
            "--instructions",
            "./AGENTS.md",
            "--skills-dir",
            "./skills",
            "--history-dir",
            "./history",
            "--max-context-chars",
            "50000",
        ]
        .into_iter()
        .map(String::from)
        .collect();

        let parsed = parse_cli_config(&args).unwrap();
        assert_eq!(
            parsed.instructions.as_deref(),
            Some(Path::new("./AGENTS.md"))
        );
        assert_eq!(parsed.skills_dir.as_deref(), Some(Path::new("./skills")));
        assert_eq!(parsed.history_dir.as_deref(), Some(Path::new("./history")));
        assert_eq!(parsed.max_context_chars, Some(50000));
    }

    #[test]
    fn parse_cli_module03_flags() {
        let args: Vec<String> = vec![
            "--allow-tools",
            "read_file,write_file",
            "--deny-tools",
            "shell",
            "--protected-files",
            "*.env,secrets/*",
            "--secret-patterns",
            "sk-.*,ghp_.*",
            "--audit-log",
            "./audit.log",
        ]
        .into_iter()
        .map(String::from)
        .collect();

        let parsed = parse_cli_config(&args).unwrap();
        assert_eq!(
            parsed.allow_tools.as_ref().unwrap(),
            &vec!["read_file", "write_file"]
        );
        assert_eq!(parsed.deny_tools.as_ref().unwrap(), &vec!["shell"]);
        assert_eq!(
            parsed.protected_files.as_ref().unwrap(),
            &vec!["*.env", "secrets/*"]
        );
        assert_eq!(
            parsed.secret_patterns.as_ref().unwrap(),
            &vec!["sk-.*", "ghp_.*"]
        );
        assert_eq!(parsed.audit_log.as_deref(), Some(Path::new("./audit.log")));
    }

    #[test]
    fn parse_cli_rejects_conflicting_mode_flags() {
        let args: Vec<String> = vec!["--interactive", "--protocol"]
            .into_iter()
            .map(String::from)
            .collect();

        let err = parse_cli_config(&args).unwrap_err().to_string();
        assert_eq!(err, "Conflicting mode flags: --interactive and --protocol");
    }

    #[test]
    fn parse_cli_mode_conflict_reports_first_then_second() {
        let args: Vec<String> = vec!["--protocol", "--interactive"]
            .into_iter()
            .map(String::from)
            .collect();

        let err = parse_cli_config(&args).unwrap_err().to_string();
        assert_eq!(err, "Conflicting mode flags: --protocol and --interactive");
    }

    #[test]
    fn parse_tool_names_reports_all_unknown_names() {
        let names = vec![
            "read_file".to_string(),
            "foo".to_string(),
            "bar".to_string(),
        ];

        let err = parse_tool_names(&names).unwrap_err().to_string();
        assert_eq!(err, "Unknown tool name(s): foo, bar");
    }

    #[test]
    fn parse_cli_reports_standard_missing_value_for_config_and_file() {
        let config_err = parse_cli_config(&["--config".to_string()])
            .unwrap_err()
            .to_string();
        assert_eq!(config_err, "--config requires a value");

        let file_err = parse_cli_config(&["--file".to_string()])
            .unwrap_err()
            .to_string();
        assert_eq!(file_err, "--file requires a value");
    }

    #[test]
    fn resolve_config_preserves_module02_overrides() {
        let cli = CliConfig {
            instructions: Some(PathBuf::from("./AGENTS.md")),
            skills_dir: Some(PathBuf::from("./skills")),
            max_context_chars: Some(50000),
            ..Default::default()
        };

        let config = resolve_config(&cli);
        assert_eq!(
            config.instructions.as_deref(),
            Some(Path::new("./AGENTS.md"))
        );
        assert_eq!(config.skills_dir.as_deref(), Some(Path::new("./skills")));
        assert_eq!(config.max_context_chars, Some(50000));
    }

    #[test]
    fn resolve_config_preserves_module03_overrides() {
        let cli = CliConfig {
            allow_tools: Some(vec!["read_file".to_string()]),
            deny_tools: Some(vec!["shell".to_string()]),
            protected_files: Some(vec!["*.env".to_string()]),
            audit_log: Some(PathBuf::from("./audit.log")),
            ..Default::default()
        };

        let config = resolve_config(&cli);
        assert_eq!(config.allow_tools, vec!["read_file"]);
        assert_eq!(config.deny_tools, vec!["shell"]);
        assert_eq!(config.protected_files, vec!["*.env"]);
        assert_eq!(config.audit_log.as_deref(), Some(Path::new("./audit.log")));
    }

    #[test]
    fn custom_secret_patterns_override_defaults() {
        let cli = CliConfig {
            secret_patterns: Some(vec!["custom-.*".to_string()]),
            ..Default::default()
        };

        let config = resolve_config(&cli);
        assert_eq!(config.secret_patterns, vec!["custom-.*"]);
    }

    #[test]
    fn load_config_file_reads_json() {
        let dir = tempfile::tempdir().unwrap();
        let cfg_path = dir.path().join("agent.json");
        fs::write(
            &cfg_path,
            r#"{"model":"gpt-4o-mini","maxIterations":5,"temperature":0.3}"#,
        )
        .unwrap();

        let loaded = load_config_file(cfg_path.to_str().unwrap()).unwrap();
        assert_eq!(loaded.model.as_deref(), Some("gpt-4o-mini"));
        assert_eq!(loaded.max_iterations, Some(5));
        assert_eq!(loaded.temperature, Some(0.3));
    }

    #[test]
    fn config_flag_loads_file() {
        let dir = tempfile::tempdir().unwrap();
        let cfg_path = dir.path().join("cli-config.json");
        fs::write(
            &cfg_path,
            r#"{"model":"from-file","tools":["read_file"],"allowTools":["read_file"]}"#,
        )
        .unwrap();

        let args: Vec<String> = vec!["--config", cfg_path.to_str().unwrap()]
            .into_iter()
            .map(String::from)
            .collect();
        let parsed = parse_cli_config(&args).unwrap();
        assert_eq!(parsed.model.as_deref(), Some("from-file"));
        assert!(matches!(
            parsed.tools,
            Some(ToolSelection::Only(ref tools)) if tools == &vec![ToolName::ReadFile]
        ));
        assert_eq!(
            parsed.allow_tools.as_ref().unwrap(),
            &vec!["read_file".to_string()]
        );
    }

    #[test]
    fn cli_flags_override_config_file() {
        let dir = tempfile::tempdir().unwrap();
        let cfg_path = dir.path().join("override.json");
        fs::write(&cfg_path, r#"{"model":"from-file","maxIterations":10}"#).unwrap();

        let args: Vec<String> = vec![
            "--config",
            cfg_path.to_str().unwrap(),
            "--model",
            "from-cli",
        ]
        .into_iter()
        .map(String::from)
        .collect();
        let parsed = parse_cli_config(&args).unwrap();
        let config = resolve_config(&parsed);
        assert_eq!(config.model, "from-cli");
        assert_eq!(config.max_iterations, 10);
    }

    #[test]
    fn get_enabled_tools_returns_all_when_empty() {
        let all_tools = tools();
        let config = AgentConfig::default_config();

        let enabled = get_enabled_tools(&config, &all_tools);
        assert_eq!(enabled.len(), all_tools.len());
        for tool in &all_tools {
            assert!(enabled
                .iter()
                .any(|enabled_tool| enabled_tool.name == tool.name));
        }
    }

    #[test]
    fn get_enabled_tools_filters_plus_message_user() {
        let all_tools = tools();
        let mut config = AgentConfig::default_config();
        config.tools = ToolSelection::Only(vec![ToolName::ReadFile, ToolName::Shell]);

        let enabled = get_enabled_tools(&config, &all_tools);
        assert_eq!(enabled.len(), 3);
        assert!(enabled.iter().any(|tool| tool.name == "read_file"));
        assert!(enabled.iter().any(|tool| tool.name == "shell"));
        assert!(enabled.iter().any(|tool| tool.name == "message_user"));
    }

    #[tokio::test(flavor = "current_thread")]
    async fn max_iterations_limits_turns() {
        let _cwd_guard = setup_temp_cwd();
        let all_tools = tools();
        let mut config = AgentConfig::default_config();
        config.max_iterations = 2;
        let enabled_tools = get_enabled_tools(&config, &all_tools);
        let mut messages = initial_messages(&config, &enabled_tools);
        let provider = MockProvider {
            responses: RefCell::new(vec!["still thinking".to_string(); 5]),
        };

        let result = handle_turn(
            &provider,
            &config,
            &enabled_tools,
            &mut messages,
            "Do work",
            None,
            &[],
        )
        .await
        .unwrap();

        let assistant_messages = messages
            .turns
            .iter()
            .filter(|message| matches!(message, ChatCompletionRequestMessage::Assistant(_)))
            .count();

        assert_eq!(assistant_messages, 2);
        assert_eq!(result.content, "Max iterations reached.");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn tool_filtering_prevents_disabled_tools() {
        let _cwd_guard = setup_temp_cwd();
        let all_tools = tools();
        let mut config = AgentConfig::default_config();
        config.tools = ToolSelection::Only(vec![ToolName::ReadFile]);
        let enabled_tools = get_enabled_tools(&config, &all_tools);
        let mut messages = initial_messages(&config, &enabled_tools);
        let provider = MockProvider {
            responses: RefCell::new(vec![
                r#"<tool_call>{"name":"shell","arguments":{"command":"echo nope"}}</tool_call>"#
                    .to_string(),
                done_msg("done"),
            ]),
        };

        let result = handle_turn(
            &provider,
            &config,
            &enabled_tools,
            &mut messages,
            "Try shell",
            None,
            &[],
        )
        .await
        .unwrap();

        assert_eq!(result.tool_calls, vec!["message_user"]);
        assert!(messages
            .turns
            .iter()
            .any(|message| message_contains(message, "Unknown tool: shell")));
    }
}

mod commands {
    use super::*;

    #[test]
    fn command_detection_works() {
        assert!(is_command("/quit"));
        assert!(is_command("/compact"));
        assert!(!is_command("hello"));
    }

    #[tokio::test(flavor = "current_thread")]
    async fn handle_command_quit_and_exit_return_quit() {
        let provider = MockProvider {
            responses: RefCell::new(vec![]),
        };
        let config = AgentConfig::default_config();
        let session_id = "session-test";
        let conversation = Conversation::new("sys".to_string());

        let quit = handle_command("/quit", &provider, &conversation, &config, session_id)
            .await
            .unwrap();
        let exit = handle_command("/exit", &provider, &conversation, &config, session_id)
            .await
            .unwrap();

        assert_eq!(quit, CommandResult::Quit);
        assert_eq!(exit, CommandResult::Quit);
    }

    #[tokio::test(flavor = "current_thread")]
    async fn handle_command_greet_is_unknown_without_a_template() {
        let provider = MockProvider {
            responses: RefCell::new(vec![]),
        };
        let config = AgentConfig::default_config();
        let session_id = "session-test";
        let conversation = Conversation::new("sys".to_string());

        let result = handle_command("/greet", &provider, &conversation, &config, session_id)
            .await
            .unwrap();
        assert_eq!(
            result,
            CommandResult::Unknown {
                name: "greet".to_string()
            }
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn handle_command_greet_uses_a_configured_template() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(
            dir.path().join("greet.md"),
            "---\ndescription: Greet someone\n---\nGreet $ARGUMENTS.",
        )
        .unwrap();
        let provider = MockProvider {
            responses: RefCell::new(vec!["Hello, Ada!".to_string()]),
        };
        let mut config = AgentConfig::default_config();
        config.commands_dir = Some(dir.path().to_path_buf());
        let conversation = Conversation::new("sys".to_string());

        let result = handle_command(
            "/greet Ada",
            &provider,
            &conversation,
            &config,
            "session-test",
        )
        .await
        .unwrap();

        assert_eq!(
            result,
            CommandResult::Custom {
                response: "Hello, Ada!".to_string()
            }
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn handle_command_unknown_returns_unknown() {
        let provider = MockProvider {
            responses: RefCell::new(vec![]),
        };
        let config = AgentConfig::default_config();
        let session_id = "session-test";
        let conversation = Conversation::new("sys".to_string());

        let result = handle_command("/foo", &provider, &conversation, &config, session_id)
            .await
            .unwrap();
        assert_eq!(
            result,
            CommandResult::Unknown {
                name: "foo".to_string()
            }
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn handle_command_compact_returns_summary() {
        let provider = MockProvider {
            responses: RefCell::new(vec!["summary output".to_string()]),
        };
        let config = AgentConfig::default_config();
        let session_id = "session-test";
        let mut conversation = Conversation::new("sys".to_string());
        conversation.push(
            ChatCompletionRequestUserMessageArgs::default()
                .content("hello")
                .build()
                .unwrap()
                .into(),
        );

        let result = handle_command("/compact", &provider, &conversation, &config, session_id)
            .await
            .unwrap();
        assert_eq!(
            result,
            CommandResult::Compact {
                summary: "summary output".to_string()
            }
        );
    }

    #[test]
    fn format_conversation_for_compaction_excludes_system_messages() {
        let mut messages = Conversation::new("sys".to_string());
        messages.push(
            ChatCompletionRequestUserMessageArgs::default()
                .content("u1")
                .build()
                .unwrap()
                .into(),
        );
        messages.push(
            ChatCompletionRequestAssistantMessageArgs::default()
                .content("a1")
                .build()
                .unwrap()
                .into(),
        );

        let formatted = format_conversation_for_compaction(&messages);
        assert!(!formatted.contains("system:"));
        assert!(formatted.contains("user: u1"));
        assert!(formatted.contains("assistant: a1"));
    }

    #[test]
    fn apply_compaction_preserves_system_and_replaces_rest() {
        let all_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &all_tools);
        messages.push(
            ChatCompletionRequestUserMessageArgs::default()
                .content("before")
                .build()
                .unwrap()
                .into(),
        );

        apply_compaction(&mut messages, "summary text").unwrap();

        assert_eq!(messages.len(), 3);
        assert!(message_contains(&messages[0], "system"));
        assert!(message_contains(
            &messages[1],
            "[Context from previous conversation]"
        ));
        assert!(message_contains(&messages[1], "summary text"));
        assert!(message_contains(
            &messages[2],
            "Context loaded. How can I help?"
        ));
    }
}

mod protocol {
    use super::*;

    #[test]
    fn input_line_deserializes() {
        let line = r#"{"content": "hello world"}"#;
        let parsed: InputLine = serde_json::from_str(line).unwrap();
        assert_eq!(parsed.content, "hello world");
    }

    #[test]
    fn output_line_serializes() {
        let output = OutputLine {
            content: "done".to_string(),
            tool_calls: vec![ToolName::WriteFile],
        };
        let json = serde_json::to_string(&output).unwrap();
        assert!(json.contains("\"content\":\"done\""));
        assert!(json.contains("\"tool_calls\":[\"write_file\"]"));
    }

    #[test]
    fn parse_file_prompts_splits_on_delimiter() {
        let content = "First prompt\n---\nSecond prompt";
        let prompts = parse_file_prompts(content);
        assert_eq!(prompts, vec!["First prompt", "Second prompt"]);
    }

    #[test]
    fn parse_file_prompts_handles_multiline_prompts() {
        let content = "First line\nSecond line\n---\nThird line\nFourth line";
        let prompts = parse_file_prompts(content);
        assert_eq!(prompts.len(), 2);
        assert_eq!(prompts[0], "First line\nSecond line");
        assert_eq!(prompts[1], "Third line\nFourth line");
    }

    #[test]
    fn parse_file_prompts_does_not_split_when_delimiter_has_whitespace() {
        let content = "First prompt\n  ---   \nSecond prompt";
        let prompts = parse_file_prompts(content);
        assert_eq!(prompts, vec!["First prompt\n  ---   \nSecond prompt"]);
    }

    #[test]
    fn parse_file_prompts_skips_empty_sections() {
        let content = "\n---\n\n---\nValid prompt\n---\n\n";
        let prompts = parse_file_prompts(content);
        assert_eq!(prompts, vec!["Valid prompt"]);
    }

    #[test]
    fn parse_file_prompts_handles_single_prompt() {
        let content = "  Just one prompt with surrounding whitespace  ";
        let prompts = parse_file_prompts(content);
        assert_eq!(prompts, vec!["Just one prompt with surrounding whitespace"]);
    }
}

mod tools {
    use super::*;

    #[test]
    fn write_file_creates_file() {
        let dir = tempfile::tempdir().unwrap();
        let file_path = dir.path().join("test.txt");
        let args = serde_json::json!({"path": file_path.to_str().unwrap(), "content": "hello"});
        let result = execute_write_file(args);
        assert!(result.text().contains("Successfully wrote"));
        assert_eq!(fs::read_to_string(&file_path).unwrap(), "hello");
    }

    #[test]
    fn web_fetch_tool_is_registered_between_list_files_and_message_user() {
        let all_tools = tools();
        let names: Vec<_> = all_tools.iter().map(|tool| tool.name).collect();
        let web_fetch_idx = names
            .iter()
            .position(|name| *name == ToolName::WebFetch)
            .expect("web_fetch should be registered");
        let list_files_idx = names
            .iter()
            .position(|name| *name == ToolName::ListFiles)
            .expect("list_files should be registered");
        let message_user_idx = names
            .iter()
            .position(|name| *name == ToolName::MessageUser)
            .expect("message_user should be registered");

        assert_eq!(web_fetch_idx, list_files_idx + 1);
        assert_eq!(message_user_idx, web_fetch_idx + 1);
    }

    #[test]
    fn web_fetch_params_accept_valid_url() {
        let parsed: Result<WebFetchParams, _> =
            serde_json::from_value(serde_json::json!({"url": "https://example.com"}));
        assert!(parsed.is_ok());
    }

    #[test]
    fn web_fetch_execute_is_callable_with_invalid_args() {
        let all_tools = tools();
        let web_fetch = all_tools
            .iter()
            .find(|tool| tool.name == ToolName::WebFetch)
            .expect("web_fetch should be registered");

        let result = (web_fetch.execute)(serde_json::json!({}));
        assert!(result.is_error());
        assert!(result.text().contains("invalid arguments"));
    }

    // --- generic update_board semantics ---

    fn write_board(file: &Path) {
        fs::write(
            file,
            r#"{"intent":"","currentPhase":"spec","phases":{"spec":{"status":"pending"},"review":{"status":"pending"}}}
"#,
        )
        .unwrap();
    }

    #[test]
    fn update_board_changes_status_and_records_artifact() {
        let dir = tempfile::tempdir().unwrap();
        let board = dir.path().join("board.json");
        write_board(&board);
        let result = execute_update_board(serde_json::json!({
            "path": board.to_str().unwrap(),
            "phase": "review",
            "status": "in_review",
            "artifact": "artifacts/review.md",
        }));
        assert!(!result.is_error(), "{}", result.text());
        let written: serde_json::Value =
            serde_json::from_str(&fs::read_to_string(&board).unwrap()).unwrap();
        assert_eq!(written["currentPhase"], "review");
        assert_eq!(written["phases"]["review"]["status"], "in_review");
        assert_eq!(
            written["phases"]["review"]["artifact"],
            "artifacts/review.md"
        );
    }

    #[test]
    fn update_board_rejects_unknown_phase() {
        let dir = tempfile::tempdir().unwrap();
        let board = dir.path().join("board.json");
        write_board(&board);
        let result = execute_update_board(serde_json::json!({
            "path": board.to_str().unwrap(),
            "phase": "missing",
            "status": "in_progress",
        }));
        assert!(result.is_error());
        assert!(result.text().contains("unknown phase"));
    }

    #[test]
    fn update_board_works_without_optional_artifact() {
        let dir = tempfile::tempdir().unwrap();
        let board = dir.path().join("board.json");
        write_board(&board);
        let result = execute_update_board(serde_json::json!({
            "path": board.to_str().unwrap(),
            "phase": "spec",
            "status": "in_progress",
        }));
        assert!(!result.is_error());
        let written: serde_json::Value =
            serde_json::from_str(&fs::read_to_string(&board).unwrap()).unwrap();
        assert_eq!(written["phases"]["spec"]["status"], "in_progress");
        assert!(written["phases"]["spec"]["artifact"].is_null());
    }

    #[test]
    fn update_board_accepts_null_for_optional_artifact() {
        let dir = tempfile::tempdir().unwrap();
        let board = dir.path().join("board.json");
        write_board(&board);
        let result = execute_update_board(serde_json::json!({
            "path": board.to_str().unwrap(),
            "phase": "spec",
            "status": "queued",
            "artifact": null,
        }));
        assert!(!result.is_error());
        let written: serde_json::Value =
            serde_json::from_str(&fs::read_to_string(&board).unwrap()).unwrap();
        assert_eq!(written["phases"]["spec"]["status"], "queued");
        assert!(written["phases"]["spec"]["artifact"].is_null());
    }
}

mod interactive {
    use super::*;

    #[test]
    fn interactive_eof_detection_matches_eof_and_pipe_errors() {
        assert!(is_interactive_eof(&ReadlineError::Eof));
        assert!(is_interactive_eof(&ReadlineError::Io(io::Error::new(
            ErrorKind::UnexpectedEof,
            "stdin closed",
        ))));
        assert!(is_interactive_eof(&ReadlineError::Io(io::Error::new(
            ErrorKind::BrokenPipe,
            "broken pipe",
        ))));
    }

    #[test]
    fn interactive_eof_detection_does_not_match_non_eof_errors() {
        assert!(!is_interactive_eof(&ReadlineError::Interrupted));
        assert!(!is_interactive_eof(&ReadlineError::Io(io::Error::other(
            "other",
        ))));
    }
}

mod terminal {
    use super::*;

    #[test]
    fn recording_terminal_captures_all_event_types() {
        let term = RecordingTerminal::new();
        term.banner("model", &[ToolName::ReadFile]);
        term.thinking("thought");
        term.tool_call(&ValidatedToolCall {
            name: ToolName::ReadFile,
            arguments: serde_json::json!({"path": "f.txt"}),
        });
        term.tool_result(&ToolResult::Ok("ok".to_string()));
        term.answer("done");
        term.error("oops");
        term.spinner_start();
        term.spinner_stop();
        term.goodbye();

        let events = term.events();
        assert_eq!(events.len(), 9);
        assert!(matches!(events[0], TerminalEvent::Banner { .. }));
        assert!(matches!(events[8], TerminalEvent::Goodbye));
    }

    #[test]
    fn recording_terminal_prompt_string() {
        let term = RecordingTerminal::new();
        assert_eq!(term.prompt_string(), "> ");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn terminal_records_spinner_lifecycle() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);
        let term = RecordingTerminal::new();
        let provider = MockProvider {
            responses: RefCell::new(vec![done_msg("hi")]),
        };

        handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "hello",
            Some(&term),
            &[],
        )
        .await
        .unwrap();

        let events = term.events();
        let starts = events
            .iter()
            .filter(|e| matches!(e, TerminalEvent::SpinnerStart))
            .count();
        let stops = events
            .iter()
            .filter(|e| matches!(e, TerminalEvent::SpinnerStop))
            .count();
        assert!(starts >= 1, "should start spinner at least once");
        assert!(stops >= 1, "should stop spinner at least once");
        assert_eq!(starts, stops, "spinner start/stop should be balanced");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn terminal_records_thinking() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);
        let term = RecordingTerminal::new();
        let provider = MockProvider {
            responses: RefCell::new(vec![format!(
                "THINKING: I need to analyze this.\nACTION: respond\n{}",
                done_msg("Analyzed.")
            )]),
        };

        handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Analyze",
            Some(&term),
            &[],
        )
        .await
        .unwrap();

        let thinking_events: Vec<_> = term
            .events()
            .into_iter()
            .filter(|e| matches!(e, TerminalEvent::Thinking(_)))
            .collect();
        assert!(!thinking_events.is_empty(), "should record thinking");
        if let TerminalEvent::Thinking(text) = &thinking_events[0] {
            assert!(
                text.to_lowercase().contains("analyze"),
                "thinking text should contain 'analyze', got: {}",
                text
            );
        }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn terminal_records_tool_call_and_result() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);
        let term = RecordingTerminal::new();
        let provider = MockProvider {
            responses: RefCell::new(vec![
                r#"<tool_call>{"name":"write_file","arguments":{"path":"rec.txt","content":"data"}}</tool_call>"#.to_string(),
                done_msg("Written."),
            ]),
        };

        handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Write rec.txt",
            Some(&term),
            &[],
        )
        .await
        .unwrap();

        let events = term.events();
        let tool_calls: Vec<_> = events
            .iter()
            .filter(|e| matches!(e, TerminalEvent::ToolCall { .. }))
            .collect();
        assert_eq!(tool_calls.len(), 1);
        if let TerminalEvent::ToolCall { name, args } = &tool_calls[0] {
            assert_eq!(*name, ToolName::WriteFile);
            let expected_path = std::env::current_dir().unwrap().join("rec.txt");
            assert_eq!(args["path"], expected_path.to_string_lossy().to_string());
        }

        let tool_results: Vec<_> = events
            .iter()
            .filter(|e| matches!(e, TerminalEvent::ToolResult { .. }))
            .collect();
        assert_eq!(tool_results.len(), 1);
        if let TerminalEvent::ToolResult { result } = &tool_results[0] {
            assert!(result.text().contains("Successfully wrote"));
            assert!(!result.is_error());
        }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn terminal_records_answer() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);
        let term = RecordingTerminal::new();
        let provider = MockProvider {
            responses: RefCell::new(vec![done_msg("Final answer here.")]),
        };

        handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "question",
            Some(&term),
            &[],
        )
        .await
        .unwrap();

        let answers: Vec<_> = term
            .events()
            .into_iter()
            .filter(|e| matches!(e, TerminalEvent::Answer(_)))
            .collect();
        assert_eq!(answers.len(), 1);
        if let TerminalEvent::Answer(text) = &answers[0] {
            assert_eq!(text, "Final answer here.");
        }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn terminal_records_error_for_unknown_tool() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);
        let term = RecordingTerminal::new();
        let provider = MockProvider {
            responses: RefCell::new(vec![
                r#"<tool_call>{"name":"nope","arguments":{}}</tool_call>"#.to_string(),
                done_msg("ok"),
            ]),
        };

        handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "try nope",
            Some(&term),
            &[],
        )
        .await
        .unwrap();

        let errors: Vec<_> = term
            .events()
            .into_iter()
            .filter(|e| matches!(e, TerminalEvent::Error(_)))
            .collect();
        assert!(!errors.is_empty(), "should record error for unknown tool");
        if let TerminalEvent::Error(text) = &errors[0] {
            assert!(text.contains("Unknown tool"));
        }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn terminal_records_error_on_max_iterations() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);
        let term = RecordingTerminal::new();
        let provider = MockProvider {
            responses: RefCell::new(vec!["Still thinking...".to_string(); 25]),
        };

        handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Infinite",
            Some(&term),
            &[],
        )
        .await
        .unwrap();

        let errors: Vec<_> = term
            .events()
            .into_iter()
            .filter(|e| matches!(e, TerminalEvent::Error(_)))
            .collect();
        assert!(
            errors.iter().any(|e| {
                if let TerminalEvent::Error(text) = e {
                    text.contains("Max iterations")
                } else {
                    false
                }
            }),
            "should record max iterations error"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn terminal_records_error_result_for_failed_tool() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);
        let term = RecordingTerminal::new();
        let provider = MockProvider {
            responses: RefCell::new(vec![
                r#"<tool_call>{"name":"read_file","arguments":{"path":"/nonexistent/file.txt"}}</tool_call>"#.to_string(),
                done_msg("Failed."),
            ]),
        };

        handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Read missing file",
            Some(&term),
            &[],
        )
        .await
        .unwrap();

        let tool_results: Vec<_> = term
            .events()
            .into_iter()
            .filter(|e| matches!(e, TerminalEvent::ToolResult { .. }))
            .collect();
        assert_eq!(tool_results.len(), 1);
        if let TerminalEvent::ToolResult { result } = &tool_results[0] {
            assert!(result.is_error(), "should be flagged as error");
            assert!(
                result.text().contains("reading file"),
                "result text should contain underlying error message"
            );
        }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn terminal_does_not_record_nudge_error_without_tool_call() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);
        let term = RecordingTerminal::new();
        let provider = MockProvider {
            responses: RefCell::new(vec!["thinking".to_string(), done_msg("done")]),
        };

        handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "do something",
            Some(&term),
            &[],
        )
        .await
        .unwrap();

        let nudge_errors: Vec<_> = term
            .events()
            .into_iter()
            .filter_map(|e| match e {
                TerminalEvent::Error(text)
                    if text.contains(
                        "You must call a tool. Use message_user to deliver your final response.",
                    ) =>
                {
                    Some(text)
                }
                _ => None,
            })
            .collect();

        assert!(
            nudge_errors.is_empty(),
            "nudge should be conversation-only, not terminal error"
        );
    }
}
