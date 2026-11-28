<p align="center"><img src="assets/logo.svg" alt="Cellar Studio" width="100"></p>

<h1 align="center">Cellar Studio</h1>

<p align="center"><strong>We build what comes next.</strong></p>
<p align="center">An independent software studio building useful developer tools. Our first product is Cellar Guard; more are coming soon.</p>

<p align="center">
  <a href="https://cellar-csv.vercel.app/">Cellar Studio (Home)</a> ·
  <a href="https://cellar-csv.vercel.app/products.html">Products</a> ·
  <a href="https://cellar-csv.vercel.app/about.html">About</a> ·
  <a href="https://cellar-csv.vercel.app/contribute.html">Contribute</a> ·
  <a href="https://cellar-csv.vercel.app/guard.html">Cellar Guard</a> ·
  <a href="https://cellar-csv.vercel.app/guard-app.html">Browser checker</a> ·
  <a href="https://github.com/suryandugy-glitch/Cellar-Studio/releases/tag/guard-v3.1.0">Download Guard</a> ·
  <a href="https://cellar-csv.vercel.app/manual.html">Online manual</a> ·
  <a href="docs/CELLAR_GUARD_MANUAL.md">Technical manual</a> ·
  <a href="https://cellar-csv.vercel.app/walkthrough.html">Example reports</a>
</p>

## Cellar Studio

