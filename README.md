# jenkins-plugin-surface

Deterministic extractor of the Pipeline-facing surface of a Jenkins plugin archive: steps, global variables,
symbols, parameters (with `model_required` kept apart from `usage_hint`), documentation and links. It works
from bytes only (a plugin `.hpi`, supplied dependency archives and a pinned Jenkins core jar) and never loads
a class.

## Usage

```sh
./gradlew installDist
build/install/jenkins-plugin-surface/bin/jenkins-plugin-surface extract \
  --hpi plugin.hpi --core jenkins-core.jar --out out/ [--dep other.hpi]... [--no-help-text] [--allow-unknown]
```

Output: `out/<plugin-id>-<version>.json`, schema `schema/jenkins-plugin-surface-v1.schema.json`. Keys and arrays
are sorted (parameters keep model order), there are no timestamps and no absolute paths.

| Exit code | Meaning |
| --- | --- |
| 0 | success |
| 1 | census gate failed: an extension ended in no class (without `--allow-unknown`) or the extension index and the bytecode scan disagree; output is still written |
| 2 | usage error |
| 3 | input error (missing, unreadable or malformed archive) |
| 4 | internal error (including an unwritable output directory) |

## How it classifies

Every extension is enumerated twice per jar (index files and an ASM annotation scan) and must end in a record:
`step`, `step-wrapped-describable`, `wrap-wrapped-describable`, `nested-only-symbol`, `global-variable`,
`declarative-element`, `not-pipeline-facing` or `unknown` (with a reason). A positive match comes from a supertype
name; a negative claim needs a fully resolved hierarchy, otherwise the record is `unknown` (`unresolvable-type`).
Facts the bytecode interpreter cannot establish become `gaps` and lower the record's `confidence`.

## Corpus check and regeneration

`./gradlew build corpusTest` downloads the archives pinned (by SHA-256) in `src/test/resources/corpus-pins.tsv`,
extracts each twice, asserts byte-identical output, zero unknown records and golden facts, and writes the surfaces
to `build/corpus-out`. The same command regenerates the sample outputs; CI uploads them as the `corpus-surfaces`
artifact. Archives are never committed.

## Documentation text and licences

Help text belongs to each plugin under its own licence. Output contains summaries and (unless `--no-help-text`)
bodies; `--no-help-text` still keeps the first-sentence summary. Do not redistribute generated output for plugins
whose licence does not allow it. Link URLs are derived from templates and are not checked for liveness.

## Licence

Apache-2.0 (see `LICENSE`); the owner may change it.
