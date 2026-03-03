# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build fat JAR (skipping tests)
mvn clean package -DskipTests

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=SamplePluginSamplerTest

# Full build + test
mvn clean package
```

The output JAR is at `target/jmeter-sample-plugin-1.0.0.jar`. Copy it to JMeter's `lib/ext/` directory to install.

## Architecture

This is a JMeter 5.6.3 plugin (Java 17) built as a fat JAR via `maven-shade-plugin`. JMeter itself is a `provided` dependency — never bundled into the output JAR.

### Plugin Components

**Sampler** (`com.sagar.jmeter.sampler`)
- `SamplePluginSampler` — extends `AbstractSampler`, implements the `sample()` method which is called per-thread per-iteration during a test run. Properties are persisted via JMeter's `getPropertyAsString`/`setProperty` API using static key constants (`TARGET_URL`, `TIMEOUT_MS`, `PAYLOAD`).
- `SamplePluginSamplerUI` — extends `AbstractSamplerGui` (Swing), provides the GUI panel in JMeter's GUI mode. `modifyTestElement()` writes UI values into the sampler; `configure()` reads them back.

**Listener** (`com.sagar.jmeter.listener`)
- `SamplePluginListener` — implements `SampleListener`, logs each sample result after it completes.

### JMeter Service Discovery

`src/main/resources/META-INF/services/sampler.com.sagar.jmeter.SamplePluginSamplerUI` registers the UI class so JMeter discovers it automatically at startup.

### CI/CD

- `build.yml` — runs on push to `main`/`develop` and on PRs to `main`
- `release.yml` — triggers on `v*.*.*` tags, publishes the JAR as a GitHub release
- `claude-review.yml` — on PRs touching `src/**/*.java` or `pom.xml`, sends a diff to the Claude API (`ANTHROPIC_API_KEY` secret required) and posts the review as a PR comment
