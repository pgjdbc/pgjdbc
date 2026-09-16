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

## Error messages

Use the word the code uses. Wrap user-facing exception text in `GT.tr`, which always formats the string with
`java.text.MessageFormat`, so double every single quote: `GT.tr("Can''t connect to {0}", host)`.

English is the source. `./gradlew :postgresql:generateGettextSources` updates the files under `translation/`
from it. Refreshing the translations is a separate i18n change, so do not run the task for a change that only
adds or reworks an English message. Never edit `messages_*.java` by hand.
