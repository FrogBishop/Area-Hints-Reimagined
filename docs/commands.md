# Command design

Players control their own hints. Each client tests its player's position against the active region data and displays the result locally. Authors can edit the JSON in the world save and reload it, use `add` and `del` for individual changes, or mark geometry in the in-game editor. [In-game region editor](region-editor.md)

The command root is `/ahr`. A path starts at the root of the command source's dimension, which for a player is their current dimension. The path argument is a JSON array of case-sensitive identifiers. For example, `["Keep","North Gate"]` has two segments and `[]` denotes the dimension root. A translated display name can change independently of this path. [Region storage format](storage-format.md#dimension-files-and-region-paths)

## Player commands

| Syntax | Behavior |
| --- | --- |
| `/ahr hints [state]` | With no argument, report the player's current setting. `on` enables automatic local detection and hints. `off` suspends them for that player. Keep this preference on the client across reconnects. |
| `/ahr here` | Immediately query the player's current floored coordinates, including between automatic samples or while automatic hints are off. Report the dimension, coordinates, full identifier path and localized plain name of every matching concrete region, including regions with their own hints turned off. |
| `/ahr list [path]` | List the identifiers and localized plain names of the direct children of the given path, starting at the dimension root when the path is omitted. |

After reloading a hand-edited file, `here` lets an author check the result at the point where they are standing. In the storage example, querying `(10, 1000, 0)` returns `["Keep","Keep"]` even though the parent sphere misses. Overlapping regions can produce several paths in the same response. [Example dimension file](storage-format.md#localized-display-names)

## World save commands

These operations act on the authoritative world save. In singleplayer that save is local. In multiplayer the server handles save operations for authorized authors and synchronizes the resulting data. Each receiving client maintains the subtree index used for its own detection. A player's hint preference is independent of save permissions.

| Syntax | Behavior |
| --- | --- |
| `/ahr reload` | Reread the per-save configuration and dimension files. Publish a successful load to connected clients, including region or dimension removals. Clients rebuild their subtree indexes from the saved region AABBs. If a file cannot be read or its JSON cannot be parsed, identify the file and keep the last successful load active. |
| `/ahr add <path> <fallback> <predicate-json>` | Create a region under the existing parent at the given path. The final argument is the complete `predicate` value with its `primitives` array and `expression` string. Store the fallback, compute the region's initial conservative AABB, and omit empty `children`. |
| `/ahr del <path> [subtree]` | Delete the named concrete region. The default accepts a leaf. For a node with children, an explicit `subtree` argument deletes the node and all descendants. |
| `/ahr aabb <scope> <path>` | Expose the two recalculation operations already in the storage design. `self` regenerates the named region's own box. `subtree` regenerates every region box in the chosen branch. Refresh the affected in-memory subtree bounds through the ancestors. |

### Adding a region

With `["Keep"]` in the current dimension, this example creates a spherical shell directly under it.

```text
/ahr add ["Keep","Bell"] "Bell Tower" {"primitives":[{"sphere":{"center":[10,64,0],"radiusSquared":25}},{"sphere":{"center":[10,64,0],"radiusSquared":4}}],"expression":"0 & !1"}
```

The command writes `name.fallback` as `"Bell Tower"`. An explicit `""` is also a valid fallback argument. The parser consumes the whole final JSON object and preserves the exact integer ranges of the file format. A malformed expression or a reference outside the supplied array produces an error before creation. Unreferenced primitives are valid and are saved. A missing parent or an existing sibling with the same identifier also produces an error. The save edit preserves other entries in the dimension file. [Expression syntax and reuse](storage-format.md#region-predicates)

### Reloading and recalculating

`reload` honors hand-tightened AABBs. Use `/ahr aabb self ["Keep","Bell"]` to regenerate just the new region's box or `/ahr aabb subtree ["Keep"]` to regenerate all region boxes in that branch. The generated boxes are written to the save. Ancestors outside the selected scope only need their in-memory subtree bounds refreshed on the clients. `subtree []` covers all concrete regions in the dimension. [AABB recalculation](storage-format.md#loading-and-recalculation)
