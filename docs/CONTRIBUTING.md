# Contributing to BuildAegis

Thanks for taking the time to contribute.

## Development setup

- Java: 21
- Build: Maven Wrapper

### Build

```powershell
.\mvnw clean compile
```

### Run

```powershell
.\mvnw spring-boot:run
```

Open:
- `http://localhost:8080/buildaegis`

### Test

```powershell
.\mvnw clean test
```

### Continuous integration

After every push to `main`, GitHub Actions (`.github/workflows/ci.yml`) runs `mvn verify` (all tests, including the Spring context test); it does not run on pull requests, so run `mvn verify` locally before opening one. The context test uses an in-memory database, so running the tests never touches your local `./data` database. A second, informational job compiles the desktop wrapper with `-Pdesktop`.

## What to contribute

- Bug fixes (dependency resolution, vulnerability enrichment, UI)
- New vulnerability providers (best-effort enrichers)
- Documentation fixes/updates

## Pull request guidelines

- Keep changes focused and small.
- Include a clear description of the problem and the solution.
- Add/update tests when reasonable.
- Avoid renaming packages / identifiers unless the change requires it.

## Reporting issues

Please include:
- OS
- Java version
- Build tool used (Maven/Gradle)
- Steps to reproduce
- Relevant logs (redact secrets)
