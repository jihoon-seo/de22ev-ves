# Built-in engine-sound packs

Each `packs/<slot>/` folder is one built-in sound (`pack.txt` + 22050 Hz mono loops). The build copies them
into `vesd.jar`; the app offers them by slot name (`vex4` is the default).

The sounds are by 히릿 (engine physics simulator), processed by `ves.resynth`, and used with their permission
in this mod only. They are **not** covered by the MIT licence: reusing them anywhere else needs 히릿's
permission. See [THIRD_PARTY.md](../THIRD_PARTY.md).

Only processed packs live here (no original recordings, models, tracks or segment files). They are written by
`python -I tools/import_packs.py <private VES/packs folder>`, which checks the source and puts the notice on
line 1 of each `pack.txt`.
