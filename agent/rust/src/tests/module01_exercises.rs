use super::*;

mod exercise01_system_prompt {
    use super::*;

    #[test]
    fn system_prompt_contains_tool_names() {
        let all_tools = tools();
        let config = AgentConfig::default_config();
        let prompt = build_system_prompt(&config, &all_tools, false);
        assert!(prompt.contains("read_file"));
        assert!(prompt.contains("write_file"));
        assert!(prompt.contains("edit_file"));
        assert!(prompt.contains("shell"));
        assert!(prompt.contains("message_user"));
    }

    #[test]
    fn system_prompt_contains_protocol() {
        let all_tools = tools();
        let config = AgentConfig::default_config();
        let prompt = build_system_prompt(&config, &all_tools, false);
        assert!(prompt.contains("<tool_call>"));
        assert!(prompt.contains("</tool_call>"));
        assert!(prompt.contains("message_user"));
    }

    #[test]
    fn system_prompt_contains_schemas() {
        let all_tools = tools();
        let config = AgentConfig::default_config();
        let prompt = build_system_prompt(&config, &all_tools, false);
        assert!(prompt.contains("```json"));
        assert!(prompt.contains("\"path\""));
        assert!(prompt.contains("\"command\""));
    }
}

mod exercise02_parse_tool_calls {
    use super::*;

    #[test]
    fn parse_single_tool_call() {
        let text = r#"Let me read that.
<tool_call>
{"name": "read_file", "arguments": {"path": "foo.txt"}}
</tool_call>"#;
        let result = parse_tool_calls(text);
        assert_eq!(result.calls.len(), 1);
        assert_eq!(result.calls[0].name, "read_file");
        assert_eq!(result.calls[0].arguments["path"], "foo.txt");
    }

    #[test]
    fn parse_multiple_tool_calls() {
        let text = r#"<tool_call>{"name": "read_file", "arguments": {"path": "a.txt"}}</tool_call> then <tool_call>{"name": "shell", "arguments": {"command": "ls"}}</tool_call>"#;
        let result = parse_tool_calls(text);
        assert_eq!(result.calls.len(), 2);
        assert_eq!(result.calls[0].name, "read_file");
        assert_eq!(result.calls[1].name, "shell");
    }

    #[test]
    fn parse_no_tool_calls() {
        let result = parse_tool_calls("Just some commentary.");
        assert!(result.calls.is_empty());
        assert!(result.errors.is_empty());
    }

    #[test]
    fn parse_reports_malformed_json() {
        let text = "<tool_call>this is not json</tool_call>";
        let result = parse_tool_calls(text);
        assert!(result.calls.is_empty());
        assert_eq!(result.errors.len(), 1);
        assert!(result.errors[0].contains("Malformed"));
    }

    #[test]
    fn parse_reports_missing_name() {
        let text = r#"<tool_call>{"arguments": {"path": "foo.txt"}}</tool_call>"#;
        let result = parse_tool_calls(text);
        assert!(result.calls.is_empty());
        assert_eq!(result.errors.len(), 1);
        assert!(result.errors[0].contains("missing"));
    }

    #[test]
    fn parse_reports_empty_name() {
        let text = r#"<tool_call>{"name": "", "arguments": {"path": "foo.txt"}}</tool_call>"#;
        let result = parse_tool_calls(text);
        assert!(result.calls.is_empty());
        assert_eq!(result.errors.len(), 1);
        assert!(result.errors[0].contains("empty"));
    }

    #[test]
    fn parse_extracts_valid_and_reports_broken() {
        let text = r#"<tool_call>broken</tool_call><tool_call>{"name": "shell", "arguments": {"command": "pwd"}}</tool_call>"#;
        let result = parse_tool_calls(text);
        assert_eq!(result.calls.len(), 1);
        assert_eq!(result.calls[0].name, "shell");
        assert_eq!(result.errors.len(), 1);
        assert!(result.errors[0].contains("Malformed"));
    }
}

mod exercise03_agent_loop {
    use super::*;

