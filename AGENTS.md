# RaftScore Issue Workflow

Use this workflow for work that originates from a repository issue:

1. Take the issue and inspect its labels before planning or editing. Continue only
   when it is labeled `task`; do not implement issues labeled `epic` or `feature`.
   If its classification is missing or conflicting, ask the user to clarify.
2. Create a new issue-linked branch before making code changes, then perform all
   implementation work in that branch. Use the repository host's issue-development
   workflow when available so the branch is associated with the issue.
3. Keep the work scoped to that issue and preserve unrelated local changes.
4. Create a pull request only when the user explicitly asks. Creating a remote
   branch or pull request is an external action and requires the user's explicit
   authorization; do not push, open, merge, or modify a PR otherwise.

## Testing and Java Boilerplate

- Prefer behavior-focused tests over declaration-only tests when verifying
  behavior or contracts.
- When database-backed behavior is relevant, use the shared Testcontainers
  integration-test support to validate it against PostgreSQL.
- Remove redundant legacy tests when stronger behavior coverage replaces them.
- Use Lombok for routine entity getters, setters, and constructors; keep JPA
  no-argument constructors `protected`.
- Use `var` for local variables when the inferred type is clear from the
  initializer; retain explicit types when they improve readability.
- On JDK 26, configure Lombok annotation processing explicitly. After changing
  that configuration, run `mvn clean test`.
