# Cellar Guard — Java user manual

**Catch CSV export changes that would break a customer's import, before you ship. Runs on every pull request.**

Cellar Guard is a Java 21+ CSV contract checker. It compares a candidate CSV export with the expectations of one or more downstream consumers. When a required header disappears or a declared value rule fails, its command-line checker returns a blocking exit code that CI can use as a release gate.

## Use the browser checker on a phone or computer

1. Open the [Cellar Guard CSV checker](https://cellar-csv.vercel.app/guard-app.html).
2. Choose **Load breaking example**. The inventory report fails because the export renamed `order_id` to `id`. Load the passing example to see the expected structure.
3. Enter one expected column name per line under **Import rules**, choose the delimiter, and tap **Use these rules**. The builder requires those columns and checks that each CSV record has the correct number of fields. Select your own CSV or paste its text. Open **Import or edit JSON** to load a contract with advanced rules.
4. Select a file to validate the contract or run the check automatically when both files are ready. If you paste or edit text, select **Validate contract** or **Check export**. Expand each integration for record numbers and suggested fixes, then use **Download report** to save the JSON result.

Use **Download contract** to save valid JSON. To build a contract that requires all columns from a known-good CSV, open **Create a starter contract from a known-good CSV**, choose its delimiter, and select **Fill required columns**. Review the names, then tap **Use these rules**. You can also download the starter JSON for use in CI.

The app checks files on the device, up to 8 MiB for a CSV and 1 MiB for a contract. Selecting a file uses its contents. Editing the text field switches back to pasted text. The [contract reference](CONTRACTS.md) lists the rules. Use the Java CLI for merge gates and the local Java workspace for comparisons and history.

## Fastest start — download the ready-to-run ZIP

If you're not a developer, **use the release ZIP first**. You don't need to clone the repository or compile Java source.

On Windows x64, double-click [Cellar-Guard-Windows-x64.exe](https://github.com/suryandugy-glitch/Cellar-Studio/releases/download/guard-v3.2.0/Cellar-Guard-Windows-x64.exe) for the offline checker and built-in user manual. For the CLI or Java workspace, use the ZIP below.

1. Open [Cellar Guard's GitHub release](https://github.com/suryandugy-glitch/Cellar-Studio/releases/tag/guard-v3.2.0).
2. Under **Assets**, download `Cellar-Guard-3.2.0.zip` and extract it.
3. Check `java -version` in Command Prompt/Terminal. Install a Java **21 or newer** runtime if needed.
4. **Windows:** double-click `start-guard.bat`. **macOS/Linux:** run `sh start-guard.sh` inside the extracted directory.
5. Keep the terminal open and visit the address it prints (usually `http://127.0.0.1:8080`).
6. **Windows:** double-click `try-demo.bat`. **macOS/Linux:** run `sh try-demo.sh`. Watch it report **PASS → BLOCK → PASS**.

The ZIP contains `START_HERE.md` with beginner-friendly instructions, including a hands-on CSV repair. If you get stuck, start with that guide or the [online tutorial](https://cellar-csv.vercel.app/manual.html#demo).

### What to click in the local browser

1. Open the **Check** area of the local workspace.
2. Select the built-in **Breaking demo**, then run the check to see a failing `inventory-report` integration.
3. Read the missing-column finding and recommended fix. The report names the field the customer expects.
4. Open **Contracts** to see or edit the consumer's required columns. Use real requirements when working with your own exports.
5. For an actual customer's CSV, select a local file and run the same check. Keep sensitive data on authorized machines.

You can also use the command line; it is the same Java validation engine used by the web workspace.

## 1. Requirements and building from source (developers)

- Full **JDK 21 or later** (Java compiler required).
- Git for cloning; no Maven or Gradle required.
- Local file access to the CSV being checked; do not commit customer CSVs to Git.

```sh
git clone https://github.com/suryandugy-glitch/Cellar-Studio.git
cd Cellar-Studio
java Build.java test
java Build.java package
java -jar dist/cellar.jar --version
```

The build creates `dist/cellar.jar`. Run `java Build.java test` before updating or releasing the checker. `java Build.java package` compiles the source and writes the JAR without running tests.

## 2. Create a consumer contract

The repository includes `cellar.json` with two *synthetic* integrations. A minimal contract looks like this:

```json
{
  "version": 2,
  "integrations": [
    {
      "id": "customer-orders",
      "owner": "Customer import team",
      "required": ["order_id", "amount_cents"],
      "minRows": 1,
      "rules": {
        "order_id": {"notEmpty": true, "unique": true},
        "amount_cents": {"type": "integer", "min": 0}
      }
    }
  ]
}
```

Commit reviewed contracts alongside the producing application's code. A contract should represent what the receiving customer/import actually expects, not merely what the latest producer happens to output. Include a separate integration when two consumers rely on different rules.

Validate the contract before checking data:

```sh
java -jar dist/cellar.jar validate --config cellar.json
```

To generate a starter sample when you have no contract yet, run `java -jar dist/cellar.jar init --config new-contract.json`. The `init` command refuses to overwrite an existing file; review and edit the generated rules before use.

See [contract reference](CONTRACTS.md) for all supported fields and row rules.

## 3. Check an export

Produce a representative CSV with the application code you intend to merge, then run:

```sh
java -jar dist/cellar.jar check \
  --config cellar.json \
  --csv examples/sales.csv \
  --format markdown --summary
```

`--summary` appends Markdown to `$GITHUB_STEP_SUMMARY` when that environment variable exists (for example, in GitHub Actions). Without it, output is still printed to the console. `--format json` produces machine-readable results; `--output path/to/report.md` saves a report. Avoid placing reports in tracked paths unless reviewed.

Exit codes:

| Code | Meaning | CI consequence |
| --- | --- | --- |
| `0` | No blocking contract violation (warnings/advisory may still appear) | Check passes |
| `1` | Blocking contract violation | Check fails |
| `2` | Invalid input, configuration or runtime error; **no successful verification** | Check fails |

The `--advisory` option can deliberately avoid blocking some violations; **do not enable it for a required PR release gate**. The `diff` command compares headers and can publish Markdown with `--format markdown --summary`. Run `check` for the release decision.

Set the root option `"validateRows": true` to parse every record with a header-only contract. For a combined key, add an integration rule such as `"uniqueKeys": [["order_id", "item_id"]]`, with both columns in `required`. The [contract reference](CONTRACTS.md) documents exact matching and blank key values.

## 4. Demonstrate failure and recovery

The repository has a passing CSV and a deliberately incompatible header:

```sh
java -jar dist/cellar.jar check --config cellar.json --csv examples/sales.csv
java -jar dist/cellar.jar check --config cellar.json --csv examples/breaking-header.csv
```

The first command should exit `0`. The second should exit `1` because the inventory integration requires `order_id`, while the breaking fixture changes that header. Typical fixes are to retain the original column while introducing an alias, or update the consumer's contract **only after** the receiving application has been migrated and verified.

### Fix the exact example yourself

This is the expected file header from `cellar.json`:

```csv
order_id,sku,total_cents,currency
```

The deliberately broken export instead contains:

```csv
id,sku,total_cents,currency
```

Do not edit the original fixture. Copy `examples/breaking-header.csv` to a new file named `examples/my-repair.csv`, then rename only the first header `id` to `order_id` in that copy. Save and rerun:

```sh
java -jar dist/cellar.jar check --config cellar.json --csv examples/my-repair.csv
```

You should now see `PASS` for both sample integrations and `Gate: ALLOW`. This proves the **sample CSV** is compatible again. In a real application, fix the **export-generating code** so the next exported CSV also retains the expected column. If the rename is intentional, emit both names temporarily until consumers are migrated and their contracts reviewed. Don't simply delete the rule to silence the gate.

| Finding | What to do |
| --- | --- |
| `MISSING_COLUMN order_id` | Restore the header or temporarily emit the old and new headers |
| Invalid integer or range | Fix how the producer formats/calculates the value; review the declared type/range |
| `DUPLICATE_VALUE` or `DUPLICATE_KEY` | Fix the producer’s key generation or deduplication logic |
| `EMPTY_KEY` | Populate every field in the reported combined key |
| Too few rows | Check filtering/export conditions; verify whether an empty export is truly allowed |
| Exit `2` / input error | Correct the file path, permissions or invalid contract JSON; rerun before shipping |

## 5. Run on every pull request

This repository includes [`.github/workflows/guard-pr.yml`](../.github/workflows/guard-pr.yml). It runs when a pull request targets `main`, and on manual dispatch. The job:

1. Installs JDK 21.
2. Compiles and tests Cellar Guard.
3. Validates `cellar.json`.
4. Checks `examples/sales.csv` as a safe **synthetic placeholder**, and publishes a job summary.
5. Confirms a deliberately breaking sample is rejected with exit code `1`.

**To protect a real customer's import**, replace the placeholder with an export created **from the pull request's code**. For example:

```yaml
      - name: Generate proposed export
        run: ./gradlew exportCsv
      - name: Check proposed CSV export
        run: |
          java -jar dist/cellar.jar check \
            --config contracts/customer-orders.json \
            --csv build/exports/customer-orders.csv \
            --format markdown --summary
```

Change the generator and paths to match your application. Ensure the generator uses synthetic or approved test data, and ensure CI fails if it cannot produce the CSV. The checked file must be freshly generated; checking an unchanged fixture cannot detect real application export regressions.

Once the workflow is green in your repo, enable GitHub branch protection (or a ruleset) and **require the `Cellar Guard / csv-compatibility` status check** to prevent merges that fail. This repository's workflow does not itself configure branch protection, and GitHub administrators must enable it.

You can also copy the workflow to a separate Java project: add Cellar Guard source/build artifacts or pin a trusted release artifact, then run the same check in that project's CI. Review third-party Actions and pin them to commit SHAs per your organization's security policy.

## 6. Local web workspace

```sh
sh run.sh
# Windows: run.bat
```

Open the address printed by the process (usually `http://127.0.0.1:8080`). You can edit contracts, review checks and inspect local history there. Local web mode and optional shared deployment have different security properties; see [web security](WEB_SECURITY.md) and [operations](OPERATIONS.md) before exposing anything beyond your machine.

## 7. Troubleshooting

| Symptom | What to check |
| --- | --- |
| Java compilation fails | Confirm `java -version` is 21+ and that you installed a full JDK |
| Exit code `1` in CI | Read the blocking integration, expected header/value rule and affected CSV row numbers |
| Exit code `2` in CI | Check contract JSON, paths, permissions and export generator logs; do not treat it as success |
| CI always green | Confirm the CSV is generated from PR code and the correct reviewed contract is used |
| Browser checker cannot read your CSV | Check that the file is UTF-8 and no larger than 8 MiB |

## 8. Security and limitations

- Never commit real customer CSVs, API keys, local `data/`, or stored reports containing sensitive metadata.
- Core validation works offline; optional AI is not needed for the checker.
- Reports exclude CSV cell values by design, but column names and metadata can still be sensitive.
- Only *declared* contract rules are checked; semantic bugs outside the contract will not be caught.
- A passing check is not a guarantee of downstream compatibility across every consumer, version, or CSV dialect.
- This is a working pilot with an open-source license, **not** an independently certified service or a hosted multi-tenant SaaS.

For API details see [API](API.md); for development see [CONTRIBUTING.md](../CONTRIBUTING.md). The [source repository README](../README.md) is the product overview.
