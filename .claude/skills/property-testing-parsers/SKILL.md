---
name: property-testing-parsers
description: Use when writing or reviewing tests for a parser, tokenizer, grammar, or text-to-structure decoder, especially when the existing tests only assert that valid input parses correctly
---

# Property Testing Parsers

## Overview

A parser suite that only checks "valid input produces the right value" is satisfied
by a parser that accepts **everything**. Cover three axes or the suite proves little:

1. **Round-trip** — valid input parses to the right structure
2. **Rejection** — invalid input is refused, with the documented error shape
3. **Totality** — every input returns a result; nothing throws

Rejection is the axis people skip.

## When to Use

- Any hand-written or combinator parser (cats-parse, nom, parsec, PEG, recursive descent)
- Config, DSL, or wire-format decoders: JSON, CSV, query strings, log lines, dates
- Reviewing a parser change whose tests are all `assert(parse(good) == expected)`

Skip for generated parsers whose grammar file is the spec and that already have
upstream conformance tests.

## The Generator Pattern

Generate **source text paired with the value it must parse to**. This buys round-trip
properties without writing a pretty-printer.

```scala
case class Tok(text: String, value: Arg)

val genInt: Gen[Tok] =
  Gen.choose(-1000000L, 1000000L).map(n => Tok(n.toString, Lit(Int(n))))

val genStr: Gen[Tok] =
  genContent.map(s => Tok("\"" + escape(s) + "\"", Lit(Str(s))))

// compose Toks into a command, commands into a pipeline, then:
forAll(genPipeline)(cs => parse(sourceOf(cs)) == Right(astOf(cs)))
```

Compose upward — token, then clause, then whole document — so one generator drives
round-trip, whitespace, and comment properties alike.

## Quick Reference

| Family         | Property                                                    |
|----------------|-------------------------------------------------------------|
| Round-trip     | generated AST → text → parse equals the AST                 |
| Whitespace     | extra separators never change the AST                       |
| Scaling        | units and multipliers hold across the numeric range         |
| Rejection      | each malformed shape is an error                            |
| Error contract | every rejection matches the documented message format       |
| Bounds         | the reported error position lies inside the input           |
| Totality       | arbitrary input returns a value, never throws               |

## Rejection Properties

Name what the grammar must refuse — one property per shape:

- unterminated quote, bracket, or block comment
- unsupported escape sequence
- a separator with a missing operand (leading, trailing, doubled)
- a character with no production in the grammar
- a token running into the next (`5abc` where `5` is a number)
- an unknown enumerated suffix (unit, encoding, keyword)

Then one **meta-property** over a union of every invalid generator: each rejection
matches the documented error format. That stops each new failure path from inventing
its own message.

## Fuzz From Grammar Fragments

`Arbitrary[String]` essentially never emits an unbalanced quote or an oversized
numeric literal — exactly where parsers break. Build fuzz from the grammar's own
alphabet instead:

```scala
val fragment = Gen.oneOf("\"", "'", "\\", "|", "#", "5kb", "999999999999999999999", ".", "-")
val fuzz     = Gen.listOf(fragment).map(_.mkString)
```

Totality over fragment-fuzz found a `NumberFormatException` that 39 example tests
and arbitrary-string fuzzing both missed.

## Common Mistakes

**Vacuous comparison.** `parse(a) == parse(b)` passes when both sides fail. Assert
one side `isRight` so the property has to prove something.

**Trusting the shrunk counterexample.** Shrinkers ignore generator constraints, so
the shrunk value often violates the property's own premise. Read the original
argument the framework reports alongside it.

**Totality without rejection.** `isRight || isLeft` is a tautology except for throws.
Necessary, nowhere near sufficient.

**Round-trip only.** Passes for a parser with no error handling whatsoever.

## Verify the Suite Has Teeth

Property suites can be decorative. Break an invariant in the parser on purpose,
confirm properties fail, revert.

**REQUIRED SUB-SKILL:** Use mutation-checking-tests.
