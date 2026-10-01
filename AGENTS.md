# Kestra Typesafe Plugin

## What

- Provides plugin components under `io.kestra.plugin.typesafe`.
- Includes classes such as `Evaluate`, `EvaluateBatch`, `Question`, `Answer`.

## Why

- What user problem does this solve? Teams need to evaluate content against calibrated, typed TypeSafe questions from orchestrated workflows instead of one-off scripts or manual review.
- Why would a team adopt this plugin in a workflow? It keeps TypeSafe evaluation steps in the same Kestra flow as data preparation, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual triage and fragmented tooling while improving reliability and traceability for processes that depend on consistent automated judgment.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `typesafe`

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.typesafe.Evaluate`
- `io.kestra.plugin.typesafe.EvaluateBatch`

### Project Structure

```
plugin-typesafe/
├── src/main/java/io/kestra/plugin/typesafe/
├── src/test/java/io/kestra/plugin/typesafe/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
