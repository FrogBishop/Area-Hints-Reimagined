# Area Hints Reimagined

Area Hints Reimagined is a Minecraft mod project for defining named regions and testing which ones contain a player. It revisits how the original mod organizes places within a dimension. [Original area-hints project](https://github.com/IMSng1019/area-hints)

**The first implementation targets Minecraft Java Edition 26.3 on Fabric.** Each dimension has its own JSON file. A region's path gives it an identity, its localized name is shown to players, and its Boolean expression gives it a shape. A child region can lie entirely outside its parent. Each player controls automatic hints on their own client, where all membership checks run. [Region storage format](docs/storage-format.md) · [Commands](docs/commands.md) · [In-game region editor](docs/region-editor.md)

Hints can show a full path of localized plain names with a styled Java Edition text component for the final region. Each region can turn its hint on or off. The client samples the player's position once per game second by default, with a detection interval shared across all saves and servers on that client. When several regions qualify at the same sample, the deepest one announces, with dimension-file order breaking ties. A save can also enable a return hint when a player leaves a descendant. [Hint presentation](docs/hint-presentation.md)

## Playing on Fabric 26.3

Launch Minecraft 26.3 with Java 25 and Fabric Loader 0.19.5 or later. Put Fabric API for 26.3 (0.162.0 or later) and the mod JAR in the instance's `mods` directory. For multiplayer, install the mod and Fabric API on the server and on each client that wants hints. The singleplayer world owner and multiplayer authors with game-master permissions can edit the save.

In **Options → Controls → Key Binds**, bind **Open Area Hints Menu** and **Mark Player Position**. Both start unbound. Open the menu, choose **New Region**, then **Properties** to enter an identifier and a fallback name. Choose **Add Sphere**, mark the center and a surface point, then reopen the menu and choose **Save Primitive**. Leave the expression at `0` and click **Save**. Use `/ahr here` to inspect the result at your feet.

The client keeps its preferences in `config/area-hints-reimagined.json`. **World hint settings** edits the presentation overrides in `<save-root>/area-hints-reimagined/config.json`. After editing world files by hand, use `/ahr reload`.

## Building and testing

Use JDK 25 and the Gradle wrapper. On Windows, run the following from the repository root.

```powershell
.\gradlew.bat :26.3:build
.\gradlew.bat :26.3:runClient
```

On macOS and Linux use `sh ./gradlew`. The installable JAR is in `versions/26.3/build/libs/`. The `:26.3:test` task exercises geometry, storage, sampling, editing and the multiplayer cache. `:26.3:runClientGameTest` opens a test client and verifies editing and synchronization in a temporary world.

The same game test can also connect twice to a temporary dedicated server on the loopback interface to exercise the multiplayer cache. If you accept the Minecraft EULA for that test server, pass `-PacceptMinecraftEula=true` to `:26.3:runClientGameTest`.

Stonecutter uses `src/` as the shared source tree. The version list and active node are in `settings.gradle.kts` and `stonecutter.gradle.kts`. The Fabric dependency pins for this target are in `versions/26.3/gradle.properties`.

## A region with a hollow center

The `Shell` region has no child regions, so it omits `children`. Its outer sphere has radius 5 and the spherical cutout has radius 2. Both spheres store the **squared** radius. The expression `0 & !1` selects points inside the first sphere and outside the second. A region can reuse any primitive by writing its index again.

```json
{
  "children": {
    "Shell": {
      "name": { "fallback": "Observatory Shell" },
      "predicate": {
        "primitives": [
          { "sphere": { "center": [0, 64, 0], "radiusSquared": 25 } },
          { "sphere": { "center": [0, 64, 0], "radiusSquared": 4 } }
        ],
        "expression": "0 & !1"
      },
      "aabb": { "x": [-5, 5], "y": [59, 69], "z": [-5, 5] }
    }
  }
}
```

The player at `(-2.1, 64, 0)` is tested at the integer point `(-3, 64, 0)`, which lies in the shell. The cutout removes `(2, 64, 0)` along with the rest of its surface. The outer surface includes `(5, 64, 0)`. All three points pass the region's bounding-box filter.

The design also supports vertical cylinders, prisms with concave outlines and holes, and polyhedra with triangular faces. Prism outlines and polyhedron surfaces can self-intersect. Each stores a boolean, `insideAtInfinity`, that says whether a point far beyond its boundary is inside. Each boundary crossing flips that state. For prisms the flag applies to the XZ footprint, and the height range is checked separately. [Geometry and editing reference](docs/storage-format.md#primitive-geometry)

## License

[GNU General Public License, version 3](LICENSE)