    #[tokio::test(flavor = "current_thread")]
    async fn executes_a_tool_call_and_completes() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);
        let provider = MockProvider {
            responses: RefCell::new(vec![
                r#"<tool_call>{"name":"write_file","arguments":{"path":"note.txt","content":"hello"}}</tool_call>"#.to_string(),
                done_msg("done"),
            ]),
        };

        let result = handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Create note.txt",
            None,
            &[],
        )
        .await
        .unwrap();

        assert_eq!(fs::read_to_string("note.txt").unwrap(), "hello");
        assert_eq!(result.tool_calls, vec!["write_file", "message_user"]);
        assert_eq!(
            result.tool_calls.last(),
            Some(&ToolName::MessageUser),
            "message_user should be the terminating tool in tool_calls"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn ignores_text_after_tool_call_and_still_executes_tool() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);
        let provider = MockProvider {
            responses: RefCell::new(vec![
                r#"Doing it now <tool_call>{"name":"write_file","arguments":{"path":"first.txt","content":"created"}}</tool_call> trailing narrative"#.to_string(),
                done_msg("done"),
            ]),
        };

        handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Create first.txt",
            None,
            &[],
        )
        .await
        .unwrap();

        assert_eq!(fs::read_to_string("first.txt").unwrap(), "created");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn nudges_on_empty_response() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);
        let provider = MockProvider {
            responses: RefCell::new(vec![
                "Thinking...".to_string(),
                "Still thinking...".to_string(),
                done_msg("Finished."),
            ]),
        };

        let _ = handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Do the task",
            None,
            &[],
        )
        .await
        .unwrap();

        let nudge_count = messages
            .turns
            .iter()
            .filter(|message| {
                message_contains(
                    message,
                    "You must call a tool. Use message_user to deliver your final response.",
                )
            })
            .count();
        assert!(nudge_count >= 2);
    }

    #[tokio::test(flavor = "current_thread")]
    async fn only_executes_first_tool_call_in_single_response() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);
        let provider = MockProvider {
            responses: RefCell::new(vec![
                r#"<tool_call>{"name":"write_file","arguments":{"path":"a.txt","content":"A"}}</tool_call>
<tool_call>{"name":"write_file","arguments":{"path":"b.txt","content":"B"}}</tool_call>"#.to_string(),
                done_msg("done"),
            ]),
        };

        let result = handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Create two files",
            None,
            &[],
        )
        .await
        .unwrap();

        assert_eq!(fs::read_to_string("a.txt").unwrap(), "A");
        assert!(!std::path::Path::new("b.txt").exists());
        assert_eq!(result.tool_calls, vec!["write_file", "message_user"]);
    }

    #[tokio::test(flavor = "current_thread")]
    async fn handles_read_after_write() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);
        let provider = MockProvider {
            responses: RefCell::new(vec![
                r#"<tool_call>{"name":"write_file","arguments":{"path":"story.txt","content":"Once"}}</tool_call>"#.to_string(),
                r#"<tool_call>{"name":"read_file","arguments":{"path":"story.txt"}}</tool_call>"#.to_string(),
                done_msg("done"),
            ]),
        };

        let result = handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Write then read",
            None,
            &[],
        )
        .await
        .unwrap();

        assert_eq!(
            result.tool_calls,
            vec!["write_file", "read_file", "message_user"]
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn reports_unknown_tool() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);
        let provider = MockProvider {
            responses: RefCell::new(vec![
                r#"<tool_call>{"name":"delete_file","arguments":{"path":"ghost.txt"}}</tool_call>"#
                    .to_string(),
                done_msg("done"),
            ]),
        };

        let result = handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Delete file",
            None,
            &[],
        )
        .await
        .unwrap();

        assert_eq!(result.tool_calls, vec!["message_user"]);
        assert!(messages
            .turns
            .iter()
            .any(|message| message_contains(message, "Unknown tool: delete_file")));
    }

    #[tokio::test(flavor = "current_thread")]
    async fn returns_max_iterations() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);
        let provider = MockProvider {
            responses: RefCell::new(vec!["Still working...".to_string(); 25]),
        };

        let result = handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Keep going",
            None,
            &[],
        )
        .await
        .unwrap();

        assert_eq!(result.content, "Max iterations reached.");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn forces_another_iteration_after_tool_calls() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);

        struct SeqProvider {
            count: std::cell::Cell<u32>,
        }
        #[async_trait::async_trait(?Send)]
        impl ChatProvider for SeqProvider {
            async fn complete(&self, messages: &[ChatCompletionRequestMessage]) -> Result<String> {
                let n = self.count.get() + 1;
                self.count.set(n);
                if n == 1 {
                    return Ok(
                        r#"<tool_call>{"name":"shell","arguments":{"command":"echo real-output"}}</tool_call>
The output was fake-output."#
                            .to_string(),
                    );
                }
                let last_user = messages
                    .iter()
                    .rev()
                    .find_map(|m| match m {
                        ChatCompletionRequestMessage::User(u) => Some(format!("{:?}", u.content)),
                        _ => None,
                    })
                    .unwrap_or_default();
                if last_user.contains("real-output") {
                    Ok(done_msg("The output was real-output."))
                } else {
                    Ok(done_msg("Something went wrong."))
                }
            }
        }

        let provider = SeqProvider {
            count: std::cell::Cell::new(0),
        };
        let result = handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Run echo",
            None,
            &[],
        )
        .await
        .unwrap();

        assert!(
            provider.count.get() >= 2,
            "model should get a second turn, got {} calls",
            provider.count.get()
        );
        assert!(
            result.content.contains("real-output"),
            "final response should reflect actual tool output, got: {}",
            result.content
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn feeds_malformed_json_parse_errors_back_to_model() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);

        struct SeqProvider {
            count: std::cell::Cell<u32>,
        }

        #[async_trait::async_trait(?Send)]
        impl ChatProvider for SeqProvider {
            async fn complete(&self, _messages: &[ChatCompletionRequestMessage]) -> Result<String> {
                let n = self.count.get() + 1;
                self.count.set(n);
                if n == 1 {
                    Ok("<tool_call>this is broken json</tool_call>".to_string())
                } else {
                    Ok(done_msg("Fixed it."))
                }
            }
        }

        let provider = SeqProvider {
            count: std::cell::Cell::new(0),
        };

        let _ = handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Do stuff",
            None,
            &[],
        )
        .await
        .unwrap();

        assert!(
            provider.count.get() >= 2,
            "model should get a second chance after parse error"
        );

        assert!(
            messages
                .turns
                .iter()
                .any(|message| message_contains(message, "Tool call parse error")),
            "parse error should be fed back to the model"
        );
        assert!(
            messages
                .turns
                .iter()
                .any(|message| message_contains(message, "Malformed")),
            "parse error message should include 'Malformed'"
        );
        assert!(
            messages
                .turns
                .iter()
                .any(|message| message_contains(message, "fix the JSON")),
            "parse error message should include 'fix the JSON'"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn retries_when_message_user_arguments_are_invalid() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);

        struct SeqProvider {
            count: std::cell::Cell<u32>,
        }

        #[async_trait::async_trait(?Send)]
        impl ChatProvider for SeqProvider {
            async fn complete(&self, _messages: &[ChatCompletionRequestMessage]) -> Result<String> {
                let n = self.count.get() + 1;
                self.count.set(n);
                if n == 1 {
                    Ok(r#"<tool_call>{"name":"message_user","arguments":{"msg":"bad"}}</tool_call>"#.to_string())
                } else {
                    Ok(done_msg("Recovered."))
                }
            }
        }

        let provider = SeqProvider {
            count: std::cell::Cell::new(0),
        };

        let result = handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Do stuff",
            None,
            &[],
        )
        .await
        .unwrap();

        assert_eq!(result.content, "Recovered.");
        assert!(
            provider.count.get() >= 2,
            "model should get a second chance after invalid message_user args"
        );
        assert!(
            messages
                .turns
                .iter()
                .any(|message| { message_contains(message, "Invalid arguments for message_user") }),
            "invalid message_user args should be fed back as parse error"
        );
    }
}

