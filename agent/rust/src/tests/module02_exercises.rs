use super::*;
use serde_json::Value;

fn system_message(content: &str) -> ChatCompletionRequestMessage {
    ChatCompletionRequestSystemMessageArgs::default()
        .content(content)
        .build()
        .unwrap()
        .into()
}

fn user_message(content: &str) -> ChatCompletionRequestMessage {
    ChatCompletionRequestUserMessageArgs::default()
        .content(content)
        .build()
        .unwrap()
        .into()
}

fn assistant_message(content: &str) -> ChatCompletionRequestMessage {
    ChatCompletionRequestAssistantMessageArgs::default()
        .content(content)
        .build()
        .unwrap()
        .into()
}

fn serialize_messages(messages: &[ChatCompletionRequestMessage]) -> Vec<String> {
    messages
        .iter()
        .map(|message| serde_json::to_string(message).unwrap())
        .collect()
}

fn noop_tool(_args: Value) -> ToolResult {
    ToolResult::Ok(String::new())
}

fn simple_tool(name: ToolName, description: &'static str, schema: Value) -> Tool {
    Tool {
        name,
        description,
        schema,
        execute: noop_tool,
    }
}

mod exercise01_load_instructions {
    use super::*;
    use std::{fs, path::Path};
    use tempfile::tempdir;

    #[test]
    fn returns_none_when_no_instructions_exist() {
        let dir = tempdir().unwrap();
        let work_dir = dir.path().join("project").join("src");
        fs::create_dir_all(&work_dir).unwrap();

        assert_eq!(load_instructions(&work_dir, None), None);
    }

    #[test]
    fn loads_content_from_explicit_instructions_path() {
        let dir = tempdir().unwrap();
        let work_dir = dir.path().join("project");
        fs::create_dir_all(&work_dir).unwrap();
        let instructions_path = dir.path().join("custom.md");
        fs::write(&instructions_path, "explicit rules").unwrap();

        assert_eq!(
            load_instructions(&work_dir, Some(instructions_path.as_path())),
            Some("explicit rules".to_string())
        );
    }

    #[test]
    fn returns_none_when_explicit_instructions_file_is_missing() {
        let dir = tempdir().unwrap();
        let work_dir = dir.path().join("project");
        fs::create_dir_all(&work_dir).unwrap();
        let missing = dir.path().join("nonexistent.md");
        assert_eq!(load_instructions(&work_dir, Some(missing.as_path())), None);
    }

    #[test]
    fn resolves_relative_instructions_path_from_work_dir() {
        let dir = tempdir().unwrap();
        let work_dir = dir.path().join("project");
        fs::create_dir_all(work_dir.join("docs")).unwrap();
        fs::write(work_dir.join("docs").join("custom.md"), "relative rules").unwrap();

        assert_eq!(
            load_instructions(&work_dir, Some(Path::new("docs/custom.md"))),
            Some("relative rules".to_string())
        );
    }

    #[test]
    fn walks_up_directory_tree_and_concatenates_root_first() {
        let dir = tempdir().unwrap();
        let root_agents = dir.path().join("AGENTS.md");
        let project_dir = dir.path().join("project");
        let nested_dir = project_dir.join("src").join("feature");
        fs::create_dir_all(&nested_dir).unwrap();
        fs::write(&root_agents, "root instructions").unwrap();
        fs::write(project_dir.join("AGENTS.md"), "project instructions").unwrap();

        assert_eq!(
            load_instructions(&nested_dir, None),
            Some("root instructions\n\nproject instructions".to_string())
        );
    }

    #[test]
    fn returns_none_for_empty_directory_tree() {
        let dir = tempdir().unwrap();

        assert_eq!(load_instructions(dir.path(), None), None);
    }
}

mod exercise02_discover_skills {
    use super::*;
    use std::fs;
    use tempfile::tempdir;

    #[test]
    fn returns_empty_when_skills_dir_is_none() {
        assert!(
            discover_skills(None).is_empty(),
            "discover_skills should return empty when skills_dir is None"
        );
    }

