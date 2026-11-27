# Mod Update Checker

A Minecraft 1.8.9 [Ornithe](https://ornithemc.net) mod that checks every installed mod for updates and can install them for you.

- **Modrinth first.** Every jar in `mods` is looked up by its SHA-1 hash in one request, filtered to Ornithe/Fabric versions for your Minecraft version. It never offers an older version as an update.
- **GitHub for the rest.** Mods that aren't on Modrinth are checked against the latest GitHub release of the repository linked in their `fabric.mod.json`.
- **Safe installs.** Downloads are checked against their published hash, and must be a Fabric/Ornithe mod with the same mod id, so a wrong link or a Forge release can't replace your mod. Jars are swapped after the game closes, since Windows locks a running game's jars.
- **Updates itself** the same way, from this repository's releases.
- **Finds mod problems.** Several copies of one mod, and jars made for another loader (which Ornithe never loads), are listed with a button to move them out of `mods` into `removed-mods`. This replaces the Duplicate Mod Warning mod, which it offers to remove.
- **Keeps Prism's metadata right.** In a Prism Launcher instance, a mod updated from Modrinth gets a `mods/.index/*.pw.toml` file for its new jar (replacing the old jar's), so Prism lists it as from Modrinth instead of "Unknown" and can update it itself.

## Requirements

- Minecraft 1.8.9 with Ornithe (Fabric Loader 0.19.5+, OSL)
- [PolyCompose](https://modrinth.com/mod/polycompose) 3.0+ (draws the in-game screen)
- Java 25
- Optional: [OneConfig](https://modrinth.com/mod/oneconfig) 1.9+ (adds a settings page, and the screen follows its theme)

## In game

- A **Mod Updates** button on the title screen and pause menu (yellow with a count when there are updates) opens the updates screen. There you can see changelogs, open a mod's page, ignore a version, update one mod, or **Update all**.
- When the mods folder has problems, the screen opens by itself on the title screen at its **Problems** tab, and the button turns red (**Mod Problems**).
- With OneConfig: a **Mod Update Checker** page (Utility, or `/modupdates`) with Open, Update All and Check Again, plus the settings.
- The screen follows your GUI scale and, with OneConfig, its theme, accent colour, transparency and background blur.

## Before launch (Prism Launcher)

The update check can also run before the game starts, as a pre-launch command. In Prism: **Edit instance → Settings → Custom commands**, tick the box, and set **Pre-launch command** to:

```
"$INST_JAVA" -cp "$INST_MC_DIR/config/modupdatechecker/prelaunch.jar" com.ootruffle.modupdatechecker.prelaunch.PrelaunchCheck --gui
```

The mod copies itself to `config/modupdatechecker/prelaunch.jar` each time the game starts, so the command keeps working when it updates. Start the game once with the mod before adding it, or Prism will refuse to launch.

`--gui` opens a window listing the updates to tick (**Changes** shows what's new in each), with **Update N & launch**, **Launch without updating** and **Quit** (which cancels the launch). Problems in the mods folder are listed above the updates (see [Mod problems](#mod-problems)). It closes by itself when there's nothing to update or fix. Other options:

| Option | What it does |
| --- | --- |
| *(none)* | Just lists updates in Prism's launch log |
| `--install` | Installs every update without asking, after moving extra copies of a mod out |
| `--verbose` | Lists every mod, not just updates |
| `--timeout <s>` | Gives up on the check after this many seconds (default 60) |

Errors, being offline and running past the timeout never stop the launch; only **Quit** does.

When the game was started by Prism, the in-game screen can also set this up for you (**Set up**). Prism keeps its settings in memory, so this happens when you close the game: Prism is closed (force-closed if it doesn't close within a few seconds, unless it's still running another game), the command is added to `instance.cfg` (with a backup), and Prism is opened again. An existing pre-launch command is never replaced.

## Mod problems

Every jar in `mods` is read and hashed, and these are reported:

- **Duplicates:** jars with the same file hash, the same mod id, or the same name. The highest version is kept (for identical copies, the one not named like a copy, such as `mod (1).jar`). Jars that only share a name are flagged to check first, since they have different mod ids.
- **Other loaders:** Forge, NeoForge, Quilt, LiteLoader, Rift and LabyMod jars, which Ornithe never loads.
- **Duplicate Mod Warning,** which isn't needed next to this mod.

Fixing one moves the jars to `removed-mods`, next to `mods`, so they can be put back. In game, a jar the game has open is moved when it closes; restart for the change to apply. In the pre-launch window, problems get ticked rows above the updates (extra copies are ticked, name-only matches and other-loader jars aren't) and are fixed before any update is installed. `--install` also moves extra copies (same hash or mod id) and Duplicate Mod Warning out; the rest is only listed.

## OneClient

Works in Polyfrost's OneClient launcher too. OneClient keeps the mods in its bundles (HUD, QoL, Performance, Utility...) up to date itself, so those are left to it: any jar listed in one of OneClient's bundles for your Minecraft version (by hash, file name or Modrinth project) isn't checked or updated, and the summary says how many were left to OneClient. Every other mod in the cluster, the ones you added yourself, is checked and updated as usual.

OneClient links a cluster's mods to the files in its own store, so updates always replace a jar with a new file rather than writing into the old one.

## Settings

`config/modupdatechecker.json`:

| Setting | Default | |
| --- | --- | --- |
| `checkOnStartup` | `true` | Check when the game starts |
| `showMenuButton` | `true` | Show the Mod Updates button |
| `checkModProblems` | `true` | Look for duplicate mods and mods for other loaders |
| `githubToken` | `""` | Optional. GitHub allows 60 requests an hour without a token; a token with no scopes raises that to 5,000 |
| `githubCacheMinutes` | `360` | How long a GitHub answer is reused (it is still dropped as soon as a newer release is tagged) |
| `ignoredVersions` | `{}` | Mod id → the version you chose to skip |
| `githubOverrides` | `{}` | Mod id → `"owner/repo"`, for mods that don't link their repository |
| `skippedMods` | `[]` | Mod ids never checked |

### For mod authors

If your mod isn't on Modrinth, link its GitHub repository in `fabric.mod.json` (`contact.sources`, `homepage` or `issues`), or set it explicitly:

```json
"custom": { "modupdatechecker": { "github": "owner/repo" } }
```

Attach one jar per release, with the Minecraft version in its name if you publish for several. Tags like `v1.2.0` or `1.2.0+1.8.9` are compared with your mod's version.

## Building

```
./gradlew build
```

The jar is in `build/libs`. `./gradlew runClient -Pmodupdatechecker.devOneConfig=true` starts a dev game with OneConfig.