mod exercise04_read_file {
    use super::*;

    #[test]
    fn read_file_reads_existing() {
        let dir = tempfile::tempdir().unwrap();
        let file_path = dir.path().join("test.txt");
        fs::write(&file_path, "contents here").unwrap();
        let args = serde_json::json!({"path": file_path.to_str().unwrap()});
        let result = execute_read_file(args);
        assert_eq!(result.text(), "contents here");
    }

    #[test]
    fn read_file_error_on_missing() {
        let args = serde_json::json!({"path": "/nonexistent/path.txt"});
        let result = execute_read_file(args);
        assert!(result.is_error());
        assert!(result.text().starts_with("reading file"));
    }

    #[test]
    fn read_file_invalid_arguments_error() {
        let result = execute_read_file(serde_json::json!({}));
        assert!(result.is_error());
        assert!(result.text().contains("invalid arguments:"));
    }
}

mod exercise05_shell {
    use super::*;

    #[test]
    fn shell_runs_command() {
        let args = serde_json::json!({"command": "echo hello"});
        let result = execute_shell(args, Path::new("."));
        assert_eq!(result.text().trim(), "hello");
    }

    #[test]
    fn shell_returns_error_on_failure() {
        let args = serde_json::json!({"command": "false"});
        let result = execute_shell(args, Path::new("."));
        assert!(result.is_error());
    }