    #[test]
    fn returns_empty_when_skills_dir_does_not_exist() {
        let dir = tempdir().unwrap();
        let missing = dir.path().join("does-not-exist");

        let skills = discover_skills(Some(&missing));

        assert!(
            skills.is_empty(),
            "discover_skills should return empty when skills directory does not exist"
        );
    }

    #[test]
    fn discovers_two_skills_with_name_and_description() {
        let dir = tempdir().unwrap();
        let skills_dir = dir.path().join("skills");
        let git_dir = skills_dir.join("git");
        let release_dir = skills_dir.join("release");
        fs::create_dir_all(&git_dir).unwrap();
        fs::create_dir_all(&release_dir).unwrap();

        fs::write(
            git_dir.join("SKILL.md"),
            "---\nname: \"Git\"\ndescription: \"Use git carefully\"\n---\nGit body",
        )
        .unwrap();
        fs::write(
            release_dir.join("SKILL.md"),
            "---\nname: \"Release\"\ndescription: \"Ship safely\"\n---\nRelease body",
        )
        .unwrap();

        let skills = discover_skills(Some(&skills_dir));

        assert_eq!(skills.len(), 2);
        assert!(skills.iter().any(|skill| {
            skill.name == "Git"
                && skill.description == "Use git carefully"
                && skill.path.ends_with("git/SKILL.md")
        }));
        assert!(skills.iter().any(|skill| {
            skill.name == "Release"
                && skill.description == "Ship safely"
                && skill.path.ends_with("release/SKILL.md")
        }));
    }

    #[test]
    fn ignores_subdirectories_without_skill_md() {
        let dir = tempdir().unwrap();
        let skills_dir = dir.path().join("skills");
        let empty_dir = skills_dir.join("empty");
        let valid_dir = skills_dir.join("valid");
        fs::create_dir_all(&empty_dir).unwrap();
        fs::create_dir_all(&valid_dir).unwrap();
        fs::write(
            valid_dir.join("SKILL.md"),
            "---\nname: \"Valid\"\ndescription: \"Found\"\n---\nBody",
        )
        .unwrap();

        let skills = discover_skills(Some(&skills_dir));

        assert_eq!(skills.len(), 1);
        assert_eq!(skills[0].name, "Valid");
    }

    #[test]
    fn sorts_skills_by_path_instead_of_parsed_name() {
        let dir = tempdir().unwrap();
        let skills_dir = dir.path().join("skills");
        let a_dir = skills_dir.join("a-first");
        let z_dir = skills_dir.join("z-second");
        fs::create_dir_all(&a_dir).unwrap();
        fs::create_dir_all(&z_dir).unwrap();
        fs::write(
            a_dir.join("SKILL.md"),
            "---\nname: \"Zulu\"\ndescription: \"Path comes first\"\n---\nA body",
        )
        .unwrap();
        fs::write(
            z_dir.join("SKILL.md"),
            "---\nname: \"Alpha\"\ndescription: \"Metadata comes second\"\n---\nZ body",
        )
        .unwrap();

        let skills = discover_skills(Some(&skills_dir));

        assert_eq!(
            skills
                .iter()
                .map(|skill| skill.path.clone())
                .collect::<Vec<_>>(),
            vec![a_dir.join("SKILL.md"), z_dir.join("SKILL.md")]
        );
    }

    #[test]
    fn load_skill_content_returns_full_file_content() {
        let dir = tempdir().unwrap();
        let skill_path = dir.path().join("SKILL.md");
        let original = "---\nname: \"Review\"\ndescription: \"Check code\"\n---\nFull body";
        fs::write(&skill_path, original).unwrap();

        assert_eq!(load_skill_content(&skill_path), original);
    }

    #[test]
    fn load_skill_content_returns_empty_string_when_file_is_missing() {
        let dir = tempdir().unwrap();

        assert_eq!(load_skill_content(&dir.path().join("missing.md")), "");
    }
}

mod exercise03_discover_commands {
    use super::*;
    use std::fs;
    use tempfile::tempdir;

