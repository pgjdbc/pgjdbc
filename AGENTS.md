# AGENTS.md

## Word choice in comments, documentation, and names

Use the right-hand column below in Javadoc, inline comments, error and log messages, `CHANGELOG.md`, `docs/`,
commit messages, and pull request descriptions.

| Instead of | Write | Note |
| --- | --- | --- |
| captured | recorded | Keep `capture` where something really is captured: traffic off a socket, a stack trace at the point of failure. |
| ceiling, cap | limit | As noun and as verb: limit a length, do not cap it. |
| desync, desynced | out of sync | |
| end-of-stream, end of the stream, EOF, end of file; the stream ends, has ended, is exhausted | end of stream | The state an `InputStream` reports by returning -1. |
| envelope | message | Name the part: the message, its header, or its body. |
| hard cap, soft cap | say what the limit does | Not `hard limit` or `soft limit` either. |
| legal | valid | |
| self-inclusive | say what the field counts | |
| upper bound on X | largest X | Keep `upper bound` where a lower bound is also in play, or the sentence is about a range. |

The list binds identifiers as well as prose: rename `fieldCap` to `fieldLimit` along with the comment beside it, and
`rejectsAFieldCountBeyondTheEnvelope` to `rejectsAFieldCountBeyondTheMessage`. This covers names you introduce and
names in code you are already changing. Do not open a renaming pass over untouched code.

Do not describe code with words borrowed from building or construction. Say the plain thing: critical, not
load-bearing; the key part, not the cornerstone; a check, not a guardrail. The terms the field already uses literally,
build, architecture, and framework, are fine; this is about the decorative ones.

These stay as they are:

- Names an outside standard fixes, whether Java, an RFC, or PostgreSQL itself: the SQL function `ceiling()`,
  `IllegalArgumentException`, frontend/backend protocol message and field names, GUC and connection-property names.
- Published API, where a name is a compatibility promise. Rename only through deprecation.
- Text the driver reproduces verbatim: quotations, log lines, wire data.

## Structure in comments and documentation

The scope is the same as the word choice list above: Javadoc, inline comments, `CHANGELOG.md`, `docs/`, commit
messages, and pull request descriptions. These rules are here because fluent prose no longer implies careful prose.
Text that reads well end to end can still contradict itself, name something that is not there yet, or state a
number it does not support, so check each rule below in a pass of its own rather than by reading the change through.

### In documentation and prose

- **Say a thing once.** Each fact gets one home, and every other place that needs it links to that home. If you
  find yourself writing a sentence you have already written elsewhere in the change, delete one of them.

- **Match the claim to the code.** Where the code enumerates cases, the documentation enumerates them too. A
  general characterisation that covers more than the code does is wrong even when it sounds right: if a setting
  governs six of nine limits, say which six rather than describing the property the six happen to share.

- **Every reference resolves where it stands.** Do not name something the reader has not met yet, unless the name
  is a link. Refer to a table row or column by its heading and never by its position, because "the last column"
  breaks the first time a column is added. Define every placeholder where you quote the message that contains it,
  so a quoted `{0}` becomes a named quantity rather than a bare letter.

- **One name per thing.** One term per concept and one verb per action, for the whole document. Where the code or
  the wire protocol already has a name, use that name rather than a paraphrase: write `ReadyForQuery` if that is
  what the error says, not "when the server reports it is ready". Varying the wording reads as variety to the
  writer and as a second mechanism to the reader.

- **Show the number.** Any claim about size or magnitude carries the arithmetic that supports it. "Orders of
  magnitude larger" is not a substitute for "64 times larger", and writing the ratio out is what catches the case
  where the ratio turns out not to support the claim.

- **One kind of thing per column.** A yes/no column holds yes or no. When a cell needs a condition, the condition
  goes in the prose under the table and the cell says which. Tables are for facts that are the same shape.

- **Stay inside the audience.** Content under a heading is what the heading says it is, and documentation a user
  reads names only things a user can reach: connection properties, SQLStates, message text. Internal method and
  field names belong in Javadoc, where the reader can act on them.

### In comments and Javadoc

Javadoc already enforces some of the above through its tags and the build, so the rules that need stating here are
the ones no tool checks.

- **Match the claim to the code.** The body is on the next line, so a comment that describes a wider intent than
  the guard implements is both easy to check and impossible for a reader to defend against. This is the rule that
  goes stale, too: when you change a method, re-read every comment that names what you changed, which is a grep
  for the identifier rather than a judgement call.

- **Say a thing once, and never what the code already said.** The fact lives at the declaration that owns it, and
  other mentions link there: the constant carries its value and its reason, and the class summary says where limits
  come from without repeating any of them. `@param msgLen the message length` costs a line and adds nothing to the
  signature.

- **Let the tags carry names and numbers.** Write `{@link}` for an identifier and `{@value}` for a constant rather
  than typing either, because the build then fails when the target moves and the number cannot drift from the
  field. Never refer to position: "the check above" is wrong after any edit that adds a check.

- **Use the name the code uses.** If the field is `messageBoundaryPosition`, the comment beside it says message
  boundary position, not sync point or framing position. The word choice list above is this rule applied to a
  fixed vocabulary.

- **Write to the reader that comment has.** Javadoc on public API addresses a caller who cannot see the body, so
  it must not depend on the body or name private fields. An inline comment addresses whoever is editing that line,
  so naming internals is exactly right there. Writing either one in the other's register fails both readers.

- **Name the constraint rather than narrating the mechanism.** The mechanism is visible in the code and will
  change; the constraint that made the code take this shape is invisible and usually will not. A comment saying a
  failed `COPY` cannot skip a message because there is no later step to fail the operation survives a rewrite of
  the method it sits in.

## Error messages

Use the word the code uses. Wrap user-facing exception text in `GT.tr`, which always formats the string with
`java.text.MessageFormat`, so double every single quote: `GT.tr("Can''t connect to {0}", host)`.

English is the source. `./gradlew :postgresql:generateGettextSources` updates the files under `translation/`
from it. Refreshing the translations is a separate i18n change, so do not run the task for a change that only
adds or reworks an English message. Never edit `messages_*.java` by hand.

Document a failure the same way every time: the SQLState, the text the caller sees, and the cause if the driver
wraps one. Giving the SQLState for some documented errors and not others leaves the reader unable to tell whether
the omission means the error is different or means nobody wrote it down.
