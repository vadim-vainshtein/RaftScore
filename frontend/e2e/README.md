# Browser tests

Use JDK 25 or newer and run `mvn -DskipTests package` at the repository root. Maven installs frontend dependencies and builds the frontend as part of packaging. Then, with Docker and Chrome installed, run `npm run test:e2e` in `frontend`. Additional Playwright arguments can follow `--`.

The runner starts a disposable PostgreSQL container on a Docker-assigned loopback port and the built application on a free loopback port. It passes the database and server settings directly to its application process and clears inherited Spring configuration environment variables. It removes its own container and anonymous volume and stops its own application process. It does not use the development database. Ensure the Java executable on PATH uses the same supported JDK.

Cleanup on SIGINT and SIGTERM is best effort. If the entire process tree is forcibly terminated, remove the printed disposable container ID and its volume with `docker rm -fv <printed-id>`.

The read-only mobile scenario seeds its own competition and user through authenticated API calls, then signs in through the Russian UI at 360px. It can run independently with `npm run test:e2e -- read-only-mobile.spec.ts`; the default command runs it alongside the full-access scenario.