    #[test]
    fn returns_empty_when_commands_dir_is_none() {
        assert!(
            discover_commands(None).is_empty(),
            "discover_commands should return empty when commands_dir is None"
        );
    }

    #[test]
    fn discovers_command_with_description_and_argument_hint() {
        let dir = tempdir().unwrap();
        let commands_dir = dir.path().join("commands");
        fs::create_dir_all(&commands_dir).unwrap();
        fs::write(
            commands_dir.join("code-review.md"),
            "---\ndescription: \"Review a file\"\nargument-hint: \"<path>\"\n---\nReview $ARGUMENTS",
        )
        .unwrap();

        let commands = discover_commands(Some(&commands_dir));

        assert_eq!(commands.len(), 1);
        assert_eq!(commands[0].name, "code-review");
        assert_eq!(commands[0].description, "Review a file");
        assert_eq!(commands[0].argument_hint.as_deref(), Some("<path>"));
    }

    #[test]
    fn stores_none_when_argument_hint_is_empty_after_trimming() {
        let dir = tempdir().unwrap();
        let commands_dir = dir.path().join("commands");
        fs::create_dir_all(&commands_dir).unwrap();
        fs::write(
            commands_dir.join("triage.md"),
            "---\ndescription: \"Triage\"\nargument-hint: \"   \"\n---\nTriage $ARGUMENTS",
        )
        .unwrap();

        let commands = discover_commands(Some(&commands_dir));

        assert_eq!(commands.len(), 1);
        assert_eq!(commands[0].argument_hint, None);
    }

    #[test]
    fn ignores_non_markdown_files() {
        let dir = tempdir().unwrap();
        let commands_dir = dir.path().join("commands");
        fs::create_dir_all(&commands_dir).unwrap();
        fs::write(
            commands_dir.join("code-review.md"),
            "---\ndescription: \"Review\"\n---\nReview $ARGUMENTS",
        )
        .unwrap();
        fs::write(commands_dir.join("notes.txt"), "ignore me").unwrap();

        let commands = discover_commands(Some(&commands_dir));

        assert_eq!(commands.len(), 1);
        assert_eq!(commands[0].name, "code-review");
    }

    #[test]
    fn execute_command_substitutes_arguments_in_body() {
        let dir = tempdir().unwrap();
        let command_path = dir.path().join("review.md");
        fs::write(
            &command_path,
            "---\ndescription: \"Review\"\n---\nPlease review $ARGUMENTS now.",
        )
        .unwrap();
        let command = CommandPrompt {
            name: "review".to_string(),
            description: "Review".to_string(),
            argument_hint: None,
            path: command_path,
        };

        let output = execute_command(&command, "src/main.rs");

        assert_eq!(output, "Please review src/main.rs now.");
    }

    #[test]
    fn execute_command_strips_yaml_frontmatter_from_output() {
        let dir = tempdir().unwrap();
        let command_path = dir.path().join("summarize.md");
        fs::write(
            &command_path,
            "---\ndescription: \"Summarize\"\nargument-hint: \"<topic>\"\n---\nSummarize $ARGUMENTS",
        )
        .unwrap();
        let command = CommandPrompt {
            name: "summarize".to_string(),
            description: "Summarize".to_string(),
            argument_hint: Some("<topic>".to_string()),
            path: command_path,
        };

        let output = execute_command(&command, "context windows");

        assert_eq!(output, "Summarize context windows");
        assert!(
            !output.contains("description:"),
            "execute_command output should not include frontmatter description"
        );
        assert!(
            !output.contains("---"),
            "execute_command output should not include frontmatter delimiters"
        );
    }

    #[test]
    fn execute_command_strips_single_blank_separator_after_frontmatter() {
        let dir = tempdir().unwrap();
        let command_path = dir.path().join("review.md");
        fs::write(
            &command_path,
            "---\ndescription: \"Review\"\n---\n\nPlease review $ARGUMENTS now.",
        )
        .unwrap();
        let command = CommandPrompt {
            name: "review".to_string(),
            description: "Review".to_string(),
            argument_hint: None,
            path: command_path,
        };

        let output = execute_command(&command, "src/main.rs");

        assert_eq!(output, "Please review src/main.rs now.");
    }

