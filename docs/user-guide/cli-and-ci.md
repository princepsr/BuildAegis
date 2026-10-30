# Command Line and CI

BuildAegis can scan a project without the web UI and **fail the build** when serious findings exist. This is meant for CI pipelines (GitHub Actions, GitLab CI, Jenkins, ...).

## Quick start

Build the application once (`mvn package`, or use a released jar), then run:

```bash
java -jar buildaegis.jar --buildaegis.cli.project=/path/to/project --buildaegis.cli.fail-on=high
```

In this mode the application starts **without a web server**, scans the project, prints a report and exits. AI is never used in command-line mode.

## Options

| Option | Default | Meaning |
|---|---|---|
| `--buildaegis.cli.project` | (required) | Project folder or build file (`pom.xml`, `build.gradle`, `build.gradle.kts`). Setting it enables command-line mode |
| `--buildaegis.cli.fail-on` | `high` | Lowest severity that fails the scan: `none`, `low`, `medium`, `high` or `critical` |
| `--buildaegis.cli.format` | `text` | `text` (console report) or `json` (full results and summary) |
| `--buildaegis.cli.output` | (console) | Write the report to this file instead of the console |
| `--buildaegis.cli.allow-unchecked` | `false` | Pass even if some dependencies could not be checked for vulnerabilities (see below) |

Any normal Spring option also works, for example `--spring.datasource.url=jdbc:h2:mem:ci` to use an in-memory database (recommended in CI so nothing is left on the runner).

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Passed: no finding at or above the threshold (or `fail-on=none`) |
| `1` | Failed: at least one dependency with known vulnerabilities is at or above the threshold |
| `2` | The scan could not run (bad path, invalid option, resolution failure), **or it was incomplete**: some dependencies could not be checked for vulnerabilities. Never treated as a pass |

### Incomplete scans

A security gate must not pass on data it could not get. If the vulnerability source (OSV) is unreachable, or the build tool is not supported by the vulnerability lookup, those dependencies are counted as **unchecked**. The report then says `WARNING: N of M dependencies could not be checked ...` and ends with `Result: INCOMPLETE`, and the exit code is `2` (unless findings already fail the scan with `1`).

Fix the cause (network access, supported build tool), or pass `--buildaegis.cli.allow-unchecked=true` if you knowingly accept it. With `--buildaegis.cli.fail-on=none` no gate is applied, so unchecked dependencies only produce the warning.

## What counts as a finding

The same rules as the UI (see [Risk Scoring](../architecture/risk-scoring.md)):

- A dependency's severity is **Critical** if a vulnerability is exploited in the wild (CISA KEV), otherwise the highest advisory severity (CVSS or GitHub label); without severity data, 10+ vulnerabilities is Critical, then the backend risk level.
- Only dependencies **with known vulnerabilities** can fail the scan. Clean dependencies never do, even with `--fail-on=low`.
- The report also shows counts per severity and the overall risk score.

## Example output

```text
BuildAegis scan: /work/my-app
Dependencies: 219, with known vulnerabilities: 9
Critical: 1  High: 1  Medium: 7  Low: 210  Overall risk score: 85
Findings at or above HIGH: 2
  CRITICAL g:tomcat-embed-core:10.1.34  vulnerabilities=11  risk=80  fix=10.1.36
  HIGH     g:logback-core:1.5.16  vulnerabilities=2  risk=70
Result: FAILED
```

When some dependencies could not be checked:

```text
WARNING: 94 of 94 dependencies could not be checked for vulnerabilities (vulnerability source unreachable, or build tool not supported).
Findings at or above HIGH: 0
Result: INCOMPLETE (94 dependencies could not be checked)
```

## GitHub Actions example

```yaml
name: dependency-scan
on: [pull_request]
jobs:
  scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - name: Download BuildAegis
        run: curl -L -o buildaegis.jar "$BUILDAEGIS_JAR_URL"   # or build it from source
      - name: Scan
        run: >
          java -jar buildaegis.jar
          --buildaegis.cli.project=.
          --buildaegis.cli.fail-on=high
          --buildaegis.cli.format=json
          --buildaegis.cli.output=buildaegis-report.json
          --spring.datasource.url=jdbc:h2:mem:ci
      - name: Upload report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: buildaegis-report
          path: buildaegis-report.json
```

## JSON report

With `--buildaegis.cli.format=json` the report contains `result` (project path, totals, counts per severity, overall risk score, the threshold, the failing findings and a `failed` flag) and `dependencies` (the full per-dependency results, same shape as `/api/project/analyze`).

## Notes

- Scanning needs network access to the vulnerability sources (OSV, CISA KEV, EPSS, Maven Central, GitHub for repository statistics). If a source is unreachable, its data is missing from the report and the scan continues.
- Gradle projects use the bundled Gradle wrapper support; Gradle results are best-effort (see [Features](features.md)). The vulnerability lookup currently covers Maven coordinates, so a Gradle project is reported as unchecked and needs `--buildaegis.cli.allow-unchecked=true` until that is extended.
