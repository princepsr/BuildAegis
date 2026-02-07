# Troubleshooting Guide

## Common Issues and Solutions

### Analysis Issues

#### "No dependencies detected"
**Symptoms**: Analysis completes but shows 0 dependencies

**Possible Causes**:
1. Project path is incorrect
2. No build file (`pom.xml` or `build.gradle`) in directory
3. Build file is empty or malformed
4. For Gradle: Project uses non-standard structure

**Solutions**:
```bash
# Verify project path exists
ls -la /path/to/project  # macOS/Linux
dir C:\path\to\project    # Windows

# Check for build files
ls /path/to/project/pom.xml
ls /path/to/project/build.gradle
ls /path/to/project/build.gradle.kts
```

**Debugging Steps**:
1. Open browser console (F12) and check Network tab
2. Look at `/api/project/scan` response
3. Check backend logs:
   ```bash
   ./mvnw spring-boot:run | grep -i "scan\|gradle\|maven"
   ```

#### "Analysis failed" with no details
**Symptoms**: Error banner appears but message is unclear

**Solutions**:
1. Check browser console for JavaScript errors
2. Verify backend is running: `curl http://localhost:8080/buildaegis/api/health`
3. Check backend logs in `logs/application.log`
4. Try with a simpler project to isolate the issue

#### Gradle projects show fewer dependencies than expected
**Symptoms**: Gradle analysis shows incomplete dependency list

**Explanation**: This is expected behavior. Gradle analysis is "best-effort" due to:
- Gradle's dynamic dependency resolution
- Build script execution requirements
- Plugin configurations that can't be statically analyzed

**Solutions**:
- Accept that Gradle confidence is MEDIUM, not HIGH
- Manually review findings for false negatives
- Consider using Maven for critical security audits
- Run `./gradlew dependencies` separately and compare

#### Multi-project Gradle analysis is very slow
**Symptoms**: Analysis of large Gradle multi-project builds takes 5+ minutes

**Explanation**: Large multi-project builds with many subprojects can be slow due to:
- Each subproject requiring separate Gradle execution
- Node.js projects downloading Node.js and npm packages
- Test projects running test frameworks

**Solutions**:
1. **Use subproject analysis**: Point to specific subproject instead of root
2. **Enable fast mode**: Start with `-Dgradle.skip.subprojects=true`
3. **Check what's being analyzed**: Look for "Skipping X project" logs
4. **Analyze relevant subprojects only**: Focus on Java projects, not Node.js/tests

**Example Fast Mode**:
```bash
# Skip all subproject analysis (root only)
./mvnw spring-boot:run -Dgradle.skip.subprojects=true

# Then analyze specific subproject in UI
# Use path: C:\Users\PrinceSingh\Sciforma\IntelliJ\sciforma-webapp
```

#### Gradle subproject not found
**Symptoms**: "Failed to analyze subproject" or "No dependencies found"

**Possible Causes**:
1. Subproject path is incorrect
2. Subproject is being automatically skipped (Node.js, test, docs)
3. Parent project doesn't have settings.gradle

**Solutions**:
1. **Verify subproject exists**:
   ```bash
   ls -la /path/to/project/subproject-name
   ```
2. **Check if it's being skipped**: Look for logs like "Skipping X project"
3. **Use root analysis**: Point to parent directory instead
4. **Check settings.gradle**: Ensure subproject is properly included

**Common Skipped Projects**:
- `apps/react-*` (Node.js projects)
- `docs/*` (documentation)
- `tests/*` (test projects)
- `build-tools` (build utilities)

### AI Issues

#### "AI settings are not configured"
**Symptoms**: Analysis completes but no AI explanations

**Solutions**:
1. Check AI Configuration section is expanded
2. Verify "Enable AI-assisted analysis" is checked
3. Ensure provider, model, and API key are set
4. Click "Analyze Dependencies" again (not "Load Cached")

#### "Test connection failed"
**Symptoms**: AI connection test shows error

**Solutions**:
| Provider | Common Issues |
|----------|--------------|
| **OpenAI** | Verify API key format (starts with `sk-`). Check key has credits. |
| **Claude** | Ensure API key is from Anthropic Console, not AWS Bedrock. |
| **Gemini** | Use Google AI Studio API key, not GCP service account. |
| **Ollama** | Verify Ollama is running locally: `ollama list`. |
| **Azure** | Check deployment name matches exactly (case-sensitive). |

**Debugging**:
```bash
# Test OpenAI directly
curl https://api.openai.com/v1/models \
  -H "Authorization: Bearer YOUR_API_KEY"
```

#### AI analysis is slow
**Symptoms**: AI analysis takes 30+ seconds per dependency

**Expected Behavior**:
- 1-5 seconds: Fast (cached or simple query)
- 5-15 seconds: Normal (AI API call)
- 15-30 seconds: Slow (complex dependency or rate limits)
- 30+ seconds: Check for issues