    #[test]
    fn execute_command_returns_empty_string_when_file_is_missing() {
        let dir = tempdir().unwrap();
        let command = CommandPrompt {
            name: "missing".to_string(),
            description: "Missing".to_string(),
            argument_hint: None,
            path: dir.path().join("missing.md"),
        };

        assert_eq!(execute_command(&command, "anything"), "");
    }
}

mod exercise04_measure_context {
    use super::*;

    fn expected_tool_chars(tool: &Tool) -> usize {
        format!(
            "### {}\n{}\nParameters:\n```json\n{}\n```",
            tool.name,
            tool.description,
            serde_json::to_string_pretty(&tool.schema).unwrap()
        )
        .len()
    }

    #[test]
    fn reports_correct_character_counts_for_known_inputs() {
        let tool = simple_tool(
            ToolName::ReadFile,
            "Read a file",
            serde_json::json!({"type": "object"}),
        );
        let conversation = vec![user_message("hello"), assistant_message("world!")];

        let usage = measure_context(
            "system",
            &conversation,
            std::slice::from_ref(&tool),
            Some(200),
        );

        assert_eq!(usage.system, 6);
        assert_eq!(usage.conversation, 11);
        assert_eq!(usage.tools, expected_tool_chars(&tool));
        assert_eq!(usage.total, usage.system + usage.conversation + usage.tools);
    }

    #[test]
    fn computes_percentage_when_limit_is_set() {
        let tool = simple_tool(
            ToolName::Shell,
            "Run shell",
            serde_json::json!({"type": "object"}),
        );
        let usage = measure_context("sys", &[user_message("hi")], &[tool], Some(100));

        let expected = usage.total as f64 / 100.0;
        assert_eq!(usage.percentage, Some(expected));
    }

    #[test]
    fn leaves_percentage_empty_when_limit_is_none() {
        let usage = measure_context("sys", &[user_message("hi")], &[], None);

        assert_eq!(usage.percentage, None);
    }

    #[test]
    fn leaves_percentage_empty_when_limit_is_zero() {
        let usage = measure_context("sys", &[user_message("hi")], &[], Some(0));

        assert_eq!(usage.percentage, None);
    }

    #[test]
    fn empty_conversation_counts_only_system_and_tools() {
        let tool = simple_tool(
            ToolName::MessageUser,
            "Message the user",
            serde_json::json!({"type": "object"}),
        );

        let usage = measure_context("sys", &[], std::slice::from_ref(&tool), Some(500));

        assert_eq!(usage.system, 3);
        assert_eq!(usage.conversation, 0);
        assert_eq!(usage.tools, expected_tool_chars(&tool));
    }

    #[test]
    fn counts_utf8_byte_length_instead_of_unicode_scalar_count() {
        let usage = measure_context("é", &[user_message("🙂")], &[], None);

        assert_eq!(usage.system, "é".len());
        assert_eq!(usage.conversation, "🙂".len());
        assert_eq!(usage.total, "é".len() + "🙂".len());
    }
}

mod exercise05_compact_conversation {
    use super::*;

    #[test]
    fn format_conversation_for_compaction_strips_system_messages() {
        let mut conversation = Conversation::new("sys".to_string());
        conversation.push(user_message("first"));
        conversation.push(assistant_message("second"));

        assert_eq!(
            format_conversation_for_compaction(&conversation),
            "user: first\n\nassistant: second"
        );
    }

    #[test]
    fn apply_compaction_replaces_turns_with_summary() {
        let mut conversation = Conversation::new("sys".to_string());
        conversation.push(user_message("question"));
        conversation.push(assistant_message("answer"));

        apply_compaction(&mut conversation, "summary text").unwrap();

        assert_eq!(conversation.turns.len(), 2);
        assert!(message_contains(&conversation.turns[0], "summary text"));
        assert!(message_contains(
            &conversation.turns[1],
            "Context loaded. How can I help?"
        ));
    }