    #[test]
    fn shell_caps_stdout_to_1mb() {
        let stream = vec![b'a'; SHELL_OUTPUT_CAP_BYTES + 128];
        let capped = cap_shell_stream(&stream);
        assert_eq!(capped.len(), SHELL_OUTPUT_CAP_BYTES);
    }

    #[test]
    fn shell_invalid_arguments_error() {
        let result = execute_shell(serde_json::json!({}), Path::new("."));
        assert!(result.is_error());
        assert!(result.text().contains("invalid arguments:"));
    }

    #[test]
    fn shell_drains_large_stdout_and_stderr_concurrently() {
        let result = execute_shell(
            serde_json::json!({"command": "(yes o | head -c 200000) & (yes e | head -c 200000 >&2) & wait"}),
            Path::new("."),
        );
        assert!(!result.is_error());
        assert_eq!(result.text().len(), 200000);
    }
}

mod exercise06_system_prompt {
    use super::*;

    #[test]
    fn gold_prompt_mentions_one_tool_call_per_turn() {
        let config = AgentConfig::default_config();
        let prompt = build_system_prompt(&config, &tools(), false);
        let lower = prompt.to_lowercase();
        assert!(
            lower.contains("one tool")
                || lower.contains("single tool")
                || lower.contains("one action"),
            "prompt should instruct one tool call per turn"
        );
    }

    #[test]
    fn gold_prompt_warns_against_predicting_results() {
        let config = AgentConfig::default_config();
        let prompt = build_system_prompt(&config, &tools(), false);
        let lower = prompt.to_lowercase();
        assert!(
            lower.contains("never predict")
                || lower.contains("not seen the output")
                || lower.contains("do not guess"),
            "prompt should warn against predicting results"
        );
    }

    #[test]
    fn gold_prompt_mentions_multi_turn_loop() {
        let config = AgentConfig::default_config();
        let prompt = build_system_prompt(&config, &tools(), false);
        let lower = prompt.to_lowercase();
        assert!(
            lower.contains("multi-turn")
                || lower.contains("iteration")
                || lower.contains("loop")
                || lower.contains("another turn"),
            "prompt should mention multi-turn loop"
        );
    }

    #[test]
    fn naive_prompt_lacks_defensive_clauses() {
        let naive = NAIVE_SYSTEM_PROMPT;
        let lower = naive.to_lowercase();
        assert!(
            !lower.contains("never predict")
                && !lower.contains("one tool call")
                && !lower.contains("multi-turn"),
            "naive prompt should lack defensive clauses"
        );
    }
}

mod exercise07_harden_loop {
    use super::*;

