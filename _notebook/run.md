Here are the exact commands I ran, in the order I ran them.

**1. Baseline — confirm the tests were red before implementing** (from `agent/ts/`):
```bash
cd agent/ts
npm install                                    # deps were missing
npx tsx --test src/module01.exercises.test.ts  # every test threw "Exercise N: ... see exercises.md"
```

**2. Type check** — after each edit:
```bash
npx tsc --noEmit
```

**3. Per-module exercise tests** — the three suites that map to Modules 01–03:
```bash
npx tsx --test src/module01.exercises.test.ts   # 58 tests
npx tsx --test src/module02.exercises.test.ts   # 52 tests
npx tsx --test src/module03.exercises.test.ts   # 45 tests
```

**4. Full unit suite** — this is what caught my three `handleTurn` terminal-contract bugs, since `terminal.test.ts` and `phase-wiring.test.ts` also exercise the loop:
```bash
npx tsx --test src/*.test.ts                    # 242 tests — equivalent to `npm test`
```
To see just the tally rather than every test name:
```bash
npx tsx --test src/*.test.ts 2>&1 | grep -E "^ℹ (tests|pass|fail)"
```

**5. Lint + build** — this is the one that still reports 6 pre-existing errors:
```bash
npm run build                                   # tsc --noEmit && eslint src/
```

**6. Live smoke test** (from the repo root) — one real OpenRouter call to prove the loop is grounded in actual tool output:
```bash
mkdir -p /tmp/agent-smoke
echo '{"content":"Create a file called greeting.txt containing the text hello world, then run the shell command `wc -c greeting.txt` and tell me the byte count it reported."}' \
  | ./z --lang ts --work-dir /tmp/agent-smoke
cat /tmp/agent-smoke/greeting.txt | od -c       # I verified the reported 11 bytes was real
```
`./z` loads `.env` itself, so no key setup is needed.

**7. Integration harness** — the 13 YAML tests:
```bash
cd agent/test-harness && npm install            # once
cd agent
set -a && . ../.env && set +a                   # the harness CLI does NOT load .env — ./z does
npx tsx test-harness/src/cli.ts --lang ts
```
Add `--verbose` to watch the full agent stdin/stdout/stderr per step — that's the mode Exercise 6 asks you to use when iterating on `agent/system-prompt.txt` (the file you have open).

The fast loop while working is `npx tsc --noEmit && npx tsx --test src/*.test.ts` — no API key, ~1 second. The harness costs real API calls and took about 4½ minutes, so it's worth saving for a final check.
