# Development tool decisions

Reviewed on November 15, 2026. These development tools do not change the audio workflow, transcription providers, application styling or beta acceptance criteria.

## Assertrust

[The workflow](../../.github/workflows/assertrust.yml) reviews pull requests and pushes to `main` for weakened tests and CI commands. Checkout and the action are pinned to reviewed commits; the Linux x86-64 executable is pinned to release 0.1.0 and a SHA-256 checked before extraction. Dependabot already monitors GitHub Actions in this repository. When updating this tool, review the wrapper and release checksum together.

Findings are observational (`fail-on: never`). Execution or download errors still fail the job. Existing quality checks and release gates retain their own failure behavior. Same-repository pull requests can receive the action's single summary comment; forks use annotations and the job summary. The checkout does not persist credentials. No additional secret, paid service or provider key is configured.

Version 0.1.0 understands supported JavaScript/TypeScript `expect` assertions and selected CI/package-script patterns. EchoScript's Kotlin assertions and current Node `assert` test bodies are outside that coverage. Its clean report does not establish application correctness or complete test review. Continue running the actual test suites and reviewing their assertions.

Source: [pinned action and wrapper](https://github.com/BeyondFolder/assertrust/tree/fc6ac2b77bb5694501b815bab9e03d43ce887ced), [release 0.1.0](https://github.com/BeyondFolder/assertrust/releases/tag/v0.1.0).

## Pollen

[Pollen](https://github.com/groupbees/pollen) manages coding-agent skills. It is development tooling, separate from EchoScript's speech enhancement and application runtime. Release 0.7.0 was installed in the development workspace's user-local binary directory and its version/help commands passed. That installation does not install anything on the owner's Android device or make Pollen a project runtime dependency.

No skill sources, project/global configuration or skill directories were deployed. Choose and review individual sources before creating a `pollen.yaml`; pin git sources to reviewed commits. `pollen validate` checks a selected configuration, while `pollen update` can install, refresh and prune managed skill directories. Review its planned scope before using it. Do not put evidence, credentials or private case material into skill sources.

The Linux installer and Windows PowerShell installer were read before execution was considered. Installation used the verified Linux release archive directly, without running a mutable remote shell script. The Windows installer was not executed; it also changes the user's PATH. The supplied `v0.4.0 /opt/bin` command was treated as an example of version/destination selection; the current 0.7.0 release was installed without system-wide writes.

Sources: [reviewed Linux installer](https://github.com/groupbees/pollen/blob/cdc539d85b9532e483f415a28c940dfe7182619a/skills/pollen/scripts/install.sh), [release 0.7.0](https://github.com/groupbees/pollen/releases/tag/v0.7.0).

## Symfony AssetMapper

The supplied Composer command and importmap-update workflow require a Symfony/PHP application with Composer dependencies and an `importmap.php`. EchoScript currently has Kotlin/Compose Android code, a Node/TypeScript API and browser companion, and a separate Next.js/Fumadocs app. There is no Symfony application or importmap to update.

Composer dependencies and an active importmap workflow are therefore deferred until a Symfony component is explicitly selected. Adding them now would create an unrelated PHP dependency tree and a scheduled job with no applicable application. The existing npm and Dependabot updates remain the relevant dependency paths. Vue/TypeScript/Tailwind design changes and codebase refactoring still require the owner's requested design approval.

Sources: [Symfony AssetMapper setup](https://symfony.com/doc/current/frontend/asset_mapper.html), [Symfony updater prerequisites](https://github.com/chr-hertel/symfony-asset-mapper-update).

## Verification record

The five reviewed text files were Pollen's Linux installer, PowerShell installer and skill description, and Assertrust's action definition and shell wrapper. Static instruction scanning reported zero active findings after one exact false-positive suppression: the wrapper's literal notice about a fork's read-only token contains no credential value or transfer. The suppression applies only to that reviewed diagnostic, not to a rule or directory globally. This review did not audit the complete Rust codebases or certify the binaries.

| Artifact                                  | SHA-256                                                            |
| ----------------------------------------- | ------------------------------------------------------------------ |
| Pollen 0.7.0 Linux x86-64 musl archive    | `546a05d6a92b26f600ce204dc98b42171c4bb477d7ef89d2dec346d9cd6d2866` |
| Assertrust 0.1.0 Linux x86-64 GNU archive | `172d5afdcb02c8c8b9f91093271cad453a0ffb6efbd7397517361db08ab9d127` |

Local execution confirmed both CLI versions. A temporary synthetic git fixture removed one `expect` assertion: Assertrust reported the removed assertion at high confidence, exited 0 with `--fail-on never`, and exited 1 with `--fail-on high`. The fixture was outside the application repository. This verifies that particular detector and exit policy, not Kotlin/Node assertion coverage or any application release gate.

The exact workflow download, checksum and extraction commands also passed locally and produced `assertrust 0.1.0`. Actionlint 1.7.12 accepted the workflow; touched canonical files passed Prettier and whitespace checks. Documentation synchronization and its stale-content check passed for 25 canonical pages. Hosted workflow execution and the full documentation build are reported separately in the associated pull request.
