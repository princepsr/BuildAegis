# Risk Scoring

This document defines how risk scores, severities and the status chip are computed and displayed in the current application.

## Scope

There are **three** related values in the UI:

1. **Per-dependency risk score** (0-100) and risk level, calculated by the **backend** (`DeterministicRiskCalculator`).
2. **Severity** of each finding (Critical / High / Medium / Low), derived on the **frontend** from the backend risk level.
3. **Overall risk score** (KPI card) and the **status chip**, computed on the **frontend** from the per-dependency scores.

## Per-Dependency Risk Score (Backend)

Calculated deterministically by `DeterministicRiskCalculator` (AI is never used for the score, only for the explanation):

| Factor | Effect on score |
|---|---|
| Base | starts at **20** |
| Known vulnerabilities, weighted by the **real severity** of each | **Critical +55, High +40, Medium +20, Low +8** per vulnerability; total capped at **+75** |
| Vulnerabilities without severity information (when others have it) | count as Medium (**+20** each) |
| No severity information at all | older rule: **+25 per vulnerability**, capped at **+60** |
| **Exploited in the wild** (CISA KEV) | **+25**, and the score is never below **90** |
| High exploit probability (EPSS 50% or more / 10% or more) | +10 / +5 |
| No activity for over 2 years / over 1 year | +15 / +10 |
| Open issues over 200 / over 50 | +10 / +5 |
| GitHub stars under 10 / under 100 | +10 / +5 |

The score is capped at 100 and mapped to a risk level:

| Score | Risk level |
|---|---|
| 70 and above | HIGH |
| 40-69 | MEDIUM |
| 20-39 | LOW |
| below 20 | UNKNOWN |

Examples: one Critical vulnerability scores 75 (HIGH); one High 60; one Medium 40; one Low 28; a Low-severity vulnerability that is exploited in the wild scores 90. Several Low-severity vulnerabilities do not outrank a single Critical one.

## Where Severity and Exploit Data Come From

- **Severity** comes from the OSV advisory of each vulnerability: GitHub's own label (`LOW`, `MODERATE`, `HIGH`, `CRITICAL`) when present, otherwise the CVSS v3.x base score computed from the vector (9.0+ Critical, 7.0-8.9 High, 4.0-6.9 Medium, below 4.0 Low). CVSS v4-only advisories carry no usable score and count as "unknown severity".
- **Exploited in the wild** comes from the CISA Known Exploited Vulnerabilities (KEV) catalog, matched by CVE id (including aliases).
- **EPSS** is the probability (0-100%) that a CVE will be exploited in the next 30 days, from the FIRST EPSS API.
- KEV and EPSS data are cached for 24 hours. They are best-effort: if the services cannot be reached, those factors are simply missing and the analysis continues.

## Severity of a Finding (Frontend)

The severity shown in the table, the severity filter and the Critical/High/Medium/Low tiles is:

1. **CRITICAL** if a vulnerability of the dependency is **exploited in the wild** (CISA KEV).
2. Otherwise the **highest advisory severity** (Critical, High, Medium or Low) when the dependency has vulnerabilities and the severity is known.
3. Without severity data: **CRITICAL** with **10 or more** known vulnerabilities, then the backend risk level (**HIGH**, **MEDIUM** or **LOW**), and as a last resort the vulnerability count (5+ = HIGH, 2+ = MEDIUM, otherwise LOW).

A dependency with no known vulnerabilities is normally LOW.

## Overall Risk Score (KPI Card)

The **Overall risk score** describes the **whole project**, not the current view.

```text
overall = min(100, highestDependencyScore + min(5, vulnerableDependencies - 1))
```

- `highestDependencyScore` is the highest per-dependency risk score (see above) among **all** analyzed dependencies.
- `vulnerableDependencies` is the number of dependencies with at least one known vulnerability. Each one beyond the first adds **1 point**, up to **+5**.
- With no dependencies the score is **0**.

The overall score is therefore never lower than the score of the riskiest dependency, so it always agrees with the per-dependency scores shown in the details dialog.

### Example

A project with 219 dependencies, 9 of them vulnerable, whose riskiest dependency scores 80:
`overall = 80 + min(5, 9 - 1) = 85`.

## Filters Do Not Change the Overall Score

Filtering and searching only change **which findings are listed** and the **count tiles**. They do **not** change the overall risk score or the status chip.

| Element | Follows the filter/search? |
|---|---|
| Findings table and "Showing X of Y findings" | Yes |
| Count tiles: Total dependencies, Vulnerabilities found, Critical, High, Medium, Low | Yes |
| **Overall risk score** | **No** - always the whole project |
| **Status chip** (top right) | **No** - always the whole project |
| **Filtered view** line under the score | Shown only while a filter or search is active |

While a filter or search is active, a line **"Filtered view: N"** appears under the overall score. `N` is calculated with the same formula as the overall score, but only from the visible findings. For example, with the filter set to High, the overall score can stay at 85 while "Filtered view" shows 70.

This is intentional: the overall score answers "how risky is this project?", and the filtered-view line answers "how risky is what I am looking at?". Showing a different number in the overall card when filtering would be misleading.

## Status Chip

The chip always reflects the whole project:

- No dependencies: **No Issues**
- Any Critical finding: **Critical Issues**
- Else any High finding: **High Risk**
- Else any Medium finding: **Medium Risk**
- Else: **Low Risk**

## Vulnerabilities Found Tile

**Vulnerabilities found** counts the **dependencies that have at least one known vulnerability**, not the total number of CVEs and not the total number of dependencies. It follows the filter like the other count tiles.

## Related

- `docs/user-guide/features.md`
- `docs/user-guide/ui-guide.md`
- `docs/developer/frontend/architecture.md`
- `src/main/resources/static/app.js` (`riskScoreFor`, `updateSummary`, `mapProjectResultsToFindings`)
- `src/main/java/com/riskscanner/dependencyriskanalyzer/service/ai/DeterministicRiskCalculator.java`
- `src/main/java/com/riskscanner/dependencyriskanalyzer/service/vulnerability/` (`OsvAdvisoryParser`, `Cvss3`, `ExploitDataService`)
