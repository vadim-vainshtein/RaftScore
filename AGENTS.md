# RaftScore Development Workflow

For each new implementation task, create a separate worktree and branch before making changes
and implement the task in that branch. Keep changes scoped to the task and
preserve unrelated local work.

At intake, read the actual target branch instructions separately from the current worktree instructions using `git show <target-branch>:AGENTS.md` before planning or editing. Plan the entire sequence through review, PR, merge, and runtime verification before work begins.

Under the standing user authorization in the global `AGENTS.md`, the coordinator completes an implementation task only after the PR is reported `MERGED` and `scripts/Complete-RaftScorePostMerge.ps1 -MergedCommit <SHA>` confirms that the running JAR comes from the latest fetched `origin/master` containing that merge and passes HTTP verification. Report the PR link, verified running SHA and URL. On a real blocker, report the exact failed stage and reason and the last verified runtime status.

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
  to `master`, waits for required checks and branch protection, and merges it when
  they allow, under the global standing user authorization.
- After merging, run the post-merge script described above before the final response. The script can be rerun after failure; preserve the existing database.
- Retain explicit authorization gates for deployment and other unrelated gated actions.

## Browser Visual Annotations

When reviewing visual annotations from the browser, assess the task and give the
session a meaningful name based on the requested change or affected scope when
identifiable, such as `Rework competition editing`. Avoid generic names such as
`Review comments 1 and 2`.

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
