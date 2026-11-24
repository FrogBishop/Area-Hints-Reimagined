# Region storage format

A **region** is an entry in a dimension's region tree. Its path identifies it, and its localized display name is shown to players. Its **predicate** decides whether an integer point belongs to it. A **primitive** is one geometric part that the region's expression can reference by index.

## Dimension files and region paths

Each dimension has one JSON file. Its root represents the entire, conceptually unbounded dimension. The root can have a plain display name, hint presentation settings and a `children` map. Each of these root fields is optional. Each concrete region uses the fields below.

| Region field | Presence | Meaning |
| --- | --- | --- |
| `name` | Required | The plain-text display name, with a fallback string and any language-specific translations. |
| `hint` | Optional | Whether this node can announce itself, plus its rich text, animation and relative HUD position. [Hint presentation](hint-presentation.md) |
| `predicate` | Required | The region's ordered `primitives` array and Boolean `expression` string. |
| `aabb` | Required | A conservative axis-aligned bounding box for this region's own matches. |
| `children` | Optional | A JSON object mapping each direct child's identifier to its region. Its written member order breaks ties between equal-depth hint candidates. |

At the root or in any region, omitting `children` is equivalent to writing `"children": {}`. Prefer the shorter form when a node has no child regions. An empty dimension using its default presentation can still be written as `{}`.

A region's identity is its full path from the dimension root. Each segment is a case-sensitive child identifier, unique among that parent's children. In one file, `["Spawn"]`, `["spawn"]` and `["Spawn", "Spawn"]` can all identify different regions. Different parents can each have a child called `Gate`. The root has depth 0. Each child's depth is its parent's depth plus one, equal to the number of segments in its path.

