# Start here — Cellar Guard in 5 minutes

Welcome! This ZIP has the **already-built Java checker**. You do not need to compile anything or install Git, Maven or Gradle to run it.

To run the Windows x64 desktop app, download [Cellar-Guard-Windows-x64.exe](https://github.com/suryandugy-glitch/Cellar-Studio/releases/download/guard-v3.2.0/Cellar-Guard-Windows-x64.exe) and double-click it. Java is bundled. Open **User manual** or select **Run breaking demo**. The rest of this guide covers the Java ZIP.

## Step 1 — Install Java 21 or later

Open a terminal (Windows: Command Prompt; macOS/Linux: Terminal), type:

```text
java -version
```

You need Java **21 or newer**. If the command isn't found or prints version 17/11/8, install a Java 21+ runtime/JDK (for example Temurin) and reopen the terminal.

## Step 2 — Open the local Cellar Guard app

- **Windows:** Extract the ZIP, open the extracted `Cellar-Guard-3.2.0` folder, and double-click `start-guard.bat`.
- **macOS/Linux:** Open a terminal inside that folder and run `sh start-guard.sh`.

The black terminal window is the running Java server. Keep it open. Open the exact URL printed there in a browser (usually `http://127.0.0.1:8080`; a different port is possible).

To stop the server, return to the terminal and press `Ctrl+C` (or close the window).

## Step 3 — Watch a file fail and see its fix

This example is deliberately fake, so you can experiment safely.

- **Windows:** Double-click `try-demo.bat`.
- **macOS/Linux:** Run `sh try-demo.sh`.

You will see:

| File | Header | Expected result |
| --- | --- | --- |
| `samples/passing.csv` | `order_id,sku,total_cents,currency` | **PASS** (exit 0) |
| `samples/broken.csv` | `id,sku,total_cents,currency` | **BLOCK** (exit 1) |
| `samples/fixed.csv` | `order_id,sku,total_cents,currency` | **PASS** (exit 0) |

**Why does it block?** `contracts/example-contract.json` says the inventory customer's import requires a column named `order_id`. The broken export silently renamed it to `id`. A spreadsheet may display the CSV perfectly, but that customer's importer can fail.

## Step 4 — Fix the breaking export yourself

1. Make a **copy** of `samples/broken.csv`, named `samples/my-repair.csv`.
2. Open the copy in Notepad, VS Code or any plain-text editor.
3. Change the **first line only** from `id,sku,total_cents,currency` to `order_id,sku,total_cents,currency`.
4. Save the file as a `.csv` (plain UTF-8 text).
5. In a terminal inside the extracted folder, run:

   ```text
   java -jar cellar-guard.jar check --config contracts/example-contract.json --csv samples/my-repair.csv
   ```

   The check should now report **PASS** for both demo integrations and `Gate: ALLOW`.

**Real-life fix:** If the producing application changed its export format, update the application's **export code** to keep the existing `order_id` header (or temporarily supply both `order_id` and `id` while consumers migrate). A local edit to one generated CSV doesn't fix next week's export. Coordinate any contract change with the customer before replacing an old field.

## Step 5 — Check your own application's export

Copy your real export to a private folder on your computer and define its import requirements in a copy of `contracts/example-contract.json`:

```text
java -jar cellar-guard.jar validate --config contracts/my-contract.json
java -jar cellar-guard.jar check --config contracts/my-contract.json --csv path/to/my-export.csv
```

Use a correct path for your machine. Edit `required`, `rules`, and integration `id` to match what the actual receiving system needs. Never weaken a contract just to make an incompatible export pass.

## Step 6 — Put it on pull requests

In your application's GitHub Actions workflow, generate the proposed CSV from **that PR's source code**, then run the same `check` command. The job fails with exit 1 for broken contracts and exit 2 for tool/input errors. Protect your main branch by requiring that status check.

See `docs/CELLAR_GUARD_MANUAL.md` for the full contract specification and an example GitHub Actions job, or visit the [online Guard manual](https://cellar-csv.vercel.app/manual.html).

## If something goes wrong

- **"java is not recognized":** Install Java 21+, reopen the terminal, retry `java -version`.
- **Browser says the site isn't available:** Keep `start-guard` running and open the URL it printed; don't assume the port is 8080 if it changed.
- **Exit 1:** The CSV violated a rule. Inspect the missing column or invalid value, fix the producer or coordinate a compatible migration.
- **Exit 2:** Nothing was verified. Check file paths, JSON syntax and permissions.
- **A public Vercel page doesn't accept your CSV:** That's expected. The website is a demonstration; your downloaded Java JAR does local checks.

Privacy: Run sensitive customer exports only on authorized machines or CI systems. Don't upload them to public demos, issues or pull-request attachments.
