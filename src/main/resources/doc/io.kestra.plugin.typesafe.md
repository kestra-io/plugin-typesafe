# How to use the TypeSafe plugin

Evaluate content with TypeSafe AI's System One models from Kestra flows.

## Authentication

Set `apiKey` (required) to your TypeSafe API key. It is sent as an `Authorization: Bearer` header. Store secrets in [secrets](https://kestra.io/docs/concepts/secrets) and apply connection properties globally with plugin defaults:

```yaml
pluginDefaults:
  - forced: false
    type: io.kestra.plugin.typesafe.Evaluate
    values:
      apiKey: "{{ secret('TYPESAFE_API_KEY') }}"
      model: jev-latest
```

## Model pinning

`model` accepts an alias such as `jev-latest`, which moves when TypeSafe ships a new release. Every response echoes the versioned model id that actually answered (for example `jev-1.13.0`), also exposed as the task `model` output. If you tune thresholds against a specific version, pin that versioned id in `model` instead of the alias.

## Confidence-gated routing

`CHOICE` and `SCORE` answers carry a `confidence` value between 0 and 1 derived from the answer's probability distribution. Use it as a second axis in downstream tasks: act directly above your threshold and route uncertain answers to review, for example with an `If` task on `{{ outputs.evaluate.answers.department.confidence }}`.

## Tasks

`Evaluate` evaluates one `state` (a string, object or array) against a map of typed `questions` and returns one structured `answer` per question, keyed by the same ids. Each question has a `type` (`NOUL`, `CHOICE` or `SCORE`) and `instructions`; `CHOICE` questions take `options` (at most 255), `SCORE` questions take `levels` (2 to 10), and `NOUL` questions optionally take `whenTrue`/`whenFalse` criteria. The output contains `answers` and the versioned `model` id; token usage is reported as `input.tokens` and `output.tokens` metrics.

`EvaluateBatch` evaluates every record of a dataset with the same `questions`. Records come from `from` (an inline list, a `kestra://` URI, or a JSON string), are evaluated with bounded `concurrency` (default 5, at most 20), and results are written in input order to an ION file with one `{"state", "answers"}` line per record. The output contains the file `uri`, the `count` of records, and the `model`. If any record fails, the whole task fails with the record index in the error message.

Transient `429` and `529` responses are retried with backoff honoring the `Retry-After` response header; `401` and `422` responses fail fast with an actionable message.