    #[test]
    fn parse_handles_flat_format() {
        let text =
            r#"<tool_call>{"name": "write_file", "path": "foo.txt", "content": "bar"}</tool_call>"#;
        let result = parse_tool_calls(text);
        assert_eq!(result.calls.len(), 1);
        assert_eq!(result.calls[0].name, "write_file");
        assert_eq!(result.calls[0].arguments["path"], "foo.txt");
        assert_eq!(result.calls[0].arguments["content"], "bar");
    }

    #[test]
    fn truncate_output_passes_short_text() {
        let output = "short output";
        assert_eq!(truncate_output(output), output);
    }

    #[test]
    fn truncate_output_truncates_long_text() {
        let long = "a".repeat(MAX_OUTPUT_CHARS + 15);
        let truncated = truncate_output(&long);
        assert!(truncated.starts_with(&"a".repeat(MAX_OUTPUT_CHARS)));
        assert!(truncated.contains("[truncated — 15 more chars]"));
    }

    #[tokio::test(flavor = "current_thread")]
    async fn truncates_large_tool_output() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);

        // Write a huge file
        let huge_content = "x".repeat(MAX_OUTPUT_CHARS + 5000);
        fs::write("huge.txt", &huge_content).unwrap();

        let provider = MockProvider {
            responses: RefCell::new(vec![
                r#"<tool_call>{"name":"read_file","arguments":{"path":"huge.txt"}}</tool_call>"#
                    .to_string(),
                done_msg("done"),
            ]),
        };

        let _ = handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Read the huge file",
            None,
            &[],
        )
        .await
        .unwrap();

        // Check that the conversation contains the truncation marker
        assert!(messages
            .turns
            .iter()
            .any(|message| message_contains(message, "[truncated")));
    }

    #[tokio::test(flavor = "current_thread")]
    async fn truncates_assistant_message_history_at_first_tool_call() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);
        let provider = MockProvider {
            responses: RefCell::new(vec![
                r#"<tool_call>{"name":"write_file","arguments":{"path":"first.txt","content":"first"}}</tool_call>Extra text<tool_call>{"name":"write_file","arguments":{"path":"second.txt","content":"second"}}</tool_call>"#.to_string(),
                done_msg("Done."),
            ]),
        };

        let result = handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Create two files",
            None,
            &[],
        )
        .await
        .unwrap();

        // Assert that only the first tool call was executed
        assert_eq!(result.tool_calls, vec!["write_file", "message_user"]);
        assert!(std::path::Path::new("first.txt").exists());
        assert!(!std::path::Path::new("second.txt").exists());

        // Check that all assistant messages contain at most one <tool_call> tag
        for message in &messages {
            let serialized = serde_json::to_string(message).unwrap_or_default();
            let tool_call_count = serialized.matches("<tool_call>").count();
            assert!(
                tool_call_count <= 1,
                "Assistant message contains {} <tool_call> tags, expected at most 1. Message: {}",
                tool_call_count,
                serialized
            );
        }

        // Check that no assistant message contains "Extra text"
        for message in &messages {
            assert!(
                !message_contains(message, "Extra text"),
                "Assistant message should not contain 'Extra text' (it was after first </tool_call>)"
            );
        }
    }
}

mod exercise09_sudoku {
    use super::*;

    const VALID_GRID: [[u8; 9]; 9] = [
        [5, 3, 4, 6, 7, 8, 9, 1, 2],
        [6, 7, 2, 1, 9, 5, 3, 4, 8],
        [1, 9, 8, 3, 4, 2, 5, 6, 7],
        [8, 5, 9, 7, 6, 1, 4, 2, 3],
        [4, 2, 6, 8, 5, 3, 7, 9, 1],
        [7, 1, 3, 9, 2, 4, 8, 5, 6],
        [9, 6, 1, 5, 3, 7, 2, 8, 4],
        [2, 8, 7, 4, 1, 9, 6, 3, 5],
        [3, 4, 5, 2, 8, 6, 1, 7, 9],
    ];