Reordering siblings preserves their identities and geometry, and can change which equal-depth hint wins. Readers, save edits and synchronization must preserve the source order of region entries for that comparison. [Hint selection](hint-presentation.md#selecting-a-hint-on-movement)

### Localized display names

Display strings are grouped under `name`. The `fallback` field holds a literal string. The optional `translations` object maps Minecraft language codes to strings. The client looks up the player's current game language, so two players can see different names for the same region path. Authors can change or reuse display names independently of the case-sensitive identifiers.

| Node | Presence of `name.fallback` | Display lookup, in priority order |
| --- | --- | --- |
| Concrete region | Required, even when the value is `""` | Exact language entry, then the region's fallback. |
| Dimension root | Optional | Exact language entry, then the root's fallback if present, then the dimension's default name in the player's language. |

Use the first present string. An explicit `"fallback": ""` yields an empty display name when the language entry is absent. A root may supply translations alone to override selected languages. Omitting its `name` uses the localized dimension name in every language, such as Overworld, Nether or End.

In this complete Overworld file, a parent and its child share an identifier. The child's cylinder lies entirely outside the parent's sphere.

```json
{
  "name": { "translations": { "zh_cn": "北境" } },
  "children": {
    "Keep": {
      "name": { "fallback": "North Keep", "translations": { "zh_cn": "北堡" } },
      "predicate": {
        "primitives": [{ "sphere": { "center": [0, 64, 0], "radiusSquared": 4 } }],
        "expression": "0"
      },
      "aabb": { "x": [-2, 2], "y": [62, 66], "z": [-2, 2] },
      "children": {
        "Keep": {
          "name": { "fallback": "" },
          "predicate": {
            "primitives": [{ "cylinder": { "center": [10, 0], "radiusSquared": 1, "y": "unbounded" } }],
            "expression": "0"
          },
          "aabb": { "x": [9, 11], "y": "unbounded", "z": [-1, 1] }
        }
      }
    }
  }
}
```

With `zh_cn` selected, the client displays the root and parent translations. With `en_us` selected, the root uses the default English Overworld name and the parent displays `North Keep`. Adding `"fallback": "The Marches"` to the root's `name` would give the English player that custom name and keep the Chinese translation. The child's display name is empty in both languages.

The point `(10, 1000, 0)` belongs to the child at `["Keep", "Keep"]`. The parent fails its own test there. Its in-memory subtree box still admits the point because it covers both regions.

## File locations

Keep the authoritative dimension files in the active world save, both in singleplayer and on a dedicated server. This makes a world's region definitions travel with its backups. A multiplayer client can keep replaceable synchronized copies in its game instance's cache.

| Copy | Path |
| --- | --- |
| World save | `<save-root>/area-hints-reimagined/regions/<namespace>/<dimension-path>.json` |
| Multiplayer client cache | `<game-dir>/cache/area-hints-reimagined/servers/<server-key>/regions/<namespace>/<dimension-path>.json` |

For example, `minecraft:overworld` maps to `regions/minecraft/overworld.json` under either location. The namespace keeps dimensions from different mods distinct. The server key is the hexadecimal SHA-256 digest of the normalized connection address, including its port.

The cache keeps the latest confirmed copy per server and dimension. On connection or a server world change, the server identifies its current dimension files. A client can reuse a cached file when the server confirms its content digest, fetch a replacement when it differs, and drop unadvertised dimensions from the active set. This handles a different world served at the same address. Players can clear the cache and download it again. Persistent edits belong in the world save.

One additional JSON configuration belongs to the whole save. It can independently override rich-text use, full-path display, the separator and whether to show an ancestor hint when leaving a descendant. The client applies its own global settings for omitted keys, including when loading synchronized server data. [Hint settings and inheritance](hint-presentation.md#settings-and-inheritance)

The automatic detection interval belongs exclusively to the player's local global configuration. All saves and servers use that same client preference. [Automatic detection interval](hint-presentation.md#automatic-detection-interval)

## Integer coordinates and boundaries

Each player's client performs all position and region membership checks locally. Convert each player coordinate `v` to an integer with the Java expression `(int) Math.floor(v)`. For example, `-0.2` becomes `-1`. Use that same integer point for the bounding-box filter and every primitive test. Coordinates beyond 30,000,000 are supported throughout the signed 32-bit range.

| Quantity | Stored values |
| --- | --- |
| Coordinates, vertices, centers and finite interval endpoints | Signed 32-bit integers from `-2147483648` through `2147483647` |
| `radiusSquared` for spheres and cylinders | Unsigned 64-bit integers from `0` through `18446744073709551615` |

Read and write these as exact JSON integers. `radiusSquared` may be zero or any non-square value such as `2`. Geometry calculations after coordinate conversion use exact integer arithmetic. Intermediate differences, products and sums can require more bits than a stored field. Precision analysis for future features using floating-point geometry belongs with the design of those features.

A primitive includes its geometric boundary. Finite intervals include both endpoints. Boolean negation (`!`) flips the result for every query point, including a point on a primitive's surface.

In the 26.3 implementation, a coordinate difference fits in a signed 64-bit value. Its square fits in 64 unsigned bits. The radial test compares each square to the remaining unsigned radius budget before subtracting it. The editor uses an arbitrary-precision sum when deriving a new radius and reports an out-of-range measurement before saving. The bounds generator uses an integer square root.

Polygon orientations and triangle plane tests use arbitrary-precision integer determinants. For a ray along positive X, projected triangle edges through the query need a consistent crossing rule. The implementation evaluates the sign at the symbolic offset `(y + ε, z + ε²)` by comparing integer coefficients in order. It checks real boundary membership separately. No numerical epsilon is added to a coordinate.

## Region predicates

A `predicate` object contains an ordered `primitives` array and an `expression` string. Each array entry is an object with one type key (`sphere`, `cylinder`, `prism` or `polyhedron`) and its parameters. The first entry has index `0`. A reference always uses the array belonging to the current region. The expression `"0"` selects its first primitive.

The operators, from highest to lowest precedence, are prefix `!` (NOT), infix `&` (AND) and infix `|` (OR). Parentheses group a subexpression. In the grammar below, braces denote repetition zero or more times, and `digit` is an ASCII decimal digit.

```text
expression  = conjunction { "|" conjunction }
conjunction = unary { "&" unary }
unary       = "!" unary | "(" expression ")" | index
index       = digit { digit }
```

Whitespace may separate tokens. Read consecutive digits as one decimal index, so `10` refers to the eleventh entry. A valid expression parses in full and each reference satisfies `0 ≤ index < primitives.length`.

For example, `!0&1|2&(!3|!1)` groups as `((!0) & 1) | (2 & ((!3) | (!1)))`. Both occurrences of `1` refer to the same cylinder at the same integer query point.

```json
{
  "primitives": [
    { "sphere": { "center": [0, 64, 0], "radiusSquared": 9 } },
    { "cylinder": { "center": [0, 0], "radiusSquared": 4, "y": [62, 66] } },
    { "sphere": { "center": [2, 64, 0], "radiusSquared": 4 } },
    { "sphere": { "center": [1, 64, 0], "radiusSquared": 4 } },
    { "sphere": { "center": [100, 64, 0], "radiusSquared": 0 } }
  ],
  "expression": "!0&1|2&(!3|!1)"
}
```

The point `(1, 64, 0)` belongs to primitives 0 through 3 and the expression is false. At `(3, 64, 0)` those same tests are true except for the cylinder, so `!1` makes the right-hand branch true.

An array may retain unreferenced entries, such as the sphere at index `4`. Only referenced entries contribute to membership and the generated region AABB. If an edit changes array positions, update the affected references to keep them pointing to the intended geometry. [Complete dimension with a spherical cutout](../README.md#a-region-with-a-hollow-center)

## Primitive geometry

Each `prism` and `polyhedron` stores an explicit JSON boolean, `insideAtInfinity`. It gives the membership of the space far beyond all outline edges in the XZ plane or all triangular faces in 3D. Infinity serves as a geometric reference for ordinary integer queries.

For a point off the boundary, start with that boolean and toggle it once per boundary layer crossed on the way to the point. An even crossing count gives the stored value. An odd count gives its opposite. Equivalently, membership is `insideAtInfinity XOR (crossings % 2 == 1)`. Points on the actual boundary match for either value. For a prism, this rule determines the footprint, and the point must also pass the Y test.

Prism outlines and polyhedron surfaces may self-intersect. The editor and runtime skip self-intersection validation.

### Sphere and cylinder

| Primitive | Fields | Membership |
| --- | --- | --- |
| `sphere` | `center: [x, y, z]`, `radiusSquared` | The squared distance to the center is at most `radiusSquared`. |
| `cylinder` | `center: [x, z]`, `radiusSquared`, `y` | The squared distance in the XZ plane is at most `radiusSquared` and the point passes the height test. |

Both cylinders and prisms extend along the Y axis. Their `y` field accepts an inclusive interval `[low, high]` or the literal `"unbounded"`. The literal skips the height test altogether. For a cylinder centered at `[0, 0]` with `radiusSquared` equal to `25` and unbounded height, `(3, y, 4)` matches at every representable `y`.

### Prism

A prism stores its closed walk of integer `[x, z]` points in `vertices`, along with the footprint's `insideAtInfinity` boolean and a separate `y` range. Consecutive vertices form edges, and the last vertex connects back to the first. Starting the same closed walk at a different vertex or traversing it in reverse preserves its edges and its footprint. Rearranging vertices in a way that connects different neighbors can change the outline.

The walk can describe a concave footprint and holes. To trace a hole, cross a bridge, follow the hole's rim in either direction, then return along the same bridge. The two bridge traversals cancel. Points on the bridge follow the surrounding footprint, and points on a real rim count as boundary hits.

Here the outer triangle is `A B C`, the hole is `D E F`, and the stored walk is `A B C D E F D C`. The final `C` supplies the return trip from `D` before the outline closes to `A`.

```json
{
  "prism": {
    "vertices": [
      [0, 0], [0, 12], [12, 0],
      [8, 2], [4, 6], [4, 2], [8, 2],
      [12, 0]
    ],
    "y": [60, 70],
    "insideAtInfinity": false
  }
}
```

At `y = 65` the hole excludes `(5, 65, 3)` and its rim includes `(5, 65, 2)`. Changing `insideAtInfinity` to `true` includes the hole interior and the unbounded exterior of the outer triangle. The rim still matches. Both settings exclude every point at `y = 71`.

#### A self-intersecting outline

Take `A=(0,0)`, `B=(10,10)`, `C=(10,0)` and `D=(0,10)` in XZ. The walk `A B C D` closes from D to A and crosses itself at `(5,5)`. Along `z = 4`, a point moving from left to right crosses edges at `x = 0, 4, 6, 10`. The two lobes both have odd crossing parity.

| `insideAtInfinity` | Left lobe `(2,4)` | Right lobe `(8,4)` | Beyond both lobes `(-1,4)` | Crossing `(5,5)` |
| --- | --- | --- | --- | --- |
| `false` | Hit | Hit | Miss | Hit |
| `true` | Miss | Miss | Hit | Hit |

All four queries use a height inside the prism's range. The walks `B C D A` and `A D C B` give the same table.

### Polyhedron

A polyhedron stores integer `[x, y, z]` points in `vertices`, triangles in `faces` and the far-field membership in `insideAtInfinity`. Each entry in `faces` contains three zero-based indices into `vertices`. For example, `[0, 1, 2]` references the first three vertices. The vertex array may also retain points used by zero faces for later editing. The referenced triangles define the surface. Reordering faces or permuting any triangle's three indices preserves every membership result. Reindexing the vertices also preserves the result when the face references are updated to match. [Editing polyhedron points](region-editor.md#marking-and-editing-points)

```json
{
  "polyhedron": {
    "vertices": [[0, 0, 0], [4, 0, 0], [0, 4, 0], [0, 0, 4]],
    "faces": [[0, 2, 1], [0, 1, 3], [0, 3, 2], [1, 2, 3]],
    "insideAtInfinity": false
  }
}
```

With `insideAtInfinity` set to `false`, crossing an outer shell once enters the selected solid. Crossing a nested shell a second time enters a cavity. Setting the flag to `true` selects the far field and the cavity instead. In both cases the shells themselves match, including their edges and vertices.

#### Intersecting shells

Use the surfaces of two triangulated cubes, `[0,10]³` and `[-2,2]³`, in a single polyhedron. Away from their surfaces, a point in exactly one cube has odd crossing parity. A point in both cubes or outside both has even parity. The point `(0,2,1)` lies on both shells and always matches.

| `insideAtInfinity` | In both cubes `(1,1,1)` | In the larger cube only `(5,5,5)` | Outside both cubes `(20,20,20)` | Shell intersection `(0,2,1)` |
| --- | --- | --- | --- | --- |
| `false` | Miss | Hit | Miss | Hit |
| `true` | Hit | Miss | Hit | Hit |

## Stored AABBs and subtree filtering

The saved `aabb` must admit **every integer point matched by its region**. Extra admitted points are allowed. Passing the box sends the point on to the predicate. A user may tighten a saved box by hand, down to the smallest box covering the region's matches.

| Box value | Coarse test |
| --- | --- |
| `"empty"` | Reject every point. Write the string once for the whole box. |
| An object with `x`, `y` and `z` | For each finite interval `[low, high]` require `low ≤ coordinate ≤ high`. Skip every axis set to `"unbounded"`. |

A finite interval has `low ≤ high`. A single-coordinate extent is `[k, k]`. Setting all three axes to `"unbounded"` passes every query to the predicate. A region's own `"empty"` box can coexist with populated children.

### Generating a box

Automatic generation takes linear time in the size of the expression and primitive data. Compute the box of each referenced primitive once and reuse it at every occurrence of its index. Intersecting operand boxes gives a safe box for `&`. Enclosing the operand boxes gives a safe box for `|`. For `!`, allowing all three axes is safe when a tighter result would require more work. An operand whose box passes every point can still have holes.

For a polyhedron with `insideAtInfinity` set to `true`, setting all three axes to `"unbounded"` gives a safe box. For a prism with the flag set to `true`, set X and Z to `"unbounded"` and copy the height range to Y. The prism in the hole example can then use `{"x": "unbounded", "y": [60, 70], "z": "unbounded"}`.

Write `"empty"` when emptiness is established. For example, a sphere at the origin with `radiusSquared = 18446744073709551615` covers the entire query domain. Its farthest signed 32-bit corner is `(-2³¹, -2³¹, -2³¹)`, at squared distance `3 × 2⁶²`. Its complement can therefore use `"empty"`. Choosing the farther coordinate limit on each of the three axes gives the same constant-time coverage test for any sphere center. Each squared difference fits in 64 unsigned bits, but the sum can require 66. Comparing each term to the remaining squared-radius budget before subtracting it keeps the test exact.

A conservative box may continue to pass candidates for an empty expression whose emptiness is harder to establish.

### Loading and recalculation

On load, build an in-memory index of subtree AABBs from the saved region boxes. Each subtree box encloses the node's own box and the boxes of all its descendants. A subtree-box miss skips the whole branch. A miss against the node's own box skips just that region's predicate.

| Player-selected operation | Saved boxes to recompute | In-memory boxes to update |
| --- | --- | --- |
| Recalculate one region | The selected region's own AABB | The selected node and its ancestors |
| Recalculate a subtree | Every region's own AABB in that subtree | The subtree and its ancestors |

Ancestor bounds can shrink as well as grow. Loading honors hand-edited boxes. An explicit recalculation can replace a hand-tightened box with a broader generated one.

## Geometry editing

To switch the selected side of a prism footprint or polyhedron, toggle its `insideAtInfinity` field. The stored region box and the runtime subtree boxes must cover the edited predicate. Real boundary points match for either flag value. Applying `!` to the primitive's index flips those boundary hits too.

The polyhedron geometry check counts uses of each undirected edge across its triangles. An odd count is an error. Counts of two, four, six and higher even numbers are allowed.

The mod's editing operations produce valid geometry. Runtime membership checks trust the saved geometry and omit this editor check. Hand-edited invalid regions have undefined behavior, including a possible crash.