    #[test]
    fn apply_compaction_preserves_system_prompt() {
        let mut conversation = Conversation::new("sys".to_string());
        let original_system = serde_json::to_string(&conversation[0]).unwrap();
        conversation.push(user_message("question"));

        apply_compaction(&mut conversation, "summary text").unwrap();

        assert_eq!(
            serde_json::to_string(&conversation[0]).unwrap(),
            original_system
        );
    }

    #[test]
    fn apply_compaction_keeps_user_assistant_alternation() {
        let mut conversation = Conversation::new("sys".to_string());
        conversation.push(user_message("question"));
        conversation.push(assistant_message("answer"));

        apply_compaction(&mut conversation, "summary text").unwrap();

        assert!(matches!(
            conversation.turns[0],
            ChatCompletionRequestMessage::User(_)
        ));
        assert!(matches!(
            conversation.turns[1],
            ChatCompletionRequestMessage::Assistant(_)
        ));
    }
}

mod exercise06_should_auto_compact {
    use super::*;

    #[test]
    fn returns_false_when_limit_is_none() {
        let usage = ContextUsage {
            system: 10,
            conversation: 20,
            tools: 5,
            total: 35,
            limit: None,
            percentage: None,
        };

        assert!(
            !should_auto_compact(&usage),
            "should_auto_compact should return false when limit is None"
        );
    }

    #[test]
    fn returns_true_when_total_exceeds_limit() {
        let usage = ContextUsage {
            system: 10,
            conversation: 20,
            tools: 5,
            total: 36,
            limit: Some(35),
            percentage: Some(36.0 / 35.0),
        };

        assert!(
            should_auto_compact(&usage),
            "should_auto_compact should return true when total exceeds limit"
        );
    }

    #[test]
    fn returns_false_when_total_does_not_exceed_limit() {
        let usage = ContextUsage {
            system: 10,
            conversation: 20,
            tools: 5,
            total: 35,
            limit: Some(35),
            percentage: Some(1.0),
        };

        assert!(
            !should_auto_compact(&usage),
            "should_auto_compact should return false when total does not exceed limit"
        );
    }
}

mod exercise07_save_and_load_session {
    use super::*;
    use regex::Regex;
    use std::fs;
    use tempfile::tempdir;

    #[test]
    fn save_session_creates_json_file_in_history_dir() {
        let dir = tempdir().unwrap();
        let conversation = vec![system_message("sys"), user_message("hello")];

        save_session(dir.path(), "session-1", &conversation).unwrap();

        let saved: serde_json::Value =
            serde_json::from_str(&fs::read_to_string(dir.path().join("session-1.json")).unwrap())
                .unwrap();

        let timestamp = saved["timestamp"].as_str().unwrap();
        assert!(Regex::new(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
            .unwrap()
            .is_match(timestamp));
        assert!(
            saved["workDir"].is_string(),
            "saved session should include workDir as a string"
        );
        assert!(
            saved["model"].is_string(),
            "saved session should include model as a string"
        );
        assert_eq!(saved["systemPrompt"], "sys");
        assert_eq!(saved["turns"].as_array().unwrap().len(), 1);
        assert_eq!(saved["turns"][0]["role"], "user");
        assert_eq!(saved["turns"][0]["content"], "hello");
    }

    #[test]
    fn load_session_restores_saved_messages() {
        let dir = tempdir().unwrap();
        fs::write(
            dir.path().join("session-2.json"),
            serde_json::to_string_pretty(&serde_json::json!({
                "timestamp": "2026-05-04T10:00:00Z",
                "workDir": "/tmp/project",
                "model": "test-model",
                "systemPrompt": "sys",
                "turns": [
                    { "role": "user", "content": "hello" },
                    { "role": "assistant", "content": "hi" }
                ]
            }))
            .unwrap(),
        )
        .unwrap();

        let loaded = load_session(dir.path(), "session-2").unwrap().unwrap();

        assert_eq!(
            serialize_messages(&loaded),
            serialize_messages(&[
                system_message("sys"),
                user_message("hello"),
                assistant_message("hi")
            ])
        );
    }

    #[test]
    fn round_trips_saved_conversation() {
        let dir = tempdir().unwrap();
        let conversation = vec![
            system_message("sys"),
            user_message("Create a plan"),
            assistant_message("Plan created"),
        ];

        save_session(dir.path(), "session-3", &conversation).unwrap();
        let loaded = load_session(dir.path(), "session-3").unwrap().unwrap();

        assert_eq!(
            serialize_messages(&loaded),
            serialize_messages(&conversation)
        );
    }

    #[test]
    fn load_session_returns_none_for_missing_file() {
        let dir = tempdir().unwrap();

        assert_eq!(load_session(dir.path(), "missing").unwrap(), None);
    }
}

mod exercise08_load_past_session_summaries {
    use super::*;
    use crate::conversation::{ChatCompletionRequestMessage, ChatProvider};
    use anyhow::Result;
    use std::{fs, path::Path};
    use tempfile::tempdir;