    #[test]
    fn valid_grid_returns_empty() {
        assert!(verify_sudoku(&VALID_GRID).is_empty());
    }

    #[test]
    fn detects_row_violation() {
        let mut bad = VALID_GRID;
        bad[0][0] = bad[0][1];
        let errors = verify_sudoku(&bad);
        assert!(errors.iter().any(|e| e.starts_with("Row 1")));
    }

    #[test]
    fn detects_col_violation() {
        let mut bad = VALID_GRID;
        bad[0][0] = bad[1][0];
        let errors = verify_sudoku(&bad);
        assert!(errors.iter().any(|e| e.starts_with("Col 1")));
    }

    #[test]
    fn detects_box_violation() {
        let mut bad = VALID_GRID;
        bad[0][0] = bad[1][1];
        let errors = verify_sudoku(&bad);
        assert!(errors.iter().any(|e| e.starts_with("Box (1,1)")));
    }

    #[test]
    fn parses_space_separated_grid() {
        let text = VALID_GRID
            .iter()
            .map(|r| {
                r.iter()
                    .map(|n| n.to_string())
                    .collect::<Vec<_>>()
                    .join(" ")
            })
            .collect::<Vec<_>>()
            .join("\n");
        let grid = parse_sudoku_grid(&text);
        assert_eq!(grid, Some(VALID_GRID));
    }

    #[test]
    fn parse_returns_none_for_incomplete() {
        assert_eq!(parse_sudoku_grid("1 2 3\n4 5 6"), None);
    }
}

mod exercise10_fabrication {
    use super::*;
    use std::cell::RefCell;

    #[derive(Debug)]
    struct CapturingJudgeProvider {
        responses: RefCell<Vec<String>>,
        captured_messages: RefCell<Vec<Vec<ChatCompletionRequestMessage>>>,
    }

