/** Public domain model supplied to attendees; behavior is defined by SPEC.md. */
export type Value =
  | { kind: "null" }
  | { kind: "bool"; value: boolean }
  | { kind: "int"; value: number }
  | { kind: "float"; value: number }
  | { kind: "str"; value: string }
  | { kind: "filesize"; bytes: number }
  | { kind: "date"; seconds: number }
  | { kind: "list"; items: Value[] }
  | { kind: "record"; fields: Array<[string, Value]> }
  | { kind: "table"; columns: string[]; rows: Value[][] };

export type Literal = string | number | boolean | { filesize: number };
export type Arg = { kind: "literal"; value: Literal } | { kind: "flag"; name: string };
export interface Command { name: string; args: Arg[] }
export interface Pipeline { commands: Command[] }
export interface ShellState { cwd: string; now: number; color: boolean; env: NodeJS.ProcessEnv }
