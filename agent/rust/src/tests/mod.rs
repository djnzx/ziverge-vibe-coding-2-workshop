use super::*;
use crate::agent::*;
use crate::config::*;
use crate::conversation::*;
use crate::exercises::*;
use crate::tools::*;
use anyhow::Result;
use async_openai::types::ChatCompletionRequestUserMessageContent;
use rustyline::error::ReadlineError;
use std::{
    cell::RefCell,
    env, fs, io,
    io::ErrorKind,
    path::Path,
    path::PathBuf,
    sync::{LazyLock, Mutex, MutexGuard},
};
use tempfile::TempDir;
use terminal::TerminalOutput;
use terminal::{RecordingTerminal, TerminalEvent};

#[derive(Debug)]
struct MockProvider {
    responses: RefCell<Vec<String>>,
}

#[async_trait::async_trait(?Send)]
impl ChatProvider for MockProvider {
    async fn complete(&self, _messages: &[ChatCompletionRequestMessage]) -> Result<String> {
        let mut responses = self.responses.borrow_mut();
        if responses.is_empty() {
            Ok(done_msg("No more responses."))
        } else {
            Ok(responses.remove(0))
        }
    }
}

struct TempCwdGuard {
    _lock: MutexGuard<'static, ()>,
    old_dir: PathBuf,
    _temp_dir: TempDir,
}

impl Drop for TempCwdGuard {
    fn drop(&mut self) {
        std::env::set_current_dir(&self.old_dir).unwrap();
    }
}

fn setup_temp_cwd() -> TempCwdGuard {
    static TEST_CWD_LOCK: LazyLock<Mutex<()>> = LazyLock::new(|| Mutex::new(()));

    let lock = TEST_CWD_LOCK.lock().unwrap();
    let old_dir = std::env::current_dir().unwrap();
    let temp_dir = tempfile::tempdir().unwrap();
    std::env::set_current_dir(temp_dir.path()).unwrap();

    TempCwdGuard {
        _lock: lock,
        old_dir,
        _temp_dir: temp_dir,
    }
}

fn initial_messages(config: &AgentConfig, available_tools: &[Tool]) -> Conversation {
    Conversation::new(build_system_prompt(config, available_tools, false))
}

fn done_msg(msg: &str) -> String {
    format!(
        r#"<tool_call>{{"name":"message_user","arguments":{{"message":"{}"}}}}</tool_call>"#,
        msg
    )
}

fn message_contains(message: &ChatCompletionRequestMessage, needle: &str) -> bool {
    serde_json::to_string(message)
        .map(|serialized| serialized.contains(needle))
        .unwrap_or(false)
}

mod module01_exercises;
mod module02_exercises;
mod module03_exercises;
mod phase_wiring;
mod scaffolding;