    #[async_trait::async_trait(?Send)]
    impl ChatProvider for CapturingJudgeProvider {
        async fn complete(&self, messages: &[ChatCompletionRequestMessage]) -> Result<String> {
            self.captured_messages.borrow_mut().push(messages.to_vec());
            let mut responses = self.responses.borrow_mut();
            if responses.is_empty() {
                Ok("SPECIFIED".to_string())
            } else {
                Ok(responses.remove(0))
            }
        }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn returns_specified_when_judge_says_specified() {
        let provider = MockProvider {
            responses: RefCell::new(vec!["SPECIFIED".into()]),
        };
        let result = evaluate_response(&provider, "here are 5 records")
            .await
            .unwrap();
        assert_eq!(result, "SPECIFIED");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn returns_unspecified_when_judge_says_unspecified() {
        let provider = MockProvider {
            responses: RefCell::new(vec!["UNSPECIFIED".into()]),
        };
        let result = evaluate_response(&provider, "I can't access that")
            .await
            .unwrap();
        assert_eq!(result, "UNSPECIFIED");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn handles_case_insensitive_verdict() {
        let provider = MockProvider {
            responses: RefCell::new(vec!["  specified  ".into()]),
        };
        let result = evaluate_response(&provider, "fake data").await.unwrap();
        assert_eq!(result, "SPECIFIED");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn sends_the_agent_response_in_the_judge_prompt() {
        let provider = CapturingJudgeProvider {
            responses: RefCell::new(vec!["SPECIFIED".to_string()]),
            captured_messages: RefCell::new(Vec::new()),
        };

        evaluate_response(&provider, "AGENT_OUTPUT_MARKER")
            .await
            .unwrap();

        let captured_messages = provider.captured_messages.borrow();
        assert_eq!(captured_messages.len(), 1);
        assert_eq!(captured_messages[0].len(), 1);

        let prompt = match &captured_messages[0][0] {
            ChatCompletionRequestMessage::User(user) => match &user.content {
                ChatCompletionRequestUserMessageContent::Text(text) => text.clone(),
                _ => String::new(),
            },
            _ => String::new(),
        };

        assert!(prompt.contains("AGENT_OUTPUT_MARKER"));
    }
}

mod exercise11_edit_file {
    use super::*;

    #[test]
    fn edit_file_replaces_text_in_range() {
        let dir = tempfile::tempdir().unwrap();
        let file_path = dir.path().join("edit.txt");
        fs::write(&file_path, "line1\nline2 old\nline3 old\nline4").unwrap();

        let args = serde_json::json!({
            "path": file_path.to_str().unwrap(),
            "line_start": 2,
            "line_end": 3,
            "old_text": "line2 old\nline3 old",
            "new_text": "line2 new\nline3 new"
        });

        let result = execute_edit_file(args);
        assert!(result.text().starts_with("Successfully edited"));
        assert_eq!(
            fs::read_to_string(&file_path).unwrap(),
            "line1\nline2 new\nline3 new\nline4"
        );
    }

    #[test]
    fn edit_file_rejects_invalid_range() {
        let dir = tempfile::tempdir().unwrap();
        let file_path = dir.path().join("edit.txt");
        fs::write(&file_path, "one\ntwo").unwrap();

        let args = serde_json::json!({
            "path": file_path.to_str().unwrap(),
            "line_start": 5,
            "line_end": 10,
            "old_text": "one",
            "new_text": "ONE"
        });

        let result = execute_edit_file(args);
        assert!(result.is_error());
        assert!(result.text().contains("line_start 5 is out of range"));
    }

    #[test]
    fn edit_file_rejects_line_end_beyond_file_length() {
        let dir = tempfile::tempdir().unwrap();
        let file_path = dir.path().join("edit.txt");
        fs::write(&file_path, "one\ntwo\n").unwrap();

        let args = serde_json::json!({
            "path": file_path.to_str().unwrap(),
            "line_start": 1,
            "line_end": 10,
            "old_text": "one",
            "new_text": "ONE"
        });

        let result = execute_edit_file(args);
        assert!(result.is_error());
        assert!(result.text().contains("line_end 10 is out of range"));
    }

    #[test]
    fn edit_file_leaves_file_unmodified_when_not_found() {
        let dir = tempfile::tempdir().unwrap();
        let file_path = dir.path().join("edit.txt");
        let original = "alpha\nbeta\ngamma";
        fs::write(&file_path, original).unwrap();

        let args = serde_json::json!({
            "path": file_path.to_str().unwrap(),
            "line_start": 1,
            "line_end": 2,
            "old_text": "does-not-exist",
            "new_text": "new"
        });

        let result = execute_edit_file(args);
        assert!(result.is_error());
        assert!(result.text().contains("old_text not found"));
        assert_eq!(fs::read_to_string(&file_path).unwrap(), original);
    }

    #[test]
    fn edit_file_handles_empty_content() {
        let dir = tempfile::tempdir().unwrap();
        let file_path = dir.path().join("edit-empty.txt");
        fs::write(&file_path, "").unwrap();

        let args = serde_json::json!({
            "path": file_path.to_str().unwrap(),
            "line_start": 1,
            "line_end": 1,
            "old_text": "",
            "new_text": "inserted"
        });

        let result = execute_edit_file(args);
        assert!(!result.is_error());
        assert_eq!(fs::read_to_string(&file_path).unwrap(), "inserted");
    }

    #[test]
    fn edit_file_replaces_only_first_of_multiple_matches() {
        let dir = tempfile::tempdir().unwrap();
        let file_path = dir.path().join("edit-multiple.txt");
        fs::write(&file_path, "foo foo foo\n").unwrap();

        let args = serde_json::json!({
            "path": file_path.to_str().unwrap(),
            "line_start": 1,
            "line_end": 1,
            "old_text": "foo",
            "new_text": "bar"
        });

        let result = execute_edit_file(args);
        assert!(!result.is_error());
        assert_eq!(fs::read_to_string(&file_path).unwrap(), "bar foo foo\n");
    }

    #[test]
    fn edit_file_inserts_at_line_when_old_text_empty() {
        let dir = tempfile::tempdir().unwrap();
        let file_path = dir.path().join("edit-insert.txt");
        fs::write(&file_path, "beta\ngamma\n").unwrap();

        let args = serde_json::json!({
            "path": file_path.to_str().unwrap(),
            "line_start": 1,
            "line_end": 1,
            "old_text": "",
            "new_text": "alpha\n"
        });

        let result = execute_edit_file(args);
        assert!(!result.is_error());
        assert_eq!(
            fs::read_to_string(&file_path).unwrap(),
            "alpha\nbeta\ngamma\n"
        );
    }

    #[test]
    fn edit_file_invalid_arguments_error() {
        let result = execute_edit_file(serde_json::json!({}));
        assert!(result.is_error());
        assert!(result.text().contains("invalid arguments:"));
    }

    #[tokio::test(flavor = "current_thread")]
    async fn edit_file_through_loop() {
        let _cwd_guard = setup_temp_cwd();
        let available_tools = tools();
        let config = AgentConfig::default_config();
        let mut messages = initial_messages(&config, &available_tools);

        // Write initial file with content to be edited
        fs::write("edit-target.txt", "line1\nold_value\nline3\n").unwrap();

        let provider = MockProvider {
            responses: RefCell::new(vec![
                r#"<tool_call>{"name":"edit_file","arguments":{"path":"edit-target.txt","line_start":2,"line_end":2,"old_text":"old_value","new_text":"new_value"}}</tool_call>"#.to_string(),
                done_msg("done"),
            ]),
        };

        let result = handle_turn(
            &provider,
            &config,
            &available_tools,
            &mut messages,
            "Edit the file",
            None,
            &[],
        )
        .await
        .unwrap();

        assert!(result.tool_calls.contains(&ToolName::EditFile));
        assert_eq!(
            fs::read_to_string("edit-target.txt").unwrap(),
            "line1\nnew_value\nline3\n"
        );
    }
}

mod exercise12_list_files {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn lists_immediate_children_at_depth_1() {
        let dir = tempdir().unwrap();
        fs::write(dir.path().join("a.txt"), "a").unwrap();
        fs::write(dir.path().join("b.txt"), "b").unwrap();
        fs::create_dir(dir.path().join("sub")).unwrap();
        fs::write(dir.path().join("sub").join("c.txt"), "c").unwrap();

        let result = execute_list_files(serde_json::json!({
            "path": dir.path().to_str().unwrap(),
            "max_depth": 1
        }));
        let output = result.text();
        assert!(output.contains("a.txt"));
        assert!(output.contains("b.txt"));
        assert!(output.contains("sub/"));
        assert!(!output.contains("c.txt"));
    }

    #[test]
    fn lists_recursively_at_depth_2() {
        let dir = tempdir().unwrap();
        fs::create_dir(dir.path().join("sub")).unwrap();
        fs::write(dir.path().join("sub").join("c.txt"), "c").unwrap();
        fs::create_dir(dir.path().join("sub").join("deep")).unwrap();
        fs::write(dir.path().join("sub").join("deep").join("d.txt"), "d").unwrap();

        let result = execute_list_files(serde_json::json!({
            "path": dir.path().to_str().unwrap(),
            "max_depth": 2
        }));
        let output = result.text();
        assert!(output.contains("sub/c.txt"));
        assert!(output.contains("sub/deep/"));
        assert!(!output.contains("sub/deep/d.txt"));
    }

    #[test]
    fn returns_error_for_missing_directory() {
        let result = execute_list_files(serde_json::json!({
            "path": "/nonexistent/dir",
            "max_depth": 1
        }));
        assert!(result.is_error());
    }

    #[test]
    fn returns_sorted_output() {
        let dir = tempdir().unwrap();
        fs::write(dir.path().join("b.txt"), "b").unwrap();
        fs::write(dir.path().join("a.txt"), "a").unwrap();

        let result = execute_list_files(serde_json::json!({
            "path": dir.path().to_str().unwrap(),
            "max_depth": 1
        }));
        let lines: Vec<&str> = result.text().lines().collect();
        assert_eq!(lines[0], "a.txt");
        assert_eq!(lines[1], "b.txt");
    }
}
