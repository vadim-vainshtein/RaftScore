# RaftScore Development Workflow

For each new implementation task, create a separate worktree and branch before making changes
and implement the task in that branch. Keep changes scoped to the task and
preserve unrelated local work.

At task intake, read the target branch instructions as well as the current worktree instructions. When the target differs, read them directly with `git show <target-branch>:AGENTS.md` before planning or editing.

## Development Review

These requirements apply to the general development pipeline for this repository.

- Delegate implementation and validation to `task_developer` using `gpt-6.1-terra`.
- After each developer pass, start a fresh, independent, read-only
  `task_reviewer` using `gpt-6.1-terra` and `fork_turns="none"`.
- Give the reviewer the original user task description verbatim as its sole task
  context. The reviewer must find the branch diff from the repository; provide
  only neutral target access metadata if necessary. Do not provide implementation
  session history or summaries, rationale, findings from previous passes, or
  validation reports.
- Return actionable findings to the developer automatically, then revalidate and
  review again with a new independent reviewer until no actionable findings remain.
  Ask the user only when a necessary product or scope decision, blocker, or
  independently gated operation requires their input.
- After validation succeeds and the independent reviewer approves, the coordinator
  commits only reviewed task changes, pushes the task branch to GitHub, opens a PR
  to the repository's default branch, and merges it once required checks and branch
  protection allow. This workflow authorizes these actions for project tasks.
- After merging, run `scripts/Complete-RaftScorePostMerge.ps1 -MergedCommit <merge-commit>` before the final response. It verifies the running JAR and HTTP endpoints on port 8081; report its verified commit and URL. The script can be rerun after failure. If blocked, report the exact failed stage and reason, and preserve the existing database.
- Retain explicit authorization gates for deployment and other unrelated gated actions.

## Code Readability

- Break nontrivial methods into short, well-named blocks or helpers so the
  high-level flow is easy to follow. Use judgment rather than a fixed line count.

## Testing and Java Boilerplate

- Prefer behavior-focused tests over declaration-only tests when verifying
  behavior or contracts.
- When database-backed behavior is relevant, use the shared Testcontainers
  integration-test support to validate it against PostgreSQL.
- Remove redundant legacy tests when stronger behavior coverage replaces them.
- Use Lombok for routine entity getters, setters, and constructors; keep JPA
  no-argument constructors `protected`.
- Use Lombok `@RequiredArgsConstructor` for trivial constructors that only assign
  required arguments to fields.
- For entities with several caller-supplied fields, prefer Lombok `@Builder`
  on a dedicated constructor.
- Do not use `@SuperBuilder` solely because an entity extends a JPA persistence
  superclass. Use it only when callers must intentionally construct inherited
  domain state; do not expose persistence-managed IDs or optimistic-lock
  versions through builders.
- Use `var` for local variables when the inferred type is clear from the
  initializer; retain explicit types when they improve readability.
- On JDK 26, configure Lombok annotation processing explicitly. After changing
  that configuration, run `mvn clean test`.
