# 📊 Configurable Aggregate Report (AI-Powered) — JMeter Plugin

> A file-based Apache JMeter listener plugin for post-test JTL analysis. Load a results file and get
> a filterable aggregate table, CSV export, and an AI-generated HTML performance report — with zero runtime overhead.

---

## ✨ Features at a Glance

| Feature                        | Description                                                                                       |
|--------------------------------|---------------------------------------------------------------------------------------------------|
| 📂 **JTL File Processing**     | Browse and load JTL files — the metrics table populates instantly                                 |
| ⏱️ **Start / End Offset**      | Exclude ramp-up and ramp-down periods by entering a time window in seconds                        |
| 📈 **Configurable Percentile** | Set any percentile value: 50th, 90th, 95th, 99th, or custom                                       |
| 🔍 **Transaction Search**      | Filter the table by transaction name using plain text or regular expressions                       |
| 👁️ **Column Visibility**      | Show or hide any column via a dropdown multi-select control                                       |
| ✅ **Pass / Fail Counts**       | Dedicated columns for transactions passed and transactions failed                                 |
| 🕐 **Test Time Info**          | Start Date/Time, End Date/Time, and total Duration shown automatically                            |
| 🔀 **Sortable Columns**        | Click any column header to sort ascending; click again for descending                             |
| 🚨 **SLA Thresholds**          | Set Error % and Response Time thresholds — breaching cells are highlighted in red                  |
| 💾 **CSV Export**              | Save all visible columns to a CSV file with one click                                             |
| 🤖 **AI Performance Report**   | Generate a styled HTML report with deep-dive analysis, powered by any OpenAI-compatible AI provider |
| 📊 **Chart Interval**          | Configure the time-bucket interval for performance charts (default: 30 seconds, or set custom)     |
| 🚫 **No Live Metrics**         | Designed for post-test JTL analysis — no runtime overhead                                         |

---

## 📦 Installation

### From Releases (Recommended)

