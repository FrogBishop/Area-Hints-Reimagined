# Hint presentation

The client builds a hint for the region being announced. Each region keeps its localized plain-text `name` for paths, the region picker and commands. A region can also supply localized Java Edition text components for its own hint. The dimension root has the same presentation options when it is the target. The player's `/ahr hints on` or `off` preference controls automatic hints. [Plain-name lookup](storage-format.md#localized-display-names)

## Settings and inheritance

The player's global configuration and the save-wide configuration use the key `hints`. The optional `hint` field belongs to a region or the dimension root. Resolve each setting independently by taking the first place that supplies the key.

| Setting | First lookup | Fallback |
| --- | --- | --- |
| `useRichText` | Save-wide `hints` | Player's global `useRichText` |
| `showFullPath` | Save-wide `hints` | Player's global `showFullPath` |
| `separator` (literal string) | Save-wide `hints` | Player's global `separator` |
| `showAncestorOnExit` | Save-wide `hints` | Player's global `showAncestorOnExit`, initially `false` |
| `enabled` | The node's own `hint` | `true` |
| `animation` | Target region or root's `hint` | Player's global `animation` |
| `position` | Target region or root's `hint` | Player's global `position` |

An explicit `false` turns off the corresponding option. An explicit empty separator joins adjacent segments directly. For settings with a player-global fallback, the client uses its current local value whenever the override is absent. Multiplayer synchronization preserves omitted fields.

For example, a save can enable rich targets, full paths and ancestor hints on exit.

```json
{
  "hints": {
    "useRichText": true,
    "showFullPath": true,
    "separator": "·",
    "showAncestorOnExit": true
  }
}
```

## Automatic detection interval

The client reads `detectionIntervalSeconds` from the top level of the player's global configuration. One value applies to every local save and multiplayer connection. Its default is one game second, including when the key is absent.

```json
{ "detectionIntervalSeconds": 1 }
```

The slider is continuous from **0.0625 to 16 game seconds**, with **1 second at the midpoint**. Let `p` be the fraction of the track measured from the left, from 0 to 1. The period in seconds is `T = 2^(8p − 4)`. To reopen the slider at a stored period, use `p = (log2(T) + 4) / 8`. Keep the full period in the configuration and round only its displayed label.

| Position along the slider | 0% | 12.5% | 25% | 37.5% | 50% | 62.5% | 75% | 87.5% | 100% |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Period in game seconds | 0.0625 | 0.125 | 0.25 | 0.5 | 1 | 2 | 4 | 8 | 16 |

Moving one eighth of the track to the left halves the period. Moving the same distance to the right doubles it, including between the marked values. For example, halfway between the 1- and 2-second marks the period is `√2` seconds.

Count 20 advancing game logic ticks per game second. While automatic detection is active, accumulate one tick per client logic step. When the accumulator reaches `20 × T`, sample the player's current integer position once and retain the remainder modulo `20 × T`. This preserves fractional-tick periods. With a sample at tick 0, the minimum period of 1.25 ticks gives subsequent samples at ticks 2, 3, 4, 5 and 7. A pause in game logic also pauses the countdown. [Java Edition tick rate](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-20-3)

Use successive automatic samples to select entry and return hints. A player who crosses an entire region between samples can pass through it without triggering a hint. `/ahr here` queries the current point immediately and leaves the automatic sampling schedule and its previous match set intact.

## Selecting a hint on movement

A region or dimension root allows automatic hints by default. Set `"hint": { "enabled": false }` on a node to silence that target. Its descendants use their own flags. Its plain name still occupies its segment in a descendant's full path, and `/ahr here` still reports every matching concrete region, including silenced ones. The player's `/ahr hints off` switch suspends all automatic hints.

For two successive automatic samples in the same dimension, form the **previous** and **current** sets of nodes that both match the sampled position and allow hints. Include an enabled dimension root at every position in that dimension. Keep all eligible matches in each set, including regions that lost a previous priority tie.

