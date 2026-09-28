# Notion Plugin (detached)

**Status:** Source archive only — NOT part of the core build.

This directory holds the former `NotionClient` (Notion workspace integration),
extracted from the core app during the Phase 0 "cut the bloat" pass
(see `../PLAN-full-duplex.md` §3).

## Why it was detached

Notion note/task sync is a useful integration but not part of the core
voice-conversation loop. Removing it from the core shrinks the command surface
(`notion_*` handlers), the settings UI, and the offline phrase table, and it
removes a network integration (and its API token) from the always-on path.

## Not compiled

`plugins/` is intentionally **not** added to `settings.gradle(.kts)`. This file
is kept for reference and for a possible future **opt-in plugin**. It retains
its original `com.example.voicebrainlive.desktop.platform` package declaration.

## Re-attaching (future)

1. Move the source into a dedicated Gradle module (e.g. `:plugin-notion`).
2. Re-add the `notion_*` command handlers behind a settings toggle
   (default off), with the token stored via `ApiKeyStore` (DPAPI-encrypted).
3. Re-add only the settings UI fields that are needed — keep them out of the
   default settings screen.
