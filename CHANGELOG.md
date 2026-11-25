# Changelog

## 1.1.0

### Added
- NuclearCraft multiblocks: molten salt reactor, fission reactor, heat exchanger and turbine, with movable ports, glass and casing choices.
- Mirror a placement from the placement card.
- Remembered picks: the editor reopens with the variants you last chose for each structure.
- Favourites in the browser: star a structure and it sorts to the top the next time you open it.
- Build animations: placing sweeps up layer by layer, dismantling sweeps down. Blocks land together at the end of the sweep, so a multiblock forms once, finished.
- Craft-all progress when a structure's missing blocks are crafted through AE2.
- JEI: a card-style structure page that isolates the material you hover.
- Editor: "Reset to original" in the Variants tab, and the checks that stop a structure from forming are listed there in red.
- Settings screen (Mods > Formwork > Config): animations on/off, animation speed and dismantle speed.
- Corner brackets on a placed multiblock float just outside it and pulse gently.

### Changed
- NuclearCraft fission reactors default to passive water heat sinks; active heat sinks need coolant.
- The status box in the editor view only appears when the structure will not form.
- Animated blocks are drawn from cached model quads, so large structures animate without stalling.

### Fixed
- Animated blocks waiting to land were drawn full-bright at night.
- A red highlight or ghost could flash over a structure while it was being dismantled.
- Starring a favourite no longer reshuffles the grid under the cursor.
- Animation speed had no effect on structures with few layers.
