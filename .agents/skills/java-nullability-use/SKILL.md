---
name: java-nullability-use
description: How to write Java code in pgjdbc that satisfies the Checker Framework null-safety verifier. Apply when adding or editing fields, parameters, return types, or generic type arguments in main Java sources.
---

# Null safety in pgjdbc

Main Java sources (`**/src/main/java`) are verified by the Checker
Framework's nullness checker, run under `-PenableCheckerframework`.
Test sources are not verified.

## Defaults

Parameters, return values, and fields default to `@NonNull` — do not
annotate them as such. Annotate only the type uses where `null` is a
legal value:

```java
void f(@Nullable Foo x)                 // parameter
@Nullable Foo find(int id)              // return type
private @Nullable Foo cached;           // field
Map<String, @Nullable Foo> byName;      // generic type argument
```

Local variables don't need annotations — the checker infers them from
the assignment.

Type variables are the exception to "don't write `@NonNull`". A bare
`<T>` gets an implicit `@Nullable Object` bound, so `T` may stand for a
nullable type. Spell the bound out when the code needs non-null
elements:

```java
interface ArrayDecoder<A extends @NonNull Object> { ... }
```

Annotations come from `org.checkerframework.checker.nullness.qual`. Do
**not** use `javax.annotation.Nullable` or
`org.jetbrains.annotations.Nullable` — the verifier ignores them and
the type use stays implicitly non-null, defeating the point.

## Null in, null out

`@PolyNull` ties several type uses in one signature together: at each
call site they are either all `@Nullable` or all `@NonNull`, decided
from the arguments. Reach for it when a method returns null exactly
when its input was null — the alternatives are two overloads, or a
`@Nullable` return that forces every caller to cast.

```java
public @PolyNull Timestamp toTimestamp(@Nullable Calendar cal,
    @PolyNull String s) throws SQLException
```

A caller passing a non-null `s` gets a non-null `Timestamp` with no
`castNonNull`. `cal` is annotated separately, so it stays nullable
either way.

## Method-by-method analysis

The checker verifies each method in isolation. A `@Nullable` field is
"could be null" on every method entry, regardless of any earlier check
in another method. Three options when that bites:

- `@MonotonicNonNull` on a field that starts null and is assigned once
  (lazy init, init-after-construct). Reads after the assignment don't
  need a cast.
- `@RequiresNonNull("field")` on a method whose caller is responsible
  for null-checking the field. The contract is enforced at every call
  site, so the body can read the field directly.
- `org.postgresql.util.internal.Nullness.castNonNull(value)` — or the
  two-arg overload with a message — when neither annotation fits and
  you genuinely know the value is non-null at that point. Prefer the
  message overload when the rationale is non-obvious; the message
  surfaces in the runtime error if you're wrong.

If you find yourself reaching for `castNonNull` more than once on the
same field in the same method, hoist it to a local variable and read
the local instead.

## Array types

The annotation binds to the type component immediately to its right
(JLS §9.7.4). Position changes meaning:

```java
String[] x;                       // array: non-null, elements: non-null
String @Nullable [] x;            // array: nullable,  elements: non-null
@Nullable String[] x;             // array: non-null,  elements: nullable
@Nullable String @Nullable [] x;  // both nullable
```

Multi-dim arrays follow the same rule for each `[]`.

## Overriding annotated JDK entries

The checker ships an annotated JDK with occasional wrong entries.
Override them with stub files under `config/checkerframework/`. The
extension must be `.astub` — any other extension is silently ignored.
