# Claude Code

The instructions for this repository are in `AGENTS.md`, shared by every agent tool and model. They are imported here
so they load at the start of each session:

@AGENTS.md

Claude Code specifics:

- The tool's default commit and PR attribution lines (`Co-Authored-By`, "Generated with ...") are **not** used in this
  project (rule 4 in `AGENTS.md`). Leave them out even when a tool reminder suggests them.
- Put nothing project-specific in this file. A rule that should hold for every agent goes into `AGENTS.md`; a
  convention goes into `docs/`.
