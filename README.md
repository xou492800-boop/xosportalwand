# PortalWand

Paper 1.21.4+ plugin. Right click = blue portal, left click = dark red portal.
Only DSBV and xo1u (checked by UUID) can use the Portal Wand. Give it with `/portalwand`.

## Build on GitHub
1. Push this folder to a GitHub repo.
2. Open the **Actions** tab, select **Build PortalWand** (it also runs on every push).
3. When the run finishes, download **PortalWand-jar** from the Artifacts section.
4. Unzip it and put the jar in your server's `plugins` folder.

## Build locally
`mvn package` (needs Java 21 and Maven). The jar is created in `target/`.
