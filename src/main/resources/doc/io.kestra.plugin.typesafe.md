# How to use the TypeSafe plugin

Evaluate content with TypeSafe AI's System One models from Kestra flows.

## Authentication

The `apiKey` property is optional. When configured, the plugin sends it as a Bearer token in the `Authorization` header. When omitted or left blank, the header is not sent.

This allows the plugin to work with compatible endpoints that do not require authentication, including local TypeSafe-compatible servers such as Laya. Whether authentication can be omitted depends on the server configuration.

For authenticated endpoints, store your API key as a [Kestra secret](https://kestra.io/docs/concepts/secrets) rather than hardcoding it in a flow. You can configure connection properties globally using plugin defaults:

```yaml
pluginDefaults:
  - forced: false
    type: io.kestra.plugin.typesafe.Evaluate
    values:
      apiKey: "{{ secret('TYPESAFE_API_KEY') }}"
      model: jev-latest
```

For a local, unauthenticated endpoint, configure `baseUrl` and omit `apiKey`:

```yaml
id: evaluate
namespace: company.team

tasks:
  - id: evaluate
    type: io.kestra.plugin.typesafe.Evaluate
    baseUrl: http://localhost:8000
    model: jev-latest
    state: "A customer reports a failed payment."
    questions:
      is_urgent:
        type: NOUL
        instructions: "Does this require urgent attention?"
```

The `baseUrl` must be reachable from the Kestra execution environment. For example, `localhost` refers to the Kestra container itself when Kestra runs inside Docker, not necessarily the host machine.

## Model pinning

`model` accepts an alias such as `jev-latest`, which moves when TypeSafe ships a new release. Every response echoes the versioned model id that actually answered (for example `jev-1.13.0`), also exposed as the task `model` output. If you tune thresholds against a specific version, pin that versioned id in `model` instead of the alias.

## Confidence-gated routing

`CHOICE` and `SCORE` answers carry a `confidence` value between 0 and 1 derived from the answer's probability distribution. Use it as a second axis in downstream tasks: act directly above your threshold and route uncertain answers to review, for example with an `If` task on `{{ outputs.evaluate.answers.department.confidence }}`.

## Tasks

`Evaluate` evaluates one `state` (a string, object or array) against a map of typed `questions` and returns one structured `answer` per question, keyed by the same ids. Each question has a `type` (`NOUL`, `CHOICE` or `SCORE`) and `instructions`; `CHOICE` questions take `options` (at most 255), `SCORE` questions take `levels` (2 to 10), and `NOUL` questions optionally take `whenTrue`/`whenFalse` criteria. The output contains `answers` and the versioned `model` id; token usage is reported as `input.tokens` and `output.tokens` metrics.

`EvaluateBatch` evaluates every record of a dataset with the same `questions`. Records come from `from` (an inline list, a `kestra://` URI, or a JSON string), are evaluated with bounded `concurrency` (default 5, at most 20), and results are written in input order to an ION file with one `{"state", "answers"}` line per record. The output contains the file `uri`, the `count` of records, and the `model`. If any record fails, the whole task fails with the record index in the error message.

Transient `429` and `529` responses are retried with backoff honoring the `Retry-After` response header; `401` and `422` responses fail fast with an actionable message.
