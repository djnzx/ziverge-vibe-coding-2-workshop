export type Assertion =
  | { type: "response_contains"; value: string }
  | { type: "response_not_empty" }
  | { type: "tool_used"; tool: string }
  | { type: "tool_not_used"; tool: string }
  | { type: "tool_count"; count: number }
  | { type: "file_exists"; path: string }
  | { type: "file_not_exists"; path: string }
  | { type: "file_content"; path: string; equals?: string; contains?: string; matches?: string }
  | { type: "shell_check"; command: string }
  | { type: "shell_output"; command: string; contains?: string; equals?: string; matches?: string };

export type Step = {
  input: string;
  assertions: Assertion[];
};

export type ShellCommand = {
  shell: string;
};

export type TestDefinition = {
  name: string;
  timeout?: number;
  setup?: ShellCommand[];
  steps: Step[];
  cleanup?: ShellCommand[];
};

export type AgentResponse = {
  content: string;
  tool_calls: string[];
};

export type AssertionResult = {
  assertion: Assertion;
  passed: boolean;
  message: string;
};

export type StepResult = {
  stepIndex: number;
  input: string;
  response: AgentResponse | null;
  assertions: AssertionResult[];
  error?: string;
  stderr?: string;
};

export type TestResult = {
  name: string;
  passed: boolean;
  duration: number;
  steps: StepResult[];
  error?: string;
};

export type LangConfig = {
  command: string;
  args: string[];
  cwd: string;
};