**Solutions**:
1. Use faster model (gpt-4o-mini instead of gpt-4o)
2. Check your API rate limits
3. Enable caching to avoid re-analyzing same dependencies
4. Consider using Ollama for offline processing

### Filter Issues

#### Filters not working
**Symptoms**: Changing filters doesn't update table

**Solutions**:
1. Check browser console for JavaScript errors
2. Verify `app.js` is loaded (check Network tab)
3. Hard refresh (Ctrl+F5 or Cmd+Shift+R)
4. Check for empty search box issues (clear and re-type)

#### "No findings match filters"
**Symptoms**: All filters result in empty table

**Possible Causes**:
1. Filter combination is too restrictive
2. No findings in that severity/confidence category
3. Search term doesn't match any dependency names

**Solutions**:
1. Reset filters: Select "All" in dropdowns, clear search
2. Check findings count shows data exists
3. Try broader search terms (e.g., "spring" instead of "spring-core")

### Cache Issues

#### "Load Cached" returns old data
**Symptoms**: Cached results don't reflect recent changes

**Explanation**:
- Cache is keyed by: `groupId + artifactId + version + AI provider + model`
- Changing any of these creates a new cache entry
- Cache entries expire based on TTL (default: 1 hour)

**Solutions**:
1. Click "Analyze Dependencies" instead of "Load Cached"
2. Clear cache via database:
   ```sql
   -- In H2 console (http://localhost:8080/buildaegis/h2-console)
   DELETE FROM dependency_risk_cache;
   ```
3. Or delete database files and restart:
   ```bash
   rm ./data/buildaegis.mv.db
   ./mvnw spring-boot:run
   ```

#### Cache not working (always re-analyzes)
**Symptoms**: Same dependency re-analyzed every time

**Possible Causes**:
1. `forceRefresh=true` is being sent
2. Cache entry expired
3. Different AI provider/model used
4. Version numbers differ slightly

**Solutions**:
1. Verify using same project path
2. Check AI settings match exactly
3. Check cache in database:
   ```sql
   SELECT group_id, artifact_id, version, provider, model, created_at 
   FROM dependency_risk_cache 
   ORDER BY created_at DESC;
   ```

### Export Issues

#### "Export CSV" doesn't download file
**Symptoms**: Click export but no file downloaded

**Solutions**:
1. Check browser console for JavaScript errors
2. Disable pop-up blockers
3. Try different browser
4. Check if findings table has data
5. Look for file in Downloads folder (may not show prompt)

#### CSV has incorrect data
**Symptoms**: Export shows wrong severity or missing columns

**Solutions**:
1. Verify UI shows correct data before export
2. Check if filters are applied (export reflects current view)
3. Clear cache and re-analyze
4. Report specific data issues with example

### UI Issues

#### Page doesn't load
**Symptoms**: Blank page or 404 error

**Solutions**:
1. Verify backend is running:
   ```bash
   curl http://localhost:8080/buildaegis
   ```
2. Check static files exist:
   ```bash
   ls src/main/resources/static/index.html
   ```
3. Try accessing directly:
   - `http://localhost:8080/buildaegis/index.html`
   - `http://localhost:8080/buildaegis/app.js`
   - `http://localhost:8080/buildaegis/styles.css`
4. Check for port conflicts (try different port)

#### Modal won't open
**Symptoms**: Clicking "View" does nothing

**Solutions**:
1. Check browser console for errors
2. Verify `app.js` loaded correctly (cache-bust with `?v=2`)
3. Check if modal HTML exists in page source
4. Look for JavaScript errors in console

#### Risk score seems wrong
**Symptoms**: Score doesn't match expectations

**Understanding the Algorithm**:
- **Current view only**: Score reflects filtered results, not total project
- **Critical dominance**: 2 critical vulnerabilities minimum score of 60
- **Weighted formula**: Critical(40) + High(30) + Medium(15) + Low(5)

**Examples**:
| Findings | Expected Score |
|----------|----------------|
| 2 Critical | 60 (minimum floor) |
| 1 Critical + 2 High | 60 (critical boost) |
| 3 High + 5 Medium | 39 (weighted sum) |
| 0 vulnerabilities | 0 |

**Debugging**:
1. Check you're viewing the right filter set
2. Count actual critical/high/medium/low in table
3. Apply formula manually to verify
4. Check console for calculation logs

### Build Tool Specific Issues

#### Maven: "Failed to parse pom.xml"
**Symptoms**: Maven project not recognized

**Solutions**:
1. Verify `pom.xml` is valid XML:
   ```bash
   xmllint --noout pom.xml
   ```
2. Check file encoding is UTF-8
3. Remove BOM (Byte Order Mark) if present
4. Simplify complex parent POM structures

#### Gradle: "Build file not found"
**Symptoms**: Gradle project not detected

**Solutions**:
1. Check for correct file names:
   - `build.gradle` (Groovy DSL)
   - `build.gradle.kts` (Kotlin DSL)