1. Download the latest JAR from
   the [GitHub Releases](https://github.com/sagaraggarwal86/Configurable_Aggregate_Report/releases) page or click here
   to download
   instantly [latest JAR](https://github.com/sagaraggarwal86/Configurable_Aggregate_Report/releases/download/v2.7.0/Configurable_Aggregate_Report-3.1.0.jar)
2. Copy it to your JMeter `lib/ext/` directory:
   ```
   <JMETER_HOME>/lib/ext/Configurable_Aggregate_Report-3.1.0.jar
   ```
3. Restart JMeter
4. *(Optional — CLI mode)* Copy the wrapper scripts to `<JMETER_HOME>/bin/`:
   ```
   <JMETER_HOME>/bin/car-cli-report.bat     (Windows)
   <JMETER_HOME>/bin/car-cli-report.sh      (macOS / Linux)
   ```
   The scripts are in the `src/main/scripts/` directory of the source repository.

### Build from Source

**Prerequisites:** Java 17+, Maven 3.6+

```bash
git clone https://github.com/sagaraggarwal86/Configurable_Aggregate_Report.git
cd Configurable_Aggregate_Report
mvn clean verify
cp target/Configurable_Aggregate_Report-3.1.0.jar $JMETER_HOME/lib/ext/
```

> **Publishing to Maven Central** requires the `release` profile (sources JAR, Javadoc JAR, GPG signing):
> ```bash
> mvn deploy -P release
> ```

---

## 🚀 Quick Start

1. In JMeter: **Test Plan → Add → Listener → Configurable Aggregate Report**
2. Click **Browse** → select a `.jtl` results file
3. The metrics table populates immediately
4. Adjust filters as needed — the table updates instantly without re-browsing

---

## 🖥️ UI Layout

```
┌─ Name / Comments ──────────────────────────────────────────────────────┐
├─ Write results to file / Read from file ───────────────────────────────┤
│   Filename [________________________________]  [Browse...]              │
├─ Filter Settings ──────────────────────────────────────────────────────┤
│   Start Offset (s)  │  End Offset (s)  │  Percentile (%)               │
│   [Select Columns ▼]   Search: [______________]  [✓ RegEx]             │
├─ Test Time Info ──────────────────────┬─ SLA Thresholds ───────────────┤
│   Start    End    Duration            │  Error %  Response Time  (ms)   │
├─ Results Table (sortable) ─────────────────────────────────────────────┤
│   Transaction Name  │  Count  │  Passed  │  Failed  │  Avg(ms)  │ ...  │
│   HTTP Request      │   19    │    0     │    19    │   448     │ ...  │
│   TOTAL             │   19    │    0     │    19    │   448     │ ...  │
├────────────────────────────────────────────────────────────────────────┤
│   [Save Table Data]  [▼ Provider]  [Generate AI Report]               │
│                                    Chart Interval (s, 0=auto): [0]     │
└────────────────────────────────────────────────────────────────────────┘
```

<img src="docs/img.jpg" alt="Plugin screenshot" width="500">

---

## 📋 Table Columns

| Column                     | Description                                    |
|----------------------------|------------------------------------------------|
| **Transaction Name**       | Sampler label — always visible                 |
| **Count**                  | Total number of samples                        |
| **Passed**                 | Count of successful samples                    |
| **Failed**                 | Count of failed samples                        |
| **Avg (ms)**               | Mean response time                             |
| **Min (ms)**               | Fastest recorded response                      |
| **Max (ms)**               | Slowest recorded response                      |
| **Pnn (ms)**               | Configurable percentile column (default: P90)  |
| **Std. Dev.**              | Standard deviation of response times           |
| **Error Rate**             | Percentage of failed samples                   |
| **TPS**                    | Transactions per second (throughput)           |

All columns are **sortable** — click the header to sort ascending, click again for descending, click a third time to reset.

Use **Select Columns ▼** to show or hide any column except Transaction Name.

---

## 🔍 Transaction Search

Filter the results table by typing in the **Search** field. Only matching transactions are shown.

| Mode         | Behaviour                                     | Example                            |
|--------------|-----------------------------------------------|------------------------------------|
| **Plain text** (default) | Case-insensitive substring match  | `login` matches `Login Flow`       |
| **RegEx** (checkbox on)  | Java regex via `Pattern.find()`   | `Login\|Checkout` matches either   |

> Invalid regex patterns are silently ignored — the table shows no matches rather than throwing an error.

---

## ⏱️ Start / End Offset Filtering

Offsets let you focus analysis on the steady-state portion of a test by excluding ramp-up and ramp-down samples. Both
values are in seconds, measured from the first sample in the file.

```
Test timeline:  0s────5s────────────25s────30s
All samples:    xxxxxx|=============|xxxxxx
                ^skip  ^included     ^skip

Start Offset = 5   →  skip samples before 5s from test start
End Offset   = 25  →  skip samples after 25s from test start
```

| Start Offset | End Offset | Behaviour                                       |
|--------------|------------|-------------------------------------------------|
| *(empty)*    | *(empty)*  | All samples included                            |
| `5`          | *(empty)*  | Skip first 5 seconds; include the rest          |
| *(empty)*    | `25`       | Include up to 25 seconds; skip everything after |
| `5`          | `25`       | Include only the 5s – 25s window                |

> **Tip:** Changing offset values re-parses the JTL file automatically after a brief pause (300 ms debounce) — no need to re-browse.

---

## 🕐 Test Time Info

Displayed automatically below the filter settings after loading a JTL file.

| Field               | Description                                                |
|---------------------|------------------------------------------------------------|
| **Start Date/Time** | Timestamp of the first included sample (local timezone)    |
| **End Date/Time**   | Timestamp when the last included sample completed          |
| **Duration**        | Wall-clock time from first sample start to last sample end |

> **Note:** Duration may be slightly longer than `End Offset − Start Offset` because it includes
> the response time of the last sample within the window.

---

## 🚨 SLA Thresholds

Set live SLA thresholds in the **SLA Thresholds** panel. Breaching cells are highlighted in **red bold** — no re-parse required.

| Field                | What it does                                                        |
|----------------------|---------------------------------------------------------------------|
| **Error %**          | Highlight Error Rate cells exceeding this value (1–99)              |
| **Response Time**    | Choose **Avg (ms)** or **Pnn (ms)** from the dropdown, then enter a threshold in milliseconds |

- The TOTAL row is never highlighted regardless of values.
- Leave a field blank to disable that threshold.

---

## 💾 Saving Table Data

1. Click **Save Table Data** at the bottom of the panel
2. Choose a save location — the default filename is `aggregate_report.csv`
3. Only **currently visible columns** are exported (header row is always included)

---

## 📊 Chart Interval

The **Chart Interval (s, 0=auto)** field at the bottom of the panel controls the time-bucket width used for performance charts in the AI report.

| Value | Behaviour                                                   |
|-------|-------------------------------------------------------------|
| `0`   | Auto — uses the default 30-second bucket interval           |
| `10`  | Each chart data point represents a 10-second window         |
| `60`  | Each chart data point represents a 1-minute window          |

Valid range: 0–3600 seconds.

---

## 🔧 Sub-Result Filtering

JMeter writes embedded sub-requests as separate rows in the JTL file (e.g. `HTTP Request-0`, `HTTP Request-1`). The
plugin automatically detects and excludes these — only parent samples are aggregated, matching the behaviour of JMeter's
built-in Aggregate Report.

---

## 🤖 AI Performance Report

Click **Generate AI Report** to analyse the loaded JTL data with any supported AI provider.
A save dialog lets you choose where to save the self-contained HTML report.

**Supported providers:** Groq (free), Gemini (free), Mistral (free), DeepSeek (free), OpenAI (paid), Claude (paid) — or any OpenAI-compatible endpoint.

### What the Report Contains

| Section                       | Description                                                                                                        |
|-------------------------------|--------------------------------------------------------------------------------------------------------------------|
| **Executive Summary**         | End-to-end narrative paragraph: scenario context, system behaviour under load, dominant constraint, PASS/FAIL verdict, and highest-priority action |
| **Bottleneck Analysis**       | Technical interpretation of throughput, latency, and error pattern cross-correlated into a single bottleneck classification (throughput-bound, latency-bound, or error-bound), followed by a supporting metrics table |
| **Error Analysis**            | Error pattern characterisation (load-correlated surge vs. systemic defect), threshold breach verdict, and operational impact assessment, followed by a pass/fail accounting table |
| **Advanced Web Diagnostics**  | Response time decomposed into network establishment, server processing, and transfer phases with ms and % values; dominant phase identified with remediation focus, followed by a phase breakdown table |
| **Root Cause Hypotheses**     | Ranked list of system-level hypotheses, each citing a specific metric value and naming the implicated component layer |
| **Recommendations**           | Prioritised action table (3–7 items) derived directly from the analysis findings                                   |
| **Verdict**                   | Single PASS or FAIL sentence anchored to the decisive aggregate metric and threshold value                         |
| **Transaction Metrics Table** | Full per-transaction breakdown matching the plugin table                                                            |
| **Performance Charts**        | Four time-series charts: Average Response Time, Error Rate, Throughput, and Bandwidth                              |

### API Key Setup

Create `ai-reporter.properties` in `$JMETER_HOME/bin/` and set at least one provider's API key:

```properties
# Groq (free — recommended for getting started)
ai.reporter.groq.api.key=gsk_your-key-here

# Or any other provider:
# ai.reporter.openai.api.key=sk-your-key-here
# ai.reporter.gemini.api.key=AIza-your-key-here
# ai.reporter.claude.api.key=sk-ant-your-key-here
# ai.reporter.mistral.api.key=your-key-here
# ai.reporter.deepseek.api.key=your-key-here
```

Select the provider from the dropdown next to the **Generate AI Report** button.

---

## 💻 CLI Mode (Headless)

Generate an AI performance report from the command line — no JMeter GUI required.

### Setup

Copy the wrapper scripts to your JMeter `bin/` directory:

```
<JMETER_HOME>/bin/car-cli-report.bat    ← Windows
<JMETER_HOME>/bin/car-cli-report.sh     ← macOS / Linux
<JMETER_HOME>/lib/ext/Configurable_Aggregate_Report-3.1.0.jar  ← already installed
```

The scripts auto-detect the JMeter installation from their own location — no environment variables needed.

### Quick Start

**Windows:**
```cmd
car-cli-report.bat -i results.jtl --provider groq --config ai-reporter.properties
```

**macOS / Linux:**
```bash
./car-cli-report.sh -i results.jtl --provider groq --config ai-reporter.properties
```

### All Options

```
Required:
  -i, --input FILE            JTL file path
  --provider STRING           provider name, case-insensitive
                              (groq, openai, claude, gemini, mistral, deepseek)
  --config FILE               path to ai-reporter.properties

Output:
  -o, --output FILE           HTML report output path (default: ./report.html)

Filter Options:
  --start-offset INT          seconds to trim from start
  --end-offset INT            seconds to trim from end
  --percentile INT            percentile 1-99 (default: 90)
  --chart-interval INT        seconds per chart bucket, 0=auto (default: 0)
  --search STRING             label filter text
  --regex                     treat --search as regex

Report Metadata:
  --scenario-name STRING      scenario name for report header
  --description STRING        scenario description
  --virtual-users INT         virtual user count for report header

SLA Thresholds:
  --error-sla INT             error rate threshold % (1-99)
  --rt-sla LONG               response time threshold in ms
  --rt-metric avg|percentile  which RT column for --rt-sla (default: percentile)

Help:
  -h, --help                  print this message and exit
```

### Exit Codes

| Code | Meaning             |
|------|---------------------|
| `0`  | Success             |
| `1`  | Invalid arguments   |
| `2`  | JTL parse error     |
| `3`  | AI provider error   |
| `4`  | Report write error  |

### Example — CI/CD Pipeline

```bash
./car-cli-report.sh \
  -i results.jtl -o report.html \
  --provider openai --config /etc/jmeter/ai-reporter.properties \
  --start-offset 10 --end-offset 300 --percentile 95 \
  --scenario-name "Nightly Load Test" --virtual-users 200 \
  --error-sla 5 --rt-sla 2000 --rt-metric percentile
```

On success, the absolute path of the generated report is printed to stdout.

---

## 📋 Requirements

| Requirement   | Version                    |
|---------------|----------------------------|
| Java          | 17+                        |
| Apache JMeter | 5.6.3+                     |
| Maven         | 3.6+ *(build only)*        |
| AI API key    | *(AI report feature only)* |

---

## 🧪 Running Tests

```bash
# Build and run all tests
mvn clean verify

# Unit tests only
mvn test

# Standalone UI preview — no JMeter installation needed
mvn test-compile exec:java
```

---

## 🤝 Contributing

Pull requests and issues are welcome!
Please test with JMeter 5.6+ on Windows, macOS, and Linux.

---

## 📄 License

Apache License 2.0 — see [LICENSE](LICENSE) for details.

## 👋 Visitors
![](https://komarev.com/ghpvc/?username=sagaraggarwal86)