1. Add every newly entered eligible region (`current − previous`) to the candidates.
2. When `showAncestorOnExit` is on, consider each exited eligible region (`previous − current`). Follow its parent chain to the first ancestor in `current`. If that ancestor also belongs to `previous`, add it as a return candidate. If it has just been entered, step 1 already includes it. A branch with no currently eligible ancestor contributes no return. Collect candidates by region path so multiple exits nominate a shared ancestor just once.
3. Select one candidate of greatest **depth**. The dimension root has depth 0 and each child has its parent's depth plus one. Every path segment counts, including ancestors with hints turned off. For equal depths, select the candidate whose region entry appears first in the dimension JSON. Equivalently, traverse each `children` object in written member order, visiting a node before its descendants. Apply this priority to entry and return candidates together. An empty candidate set produces no new hint.

For example, let the root's children appear as A then E in the file. A's children appear as C then D, and B is a child of C. The table lists **geometric** matches among concrete regions so it is visible when a disabled node is skipped. The root is enabled and `showAncestorOnExit` is on.

| Previous matches | Current matches | Disabled node | Candidates | Selected hint |
| --- | --- | --- | --- | --- |
| A, B | A | None | A | A |
| A, C, B | A, C | None | C | C |
| A, C, B | A, C | C | A | A |
| A, B | A | B | None | None |
| A, C, B | A | None | A (once) | A |
| A, B | A, D | None | A, D | D (depth 2) |
| A | A, C, D | None | C, D | C (earlier in the file) |
| A, B | A, E | None | A, E | A (earlier in the file) |
| B | None | None | Root | Root (depth 0) |

With the return setting off, moving from A and B to A alone produces no hint. Entering D on that same move still produces D's ordinary entry hint. If the root is silenced, the last row has no candidate.

## Localized components and paths