2. Verify file is in project root, not `app/` subdirectory
3. Check for `settings.gradle` which might redirect
4. Try with simplified build file to isolate syntax issues

### Performance Issues

#### Analysis is very slow
**Symptoms**: Analysis takes 5+ minutes

**Expected Times**:
- Small project (< 20 deps): 10-30 seconds
- Medium project (20-50 deps): 30-60 seconds
- Large project (50-100 deps): 1-2 minutes
- Very large project (100+ deps): 2-5 minutes

**If Slower Than Expected**:

1. **Check for AI timeout**: Disable AI for faster results
2. **Network issues**: Check connectivity to OSV database
3. **Large dependency trees**: Maven projects with 100+ deps take time
4. **Outdated cache**: First analysis is slower (subsequent use cache)

**Optimization Tips**:
```bash
# Increase JVM heap for large projects
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx4g"

# Skip AI for initial scan
# Disable "Enable AI-assisted analysis"

# Use cached results when possible
# Click "Load Cached" instead of re-analyzing
```

#### UI is unresponsive
**Symptoms**: Page freezes during analysis

**Solutions**:
1. Wait - large projects may take time
2. Check browser CPU usage (may be processing large data)
3. Reduce table size with filters
4. Clear browser cache and reload

### Database Issues

#### "H2 console not accessible"
**Symptoms**: Can't access `http://localhost:8080/buildaegis/h2-console`

**Solutions**:
1. Verify property in `application.properties`:
   ```properties
   spring.h2.console.enabled=true
   ```
2. Check correct JDBC URL in console:
   ```
   jdbc:h2:file:./data/buildaegis
   ```
3. Try without path:
   ```
   jdbc:h2:mem:buildaegis
   ```

#### Database locked error
**Symptoms**: "Database is locked" or "Connection refused"

**Solutions**:
1. Kill any hanging Java processes:
   ```bash
   # macOS/Linux
   pkill -f "buildaegis"
   
   # Windows
   taskkill /F /IM java.exe
   ```
2. Delete lock files:
   ```bash
   rm ./data/*.lock.db
   ```
3. Restart application

### Encryption Issues

#### "Encryption secret is not configured"
**Symptoms**: Warning about API key encryption

**Explanation**: API keys are stored encrypted only when `buildaegis.encryption.secret` is set.

**Solutions**:
```properties
# In application.properties or application-dev.properties
buildaegis.encryption.secret=your-secret-key-min-16-chars
```

**Important**:
- Set this BEFORE saving any API keys
- Changing secret later makes previously encrypted keys unreadable
- Use a long, random string (32+ characters recommended)

### Miscellaneous Issues

#### "CORS error" in browser console
**Symptoms**: API calls blocked with CORS errors

**Solutions**:
1. Ensure accessing via `localhost:8080`, not `127.0.0.1:8080`
2. Check no proxy/VPN interfering
3. Try different browser
4. Verify Spring CORS configuration in `application.properties`

#### "Out of memory" errors
**Symptoms**: Java heap space errors

**Solutions**:
```bash
# Increase heap size
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx4g -Xms2g"

# For very large projects
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx8g -Xms4g"
```

#### Date/time format issues
**Symptoms**: Timestamps show wrong timezone

**Solutions**:
```properties
# In application.properties
spring.jackson.time-zone=UTC
# or your local timezone
spring.jackson.time-zone=America/New_York
```

## Getting Help

### Debug Information to Collect

When reporting issues, include:

1. **Environment**:
   ```bash
   java -version
   ./mvnw -version
   ```

2. **Application logs**:
   ```bash
   cat logs/application.log | tail -100
   ```

3. **Browser console**:
   - Open DevTools (F12)
   - Console tab - screenshot any errors
   - Network tab - screenshot failed requests

4. **Reproduction steps**:
   - Exact project path used
   - Build tool selected
   - AI provider (if any)
   - What you expected vs what happened

### Checking System Health

Quick diagnostic commands:

```bash
# 1. Check Java version
java -version  # Should show Java 21

# 2. Check Maven wrapper
./mvnw -version

# 3. Test backend running
curl http://localhost:8080/buildaegis/actuator/health

# 4. Check database
curl http://localhost:8080/buildaegis/h2-console  # Should return HTML

# 5. Test API
curl "http://localhost:8080/buildaegis/api/project/scan?projectPath=/valid/path"
```

## Known Limitations

### By Design

1. **Gradle accuracy**: MEDIUM confidence due to dynamic resolution
2. **False positives**: Possible with transitive dependencies
3. **AI optional**: Core detection works without AI
4. **Cache scoped by provider**: Same dep with different AI = separate cache

### Being Addressed

1. Multi-module project aggregation improvements
2. Better Gradle build file parsing
3. Enhanced false positive detection

---

*For setup help, see [Getting Started](getting-started.md). For feature details, see [Features Guide](features.md).*
