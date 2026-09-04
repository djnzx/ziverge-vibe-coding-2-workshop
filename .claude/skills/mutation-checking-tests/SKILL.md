---
name: mutation-checking-tests
description: Use when a new test or batch of tests passes on the first run, before claiming the tests verify anything
---

# Mutation Checking Tests

## Overview

**A test you never watched fail proves nothing.**

TDD gives you that failure for free: write the test, watch it fail, then implement.
But some tests cannot be written first — properties over existing behavior,
characterization tests on legacy code, regression tests added alongside a fix, tests
generated in bulk. Those pass on their first run, and a first-run pass is
indistinguishable from a test that asserts nothing.

Mutation checking recovers the missing RED: break the code on purpose, confirm the
tests catch it, revert.

## When to Use

- A new test or property passed the first time it ran
- You added tests to code that already worked
- A suite is large and green and you cannot say which parts are load-bearing
- Reviewing tests someone else wrote

Skip when you already watched the test fail before implementing — that *is* the check.

## The Loop

1. **Name the invariant** the tests claim to protect
2. **Break it minimally** — one guard, one comparison, one boundary
3. **Run the suite**
4. **Read which tests failed**, not just the count
5. **Revert from a copy** — never by retyping
6. **Re-run** to confirm green

```bash
cp src/Parser.scala /tmp/Parser.scala.bak
# remove the guard stopping a literal from running into the next token
sed -i '' 's/P.not(P.charWhere(isIdentRest))/P.unit/' src/Parser.scala
sbt "testOnly *ParserPropertyTest"          # expect failures — read them
cp /tmp/Parser.scala.bak src/Parser.scala && rm /tmp/Parser.scala.bak
sbt "testOnly *ParserPropertyTest"          # expect green
```

## Choosing the Mutation

| Good mutation              | Why                                          |
|----------------------------|----------------------------------------------|
| Remove a boundary guard    | Tests should pin the boundary                |
| Flip `<` to `<=`           | Off-by-one is the classic escape             |
| Drop a validation branch   | Rejection tests should fire                  |
| Return the input unchanged | Transformation tests should fire             |

| Bad mutation                | Why                                         |
|-----------------------------|---------------------------------------------|
| `throw new Exception`       | Everything fails; tells you nothing         |
| Delete the whole function   | Compile error, not a test signal            |
| Change a log message        | Nothing should depend on it                 |

**Zero failures means the tests are decorative.** That is the finding — write the
missing test. **Everything failing means the mutation was too broad** — pick
something smaller and repeat.

## Rationalization Table

| Excuse                                  | Reality                                                                          |
|-----------------------------------------|----------------------------------------------------------------------------------|
| "The tests are obviously correct"       | Obvious-looking tests assert on the wrong thing constantly. One minute settles it. |
| "Coverage is green, the lines run"      | Coverage says a line executed, not that any assertion would notice it changing.   |
| "I'll mutate if a bug slips through"    | Then you have paid for the bug and still do not know whether the suite works.     |
| "Breaking working code is risky"        | You copy the file first and restore it. The risk is a stale suite you trust.      |
| "It passed — that's the point"          | A test that *cannot* fail also passes. Passing alone is not evidence.             |
| "TDD already covers this"               | Only for tests written before the code. These were not.                          |
| "The suite is too big to mutate"        | Mutate one invariant, not the codebase. It is one edit.                          |

## Red Flags

- "All 15 new tests passed first try" reported as a success
- Reverting by retyping the original line instead of restoring the copy
- Reporting a failure count without reading *which* tests failed
- Skipping the final green re-run after reverting
- Mutating, seeing zero failures, and moving on

**All of these mean the suite is still unverified.**

## Real-World Impact

31 parser properties passed on their first run. Removing a single guard — the check
that a literal may not run into the following identifier — was caught by 11 of them,
4 being rejection properties, and the framework named the newly-accepted input.
Before the mutation, "31 green" was an assertion. After it, it was evidence.