    fn write_session(
        history_dir: &Path,
        session_id: &str,
        timestamp: &str,
        turns: &[(&str, &str)],
    ) {
        let turns = turns
            .iter()
            .map(|(role, content)| serde_json::json!({ "role": role, "content": content }))
            .collect::<Vec<_>>();
        fs::write(
            history_dir.join(format!("{session_id}.json")),
            serde_json::to_string_pretty(&serde_json::json!({
                "timestamp": timestamp,
                "workDir": "/tmp/project",
                "model": "test-model",
                "systemPrompt": "sys",
                "turns": turns
            }))
            .unwrap(),
        )
        .unwrap();
    }

    /// Short inputs fit within the topic-char limit and must NOT trigger an LLM
    /// call. Tests pass this provider to assert the no-LLM-for-short-inputs path.
    struct UnusedProvider;

    #[async_trait::async_trait(?Send)]
    impl ChatProvider for UnusedProvider {
        async fn complete(&self, _messages: &[ChatCompletionRequestMessage]) -> Result<String> {
            panic!("provider should not be called for short inputs");
        }
    }

    /// Returns a fixed summary regardless of input. Used for long-input tests to
    /// verify the LLM summarisation path runs and the result is plumbed through.
    struct FixedSummaryProvider(&'static str);

    #[async_trait::async_trait(?Send)]
    impl ChatProvider for FixedSummaryProvider {
        async fn complete(&self, _messages: &[ChatCompletionRequestMessage]) -> Result<String> {
            Ok(self.0.to_string())
        }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn returns_empty_string_when_no_prior_sessions_exist() {
        let dir = tempdir().unwrap();
        let provider = UnusedProvider;
        assert_eq!(
            load_past_session_summaries(dir.path(), "current", &provider).await,
            ""
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn formats_prior_sessions_with_timestamp_and_first_user_message() {
        let dir = tempdir().unwrap();
        write_session(
            dir.path(),
            "prior",
            "2026-05-04T10:00:00Z",
            &[
                ("user", "Review the compaction prompt"),
                ("assistant", "done"),
            ],
        );

        let provider = UnusedProvider;
        let summaries = load_past_session_summaries(dir.path(), "current", &provider).await;

        assert_eq!(
            summaries,
            "[2026-05-04T10:00:00Z] topic: Review the compaction prompt"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn excludes_current_session_by_id() {
        let dir = tempdir().unwrap();
        write_session(
            dir.path(),
            "current",
            "2026-05-04T10:00:00Z",
            &[("user", "Current work")],
        );
        write_session(
            dir.path(),
            "previous",
            "2026-05-03T09:00:00Z",
            &[("user", "Previous work")],
        );

        let provider = UnusedProvider;
        let summaries = load_past_session_summaries(dir.path(), "current", &provider).await;

        assert!(
            summaries.contains("Previous work"),
            "session summaries should include previous session topic"
        );
        assert!(
            !summaries.contains("Current work"),
            "session summaries should exclude current session topic"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn handles_history_dir_with_no_json_files() {
        let dir = tempdir().unwrap();
        fs::write(dir.path().join("notes.txt"), "not a session").unwrap();

        let provider = UnusedProvider;
        assert_eq!(
            load_past_session_summaries(dir.path(), "current", &provider).await,
            ""
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn llm_summarises_long_topics_response_plumbed_through_verbatim_when_it_fits() {
        let dir = tempdir().unwrap();
        let long_topic = "a".repeat(90);
        write_session(
            dir.path(),
            "prior",
            "2026-05-04T10:00:00Z",
            &[("user", long_topic.as_str())],
        );

        let provider = FixedSummaryProvider("Investigating ninety-A topic preview");
        let summaries = load_past_session_summaries(dir.path(), "current", &provider).await;

        assert_eq!(
            summaries,
            "[2026-05-04T10:00:00Z] topic: Investigating ninety-A topic preview"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn truncates_an_llm_response_that_overshoots() {
        let dir = tempdir().unwrap();
        let long_topic = "a".repeat(90);
        write_session(
            dir.path(),
            "prior",
            "2026-05-04T10:00:00Z",
            &[("user", long_topic.as_str())],
        );

        // Model overshoots the 80-char request — belt-and-suspenders cap reins it in.
        let provider = FixedSummaryProvider(
            "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
        );
        let summaries = load_past_session_summaries(dir.path(), "current", &provider).await;

        assert_eq!(
            summaries,
            format!("[2026-05-04T10:00:00Z] topic: {}...", "X".repeat(77))
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn skips_sessions_without_user_messages() {
        let dir = tempdir().unwrap();
        write_session(
            dir.path(),
            "assistant-only",
            "2026-05-04T10:00:00Z",
            &[("assistant", "No user topic here")],
        );
        write_session(
            dir.path(),
            "with-user",
            "2026-05-05T10:00:00Z",
            &[("user", "Real topic")],
        );

        let provider = UnusedProvider;
        let summaries = load_past_session_summaries(dir.path(), "current", &provider).await;

        assert_eq!(summaries, "[2026-05-05T10:00:00Z] topic: Real topic");
    }
}

mod exercise09_extract_memories {
    use super::*;
    use std::{cell::RefCell, fs};
    use tempfile::tempdir;

    #[derive(Debug)]
    struct CapturingProvider {
        responses: RefCell<Vec<String>>,
        captured_messages: RefCell<Vec<Vec<ChatCompletionRequestMessage>>>,
    }

    #[async_trait::async_trait(?Send)]
    impl ChatProvider for CapturingProvider {
        async fn complete(&self, messages: &[ChatCompletionRequestMessage]) -> Result<String> {
            self.captured_messages.borrow_mut().push(messages.to_vec());

            let mut responses = self.responses.borrow_mut();
            if responses.is_empty() {
                Ok("NONE".to_string())
            } else {
                Ok(responses.remove(0))
            }
        }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn appends_extracted_facts_to_memory_file() {
        let dir = tempdir().unwrap();
        let provider = MockProvider {
            responses: RefCell::new(vec!["Prefers Rust\nRuns cargo test".to_string()]),
        };

        extract_memories(&provider, "I like Rust", "Use cargo test", dir.path())
            .await
            .unwrap();

        assert_eq!(
            fs::read_to_string(dir.path().join(".agent").join("memories.md")).unwrap(),
            "- Prefers Rust\n- Runs cargo test\n"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn does_nothing_when_provider_returns_none() {
        let dir = tempdir().unwrap();
        let provider = MockProvider {
            responses: RefCell::new(vec!["NONE".to_string()]),
        };

        extract_memories(&provider, "Nothing special", "Okay", dir.path())
            .await
            .unwrap();

        assert!(
            !dir.path().join(".agent").join("memories.md").exists(),
            "extract_memories should not create memories file when provider returns NONE"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn does_nothing_when_provider_returns_none_case_insensitively() {
        let dir = tempdir().unwrap();
        let provider = MockProvider {
            responses: RefCell::new(vec!["  nOnE  ".to_string()]),
        };

        extract_memories(&provider, "Nothing special", "Okay", dir.path())
            .await
            .unwrap();

        assert!(
            !dir.path().join(".agent").join("memories.md").exists(),
            "extract_memories should not create memories file for case-insensitive NONE"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn creates_memory_file_when_it_does_not_exist() {
        let dir = tempdir().unwrap();
        let provider = MockProvider {
            responses: RefCell::new(vec!["Likes concise answers".to_string()]),
        };

        extract_memories(&provider, "Be concise", "Will do", dir.path())
            .await
            .unwrap();

        assert!(
            dir.path().join(".agent").join("memories.md").exists(),
            "extract_memories should create memories file when facts are extracted"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn appends_to_existing_memory_file_without_overwriting() {
        let dir = tempdir().unwrap();
        let memory_dir = dir.path().join(".agent");
        fs::create_dir_all(&memory_dir).unwrap();
        fs::write(memory_dir.join("memories.md"), "- Existing fact\n").unwrap();
        let provider = MockProvider {
            responses: RefCell::new(vec!["Learns Rust conventions".to_string()]),
        };

        extract_memories(&provider, "Use Rust idioms", "Okay", dir.path())
            .await
            .unwrap();

        assert_eq!(
            fs::read_to_string(memory_dir.join("memories.md")).unwrap(),
            "- Existing fact\n- Learns Rust conventions\n"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn filters_none_lines_and_empty_bullets_from_provider_output() {
        let dir = tempdir().unwrap();
        let provider = MockProvider {
            responses: RefCell::new(vec![
                "- NONE\n\n* Remembers test preferences\nnone\n*   \n".to_string()
            ]),
        };

        extract_memories(&provider, "Remember this", "Okay", dir.path())
            .await
            .unwrap();

        assert_eq!(
            fs::read_to_string(dir.path().join(".agent").join("memories.md")).unwrap(),
            "- Remembers test preferences\n"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn sends_prompt_with_user_message_agent_response_and_none_instruction() {
        let dir = tempdir().unwrap();
        let provider = CapturingProvider {
            responses: RefCell::new(vec!["Prefers cargo fmt".to_string()]),
            captured_messages: RefCell::new(Vec::new()),
        };

        extract_memories(
            &provider,
            "Please remember that I run cargo fmt.",
            "Understood. I will run it.",
            dir.path(),
        )
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

        let expected_prompt =
            "Extract durable facts worth remembering from this conversation turn.\n\
Focus on: user preferences, project conventions, technical decisions, recurring patterns.\n\n\
User message:\n\
Please remember that I run cargo fmt.\n\n\
Agent response:\n\
Understood. I will run it.\n\n\
Return one fact per line. If nothing is worth remembering, return exactly NONE.";

        assert_eq!(prompt, expected_prompt);
        assert!(
            prompt.contains("Please remember that I run cargo fmt."),
            "memory extraction prompt should include original user message"
        );
        assert!(
            prompt.contains("Understood. I will run it."),
            "memory extraction prompt should include agent response"
        );
        assert!(
            prompt.contains("NONE"),
            "memory extraction prompt should include NONE fallback instruction"
        );
    }
}

mod exercise10_load_memories {
    use super::*;
    use std::fs;
    use tempfile::tempdir;

    #[test]
    fn returns_content_of_existing_memory_file() {
        let dir = tempdir().unwrap();
        let memory_dir = dir.path().join(".agent");
        fs::create_dir_all(&memory_dir).unwrap();
        fs::write(memory_dir.join("memories.md"), "- Prefers Rust\n").unwrap();

        assert_eq!(
            load_memories(dir.path()),
            Some("- Prefers Rust\n".to_string())
        );
    }

    #[test]
    fn returns_none_when_memory_file_does_not_exist() {
        let dir = tempdir().unwrap();

        assert_eq!(load_memories(dir.path()), None);
    }

    #[test]
    fn preserves_memory_file_formatting() {
        let dir = tempdir().unwrap();
        let memory_dir = dir.path().join(".agent");
        fs::create_dir_all(&memory_dir).unwrap();
        let content = "- Prefers Rust\n- Uses cargo fmt\n";
        fs::write(memory_dir.join("memories.md"), content).unwrap();

        assert_eq!(load_memories(dir.path()), Some(content.to_string()));
    }
}