When rich text is enabled, look up the target's `hint.richText.translations` entry for the current game language. If it is absent, use the optional `hint.richText.fallback` component. If both are absent, use the target's resolved plain name as literal text. A present component that displays an empty string still supplies the target segment. When rich text is disabled, use the plain name directly. Each rich value is a native Java Edition text component embedded in the JSON. Its styles and nested components stay attached when it is added to the hint. [Minecraft text components](https://docs.fabricmc.net/develop/text-and-translations)

Suppose the dimension root's plain name is `C`, the parent's is `B` and the target's is `A`. The following is the value of the target's `hint` field. Its fallback displays `AAA` in red, and it supplies a separate French component.

```json
{
  "richText": {
    "fallback": { "text": "AAA", "color": "red" },
    "translations": { "fr_fr": { "text": "Quai", "color": "gold" } }
  },
  "position": [0.5, 0.25]
}
```

With full paths enabled, walk the target's identifier path from the dimension root. Emit each ancestor's localized **plain name** as a literal component, then emit the target's selected component once. Insert the literal separator between every pair of segments, preserving empty segments. This follows the region tree even when a parent misses the player's position. A root announced as the target has a single segment. With full paths disabled, the hint consists solely of the target's selected component.

For a player using `en_us` and the separator `·`, the example has four possible displays.

| Full path | Rich text | Visible text | Red run |
| --- | --- | --- | --- |
| On | On | `C·B·AAA` | `AAA` |
| On | Off | `C·B·A` | None |
| Off | On | `AAA` | `AAA` |
| Off | Off | `A` | None |

For the first row, the following composed component keeps the ancestor names and separators in the ordinary hint style. If the parent's name resolves to the empty string, the same rule yields `C··AAA`.

```json
{
  "text": "",
  "extra": [
    { "text": "C" }, { "text": "·" }, { "text": "B" }, { "text": "·" },
    { "text": "AAA", "color": "red" }
  ]
}
```

A server supplying only `showFullPath: true` can produce different displays for two players of the same language. One player's global rich-text setting and separator give `C·B·AAA` with a red final segment. Another player's plain-text setting and slash separator give `C/B/A`.

## Position and animation

In **Global Settings** and **Properties**, enter **HUD X** and **HUD Y** separately. The placeholder on each axis shows its inherited or default value. Clearing both fields restores the inherited position, or the client preset `[0.5, 0.25]` in global settings. Entering just one axis stores the complete pair using the other axis's displayed value at the time of editing.

Choose an effect in **Animation**, then open **Animation parameters** to edit its timings or motion. For example, **Instant** exposes only the hold duration, and **Wipe** also lets you select the reveal direction. An empty parameter uses the applicable global value or effect preset. **Use global animation** in a region clears its animation override.

Store `position` as a normalized GUI coordinate pair `[x, y]`, with each value in `[0, 1]`. The top-left corner is `[0, 0]` and the bottom-right corner is `[1, 1]`. Center the **entire composed hint**, including the path, at `(x × W, y × H)` in the current scaled GUI of width `W` and height `H`. For measured text dimensions `w` and `h`, the top-left drawing position is `(x × W − w / 2, y × H − h / 2)`. Recalculate it on a window resize or GUI scale change. The example's `[0.5, 0.25]` gives centers of `(400, 120)` in an 800 × 480 GUI and `(640, 180)` in a 1280 × 720 GUI.

Apply the target's effective animation to the composed hint as a whole. In the example, the target chooses its position and inherits the viewing player's animation. Measure the complete localized text, including separators and styled runs, before starting an effect. The configured center is where the hint rests during its hold phase.

### Genshin

`Genshin` starts with the hint fully transparent and slightly above its resting position. It moves down to the target as it becomes opaque, then holds there. On exit it rises back to its starting height while fading to transparent, tracing the entrance in reverse. Apply the opacity multiplier to the composed component so each run keeps its own color and styling.

Each animation type owns its parameters. The following `hint.animation` value gives the entrance and exit equal durations. Durations are in client playback milliseconds, and the vertical offset is measured in rendered text line heights.

```json
{
  "type": "Genshin",
  "enterMs": 400,
  "holdMs": 1800,
  "exitMs": 400,
  "offsetLines": 0.75
}
```

For a resting center at `Y` and an offset of `d` GUI units, the center moves from `Y − d` to `Y`. The exit retraces the same path and opacity curve. Equal entrance and exit durations give an exact time reversal. The hold starts once the hint is fully visible. A zero-duration transition takes effect immediately.

The whole `animation` setting falls back to the player's local global selection when omitted, including for synchronized server data. For a partial animation object, resolve omitted parameters from the player's settings for the selected type, then its preset. For example, a player using `Genshin` with a longer hold can keep that timing when a region overrides only `offsetLines`.

### Instant

`Instant` displays the complete hint immediately at its resting center and full opacity. It holds for `holdMs` and disappears in one step. The hold is its only timing parameter. For the example below the hint is visible from the trigger up to, but excluding, the 1,800 ms endpoint.

```json
{ "type": "Instant", "holdMs": 1800 }
```

### Other animations

| Type | Motion and useful setting | Where it fits |
| --- | --- | --- |
| `Fade` | Keep the center fixed and fade the whole hint in and out. Set the entrance, hold and exit durations separately. | Streets and neighboring rooms where hints appear often. |
| `Wipe` | Reveal the laid-out text with a moving clip edge, then close the clip on exit. Choose an `origin` of `left`, `right` or `center`. A center opening reveals both halves at once. | A gate or a long place name unfolding across the screen. |
| `Zoom` | Fade in while scaling about the resting center from `fromScale` (try `0.90`) to full size. Shrink and fade on exit. | A landmark that merits a brief emphasis. |
| `Typewriter` | Reveal whole displayed characters at `graphemesPerSecond` (try `14`), hold after the final character, then fade the whole hint out. Reserve the complete layout during the reveal. | A chapter title or a discovery that calls for a slower reading pace. |

PowerPoint's fade, wipe, zoom and by-letter effects are visual references for these animations. [Microsoft's effect gallery](https://support.microsoft.com/en-us/powerpoint/animation-effects-available-in-powerpoint-for-the-web) · [Microsoft's by-letter animation example](https://support.microsoft.com/en-us/powerpoint/animate-or-make-words-appear-one-line-at-a-time-in-powerpoint)
