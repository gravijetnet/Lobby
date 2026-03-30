---
globs: "**/listener/*.java"
description: This rule ensures that players cannot interact with doors,
  trapdoors, noteblocks, or any other interactive blocks while in the lobby,
  unless they are in build mode. It also ensures that lobby blocks can still be
  placed for building purposes.
alwaysApply: false
---

Always cancel all block interactions (right-click, left-click) for players not in build mode, except for placing lobby blocks. Use event.setUseInteractedBlock(Event.Result.DENY) to prevent interactions with clicked blocks while still allowing block placement when holding lobby blocks.