---
globs: "**/listener/*.java"
description: This rule ensures that Ender Butt pearls cannot be used to glitch
  through blocks in the lobby. It requires multiple layers of collision
  detection and safety measures.
alwaysApply: false
---

Always implement comprehensive collision detection for Ender Butt pearls including: line collision checks between previous and current positions, player body collision box checks, speed limiting, and immediate removal when near solid blocks. Store full previous location, not just Y coordinate.