[Cellar Studio](https://cellar-csv.vercel.app/studio.html) is the public studio homepage. Products, team information, documentation and policy pages live beneath that identity. The studio works on practical software problems with careful engineering and reproducible evidence.

**Site appearance:** Select the compact sun/moon icon in the bottom-right corner of any page to open the **Appearance** dropdown and choose **System**, **Light**, **Dark**, or a saved **Custom** theme. [Theme Studio](https://cellar-csv.vercel.app/themes.html) includes **12 curated presets** in Minimalist, Brutalist and Studio styles, plus **30 mix-and-match palette swatches** and manual HEX input. Users can create up to 12 named themes, adjust eight colors, choose a visual style (flat minimalist surfaces or bold brutalist borders and shadows), select larger text/rounded corners/reduced motion, preview, and import/export theme JSON. Saving applies the choice to Studio, Guard, the browser checker, manuals, Products, About and policy pages. Preferences stay in that browser, not on an account or server. System follows the device's light/dark setting.

## Cellar Studio accounts

The [sign-in page](https://cellar-csv.vercel.app/signin.html) is the production landing screen. It uses an email-first flow (email → password), registration and email verification, Google OAuth, and an optional **Keep me signed in** setting. Microsoft sign-in is not offered. Supabase Auth securely stores user accounts and password hashes, while server-managed cookies remember valid sessions. Guests can still explore the studio. See the [Supabase and Vercel setup guide](docs/ACCOUNTS.md).

## Products

| Product | Status | Details |
| --- | --- | --- |
| **Cellar Guard** | Working Java 21+ pilot | [Product page](https://cellar-csv.vercel.app/guard.html) and [manual](docs/CELLAR_GUARD_MANUAL.md) |
| **More products** | Coming soon | No additional names, releases or download dates have been announced |

### Cellar Guard

**Catch CSV export changes that would break a customer’s import, before you ship. Runs on every pull request.**

Cellar Guard is the only implemented product in this repository. It checks proposed CSV exports against consumer-defined contracts. For example, renaming `order_id` to `id` can break an import even when the new file looks valid in a spreadsheet.

```text
Consumer expects: order_id, sku, total_cents, currency
New export sends: id, sku, total_cents, currency

Cellar Guard: Required column "order_id" is missing.
Release gate: BLOCK (exit 1)
```

Use the browser checker for one-off checks on mobile or desktop. The Java 21+ app provides a local workspace, CLI and pull-request checks.

### Use Cellar Guard on mobile or desktop

1. Open the [CSV checker](https://cellar-csv.vercel.app/guard-app.html) on your phone or computer.
2. Try **Load breaking example**. The report identifies the missing `order_id` column. Try the passing example next.
3. Under **Import rules**, enter one required column per line, choose the delimiter, and tap **Use these rules**. Then select a CSV or paste its text. Open **Import or edit JSON** if you already have a contract or need advanced rules.
4. File selection validates the JSON immediately and runs the check when both inputs are ready. After pasting or editing text, select **Check export**. Use **Download report** to save the findings.

Use **Remember these rules on this device** to reuse required columns and the delimiter. Select **Get help** in the checker for quick answers or to contact Cellar Guard support.

Use **Validate contract** to check edited JSON without a CSV. To start from a known-good file, open **Create a starter contract from a known-good CSV** and download its header rules.

The browser reads UTF-8 CSV files up to 8 MiB and contracts up to 1 MiB. Your CSV and contract stay on your device. Use the Java workspace for revisions, history and side-by-side header comparisons, and the CLI for release gates.

### Ask the website assistant

Open [Ask Cellar](https://cellar-csv.vercel.app/studio.html?ask=1) on your phone or computer. Ask a general question, choose **Show me a CSV demo** for a worked Cellar Guard example, or ask about the page you’re on. The assistant can follow recent turns and links to relevant public pages when it uses them. Select **Expand** for a larger chat and **New chat** to clear the conversation on the page. When the model is unavailable, the site guide provides public help. The checker keeps CSVs and contracts on the device.

### Download and run (no source build)

1. Open the dedicated [Cellar Guard GitHub release](https://github.com/suryandugy-glitch/Cellar-Studio/releases/tag/guard-v3.1.0), download **Cellar-Guard-3.1.0.zip**, and extract it.
2. Install Java 21 or later if needed (`java -version` should show 21+).
3. On Windows, double-click `start-guard.bat`. On macOS/Linux, run `sh start-guard.sh` in the extracted folder. Open the local address printed in the terminal.
4. Try the included **PASS → BLOCK → FIX → PASS** demonstration: double-click `try-demo.bat` on Windows or run `sh try-demo.sh` on macOS/Linux.
5. Follow `START_HERE.md` inside the ZIP to repair `samples/broken.csv` yourself and check a customer export.

The ZIP includes the runnable JAR, all three synthetic CSVs, a contract and both beginner and technical manuals. It is **not a native `.exe` installer**; Java 21+ is required. The [online manual](https://cellar-csv.vercel.app/manual.html#demo) shows the same example.

### What it checks

- Required headers, column order, and optionally the shape of every CSV row.
- Empty values, types, allowed values, numeric ranges and row counts.
- Uniqueness of individual columns or column combinations.
- One export against multiple consumer integrations and their owners.
- Release decisions with text, Markdown or JSON results, plus optional local history.
- Exports generated by your app on a pull request, when connected to the provided CI workflow.

## Build from source (developers, Java 21+)

```sh
git clone https://github.com/suryandugy-glitch/Cellar-Studio.git
cd Cellar-Studio
java Build.java test
java Build.java package
java -jar dist/cellar.jar validate --config cellar.json
java -jar dist/cellar.jar check --config cellar.json --csv examples/sales.csv
```

The final command checks a **synthetic passing example**. Now try a breaking export:

```sh
java -jar dist/cellar.jar check --config cellar.json --csv examples/breaking-header.csv
```

Exit `1` blocks the release. Exit `0` allows it. Invalid input or a runtime error returns `2` and should also fail CI. See the [full manual](docs/CELLAR_GUARD_MANUAL.md#3-check-an-export) for report formats and the contract schema.

**How do I fix a failure?** If a report says `MISSING_COLUMN order_id`, check the CSV header and restore `order_id` in the application's export code. During an intentional rename, you can emit both `order_id` and `id` until the consumer updates. Don't change the contract merely to suppress a real compatibility problem. The [beginner guide](docs/GUARD_START_HERE.md#step-4--fix-the-breaking-export-yourself) walks through this with a safe CSV copy.

### Check duplicates across multiple columns

An order can contain several items. If the customer expects each order and item pair to appear once, save this contract as `order-lines.json`.

```json
{
  "version": 2,
  "validateRows": true,
  "integrations": [
    {
      "id": "order-lines-import",
      "required": ["order_id", "item_id"],
      "uniqueKeys": [["order_id", "item_id"]]
    }
  ]
}
```

Save this as `order-lines.csv`.

```csv
order_id,item_id
O-100,ITEM-1
O-100,ITEM-2
O-100,ITEM-1
```

```sh
java -jar dist/cellar.jar check --config order-lines.json --csv order-lines.csv
```

The last record causes `DUPLICATE_KEY` and exit `1`. The first two records pass because their item IDs differ. A blank part of the key causes `EMPTY_KEY`. From the release ZIP, substitute `cellar-guard.jar` for `dist/cellar.jar`.

Set `"validateRows": true` on a header-only contract to catch malformed quoting and records with the wrong number of fields. Contracts that already declare key, value, or row-count rules parse every row automatically. See the [contract reference](docs/CONTRACTS.md).

### Compare two exports in a pull request

```sh
java -jar dist/cellar.jar diff --config cellar.json \
  --baseline examples/sales.csv --csv examples/breaking-header.csv \
  --format markdown --summary
```

The report lists added and removed columns, newly affected integrations, and possible renames based on header names. `--summary` writes it to the GitHub Actions job summary. The local **Compare exports** screen shows the same rename suggestions. Run `check` separately for the release decision.

## Run on every pull request

The [Cellar Guard GitHub Actions workflow](.github/workflows/guard-pr.yml) triggers on every pull request targeting `main`, compiles and tests the checker, validates the contract, and runs a CSV check.

**Important setup:** It initially checks `examples/sales.csv` so the example runs safely in this repository. Replace that sample with a CSV **freshly generated from the PR's application code** to detect real customer-breaking changes. Then require the `Cellar Guard / csv-compatibility` check in your GitHub branch protection or ruleset.

Example command inside a CI step:

```sh
java -jar dist/cellar.jar check \
  --config cellar.json \
  --csv build/exports/customer-orders.csv \
  --format markdown --summary
```

Read [the PR integration walkthrough](docs/CELLAR_GUARD_MANUAL.md#5-run-on-every-pull-request) before turning it into a required merge gate. **A static sample alone cannot catch regressions in an application's new export code.**

## Local workspace

```sh
sh run.sh          # macOS / Linux
# run.bat          # Windows (run separately)
```

Open the URL printed by the Java process, normally `http://127.0.0.1:8080`. The workspace supports contract editing, checks and local reporting. No AI key is required. See [web security](docs/WEB_SECURITY.md) before hosting it for other users.

## About the team

| Team member | Role |
| --- | --- |
| **Suryandu Ganguly** | Founder & CTO |
| **Gunjan Roy** | Backend |
| **Disha Chakraborty** | CEO |
| **Debangana Debnath** | Operation Coordinator |

Additional contributors support engineering, design, testing, documentation and operations behind the scenes. The [About page](https://cellar-csv.vercel.app/about.html#people) introduces the team. These labels describe project roles, not independently verified legal appointments.

## Repository layout

| Location | Purpose |
| --- | --- |
| `src/main/java/io/cellar/` | Java CSV validator, CLI and local web server |
| `src/test/` | Java and browser tests |
| `cellar.json` and `examples/` | Synthetic consumer contracts and sample CSVs |
| `.github/workflows/guard-pr.yml` | Pull-request CSV compatibility gate |
| `docs/CELLAR_GUARD_MANUAL.md` | Installation, contracts, CI, troubleshooting and safety manual |
| `deploy/pages/` | Studio homepage, Guard presentation, manual and company/legal pages |
| `deploy/build-vercel.mjs` | Static Vercel site builder |

The primary website remains **[Cellar Studio](https://cellar-csv.vercel.app/)**. Its [Products page](https://cellar-csv.vercel.app/products.html) lists the implemented **[Cellar Guard](https://cellar-csv.vercel.app/guard.html)** and a coming-soon placeholder. Retired product catalogue pages and unrelated implementations remain absent from the current source tree; Git history retains previous revisions.

## Development and limitations

- Run `java Build.java test` for Java changes and `node deploy/build-vercel.mjs && node --test deploy/theme.test.mjs` for website changes.
- Reports avoid storing CSV cell values; treat customer data, headers, and contract metadata as sensitive nonetheless.
- Validation proves only that the **declared rules** passed, not that every downstream behavior is correct.
- The public checker validates files in a browser worker. The Java workspace and CLI handle saved history and CI runs.
- No commercial service, security certification, or production SLA is claimed.

Contributions are welcome: read [CONTRIBUTING.md](CONTRIBUTING.md), [security guidance](SECURITY.md) and [the docs index](docs/README.md). The code is provided under the [MIT license](LICENSE).

New contributors can begin at the [public Contribute page](https://cellar-csv.vercel.app/contribute.html), which explains engineering, testing, design, documentation and feedback contributions and the pull-request workflow.
