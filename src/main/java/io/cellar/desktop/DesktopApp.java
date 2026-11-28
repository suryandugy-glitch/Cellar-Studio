package io.cellar.desktop;

import io.cellar.Checker;
import io.cellar.Contract;
import io.cellar.Csv;
import io.cellar.Demo;
import io.cellar.FilesEx;
import io.cellar.InputError;
import io.cellar.Json;
import io.cellar.Main;
import io.cellar.Reports;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.DefaultCaret;

import static io.cellar.desktop.StudioTheme.*;

public final class DesktopApp {
    private static final Contract DEMO_CONTRACT = Contract.parse(Demo.CONFIG);
    private static final List<String> SEPARATORS = List.of(",", ";", "\t");

    private final JFrame window = new JFrame("Cellar Guard");
    private final CardLayout pages = new CardLayout();
    private final JPanel content = new JPanel(pages);
    private final JLabel verdict = heading("Ready to check", 19);
    private final JLabel feedback = label("Choose a CSV and add its import rules, or try the built-in demo.");
    private final JLabel csvLabel = label("No CSV selected");
    private final JLabel rulesLabel = label("No rules loaded");
    private final JLabel resultCounts = label("");
    private final JTextArea findings = textArea("");
    private final JComboBox<ResultView> resultView = new JComboBox<>(ResultView.values());
    private final JButton chooseCsv = button("Choose CSV…", 'C', false);
    private final JButton buildRules = button("Create rules…", 'R', false);
    private final JButton importRules = button("Import JSON…", 'I', false);
    private final JButton saveRules = button("Save rules…", 'L', false);
    private final JButton check = button("Check export", 'E', true);
    private final JButton demo = button("Run breaking demo", 'D', false);
    private final JButton fixDemo = button("Apply demo fix & recheck", 'F', false);
    private final JButton leaveDemo = button("Back to my files", 'B', false);
    private final JButton saveReport = button("Save report…", 'P', false);
    private final JButton copyReport = button("Copy report", 'Y', false);
    private final JButton pickBaseline = button("Choose baseline…", 'N', false);
    private final JButton pickCandidate = button("Choose new export…", 'X', false);
    private final JButton compare = button("Compare headers", 'H', true);
    private final JButton compareSample = button("Compare samples", 'S', false);
    private final JButton saveComparison = button("Save comparison…", 'P', false);
    private final JComboBox<String> compareSeparator = new JComboBox<>(new String[]{"Comma (,)", "Semicolon (;)", "Tab"});
    private final JLabel baselineLabel = label("Select a previous export");
    private final JLabel candidateLabel = label("Select the new export");
    private final JLabel comparisonStatus = heading("Choose two exports or try the samples", 18);
    private final JTextArea comparisonResults = textArea("");

    private Contract contract;
    private Path contractPath;
    private Path csvPath;
    private Path baselinePath;
    private Path candidatePath;
    private String comparisonReport;
    private File lastDirectory;
    private boolean demoSelected;
    private boolean demoFixed;
    private boolean busy;
    private Map<String, Object> report;

    private enum ResultView {
        ALL("All integrations"), ISSUES("Issues only"), COLUMNS("CSV columns");
        private final String label;

        ResultView(String label) {
            this.label = label;
        }

        @Override public String toString() {
            return label;
        }
    }

    private record RuleDraft(String name, String columns, int separator) {}
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--smoke-test")) {
            smokeTest();
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                new DesktopApp().show();
            } catch (RuntimeException error) {
                JOptionPane.showMessageDialog(null, "Cellar Guard could not open. Restart the app and try again.",
                    "Cellar Guard startup error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private DesktopApp() {
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        window.setIconImage(new ImageIcon(Objects.requireNonNull(
            DesktopApp.class.getResource("/desktop/icon.png"))).getImage());

        JPanel layout = new JPanel(new BorderLayout());
        layout.add(sidebar(), BorderLayout.WEST);
        layout.add(content, BorderLayout.CENTER);
        content.add(workspace(), "check");
        content.add(comparison(), "compare");
        content.add(manual(), "manual");
        window.setContentPane(layout);
        window.setMinimumSize(new Dimension(780, 540));
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        window.setSize(Math.min(1120, screen.width - 40), Math.min(790, screen.height - 100));
        window.setLocationRelativeTo(null);

        chooseCsv.addActionListener(event -> selectCsv());
        buildRules.addActionListener(event -> editRules(new RuleDraft("customer-import", "", 0)));
        importRules.addActionListener(event -> loadRules());
        saveRules.addActionListener(event -> saveFile("cellar-rules.json", "JSON contract", "json", activeContract().canonical()));
        check.addActionListener(event -> runCheck());
        demo.addActionListener(event -> startDemo());
        fixDemo.addActionListener(event -> {
            demoFixed = true;
            describeInputs();
            runCheck();
        });
        leaveDemo.addActionListener(event -> {
            demoSelected = false;
            inputsChanged();
        });
        saveReport.addActionListener(event ->
            saveFile("cellar-guard-report.txt", "Text report", "txt", Reports.text(report)));
        copyReport.addActionListener(event -> copyText(Reports.text(report)));
        pickBaseline.addActionListener(event -> pickComparisonFile(true));
        pickCandidate.addActionListener(event -> pickComparisonFile(false));
        compare.addActionListener(event -> runComparison(false));
        compareSample.addActionListener(event -> runComparison(true));
        compareSeparator.addActionListener(event -> clearComparison());
        saveComparison.addActionListener(event ->
            saveFile("cellar-guard-comparison.txt", "Text report", "txt", comparisonReport));
        resultView.addActionListener(event -> renderFindings());
        window.getRootPane().setDefaultButton(check);
        updateButtons();
    }

    private void show() {
        window.setVisible(true);
    }

    private JPanel sidebar() {
        JPanel sidebar = stack(INK);
        sidebar.setBorder(BorderFactory.createEmptyBorder(25, 12, 19, 12));
        sidebar.setPreferredSize(new Dimension(172, 0));

        JLabel brand = heading("Cellar Guard", 19);
        brand.setForeground(YELLOW);
        JLabel studio = label("Cellar Studio");
        studio.setForeground(PAPER);
        sidebar.add(brand);
        sidebar.add(Box.createVerticalStrut(3));
        sidebar.add(studio);
        sidebar.add(Box.createVerticalStrut(29));
        JButton workspace = button("CSV checker", 'W', false);
        JButton comparePage = button("Compare exports", 'A', false);
        JButton help = button("User manual", 'M', false);
        workspace.setBackground(YELLOW);
        for (JButton nav : List.of(workspace, comparePage, help)) {
            nav.setAlignmentX(Component.LEFT_ALIGNMENT);
            nav.setMaximumSize(new Dimension(Integer.MAX_VALUE, nav.getPreferredSize().height));
            sidebar.add(nav);
            sidebar.add(Box.createVerticalStrut(9));
        }
        workspace.addActionListener(event -> openPage("check", check));
        comparePage.addActionListener(event -> openPage("compare", compare));
        help.addActionListener(event -> openPage("manual", null));
        content.addPropertyChangeListener("currentPage", event -> {
            workspace.setBackground(PAPER);
            comparePage.setBackground(PAPER);
            help.setBackground(PAPER);
            switch ((String) event.getNewValue()) {
                case "check" -> workspace.setBackground(YELLOW);
                case "compare" -> comparePage.setBackground(YELLOW);
                default -> help.setBackground(YELLOW);
            }
        });

        sidebar.add(Box.createVerticalGlue());
        JLabel version = label("Version " + Main.VERSION);
        version.setForeground(PAPER);
        sidebar.add(version);
        return sidebar;
    }

    private void openPage(String page, JButton defaultButton) {
        pages.show(content, page);
        content.putClientProperty("currentPage", page);
        window.getRootPane().setDefaultButton(defaultButton);
    }

    private static JPanel titleCard(String title, String description, Color background) {
        JPanel titleCard = stack(background);
        titleCard.setBorder(cardBorder());
        titleCard.add(heading(title, 29));
        titleCard.add(label(description));
        titleCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleCard.getPreferredSize().height));
        return titleCard;
    }

    private JPanel workspace() {
        JPanel page = new JPanel(new BorderLayout(0, 9));
        page.setBackground(CANVAS);
        page.setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));

        JPanel form = stack(CANVAS);
        form.add(titleCard("Check a CSV export", "See whether the export meets your customer’s import rules.", YELLOW));
        form.add(Box.createVerticalStrut(6));

        JPanel inputCard = stack(BLUE);
        inputCard.setBorder(cardBorder());
        csvLabel.setToolTipText("Choose the export you want to verify");
        rulesLabel.setToolTipText("Create or import rules that describe the receiving system");
        JPanel csvRow = new JPanel(new BorderLayout(7, 0));
        csvRow.setOpaque(false);
        csvRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        csvRow.add(row(heading("1  CSV export", 15), chooseCsv), BorderLayout.WEST);
        csvRow.add(csvLabel, BorderLayout.CENTER);
        inputCard.add(csvRow);
        inputCard.add(Box.createVerticalStrut(3));
        inputCard.add(row(heading("2  Import rules", 15), buildRules, importRules, saveRules));
        inputCard.add(row(rulesLabel));
        inputCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(inputCard);
        form.add(Box.createVerticalStrut(4));
        form.add(row(check, demo, fixDemo, leaveDemo));
        page.add(form, BorderLayout.NORTH);

        JPanel results = new JPanel(new BorderLayout(0, 6));
        results.setBackground(PAPER);
        results.setBorder(cardBorder());
        JPanel status = new JPanel(new BorderLayout(0, 4));
        status.setOpaque(false);
        JPanel resultTitle = new JPanel(new BorderLayout(9, 0));
        resultTitle.setOpaque(false);
        decorateVerdict(BLUE);
        resultTitle.add(verdict, BorderLayout.CENTER);
        resultTitle.add(row(copyReport, saveReport), BorderLayout.EAST);
        status.add(resultTitle, BorderLayout.NORTH);
        status.add(feedback, BorderLayout.CENTER);
        resultView.getAccessibleContext().setAccessibleName("Result view");
        resultView.setToolTipText("View all integrations, issues only, or the CSV column names");
        status.add(row(resultView, resultCounts), BorderLayout.SOUTH);
        results.add(status, BorderLayout.NORTH);
        findings.setRows(4);
        findings.getAccessibleContext().setAccessibleName("Check findings");
        JScrollPane scroll = new JScrollPane(findings);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        results.add(scroll, BorderLayout.CENTER);
        page.add(results, BorderLayout.CENTER);
        return page;
    }

    private JPanel comparison() {
        JPanel page = new JPanel(new BorderLayout(0, 9));
        page.setBackground(CANVAS);
        page.setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));
        JPanel form = stack(CANVAS);
        form.add(titleCard("Compare CSV exports", "See which headers were added, removed or reordered.", CORAL));
        form.add(Box.createVerticalStrut(6));

        JPanel inputs = stack(BLUE);
        inputs.setBorder(cardBorder());
        inputs.setAlignmentX(Component.LEFT_ALIGNMENT);
        inputs.add(comparisonInput(pickBaseline, baselineLabel));
        inputs.add(comparisonInput(pickCandidate, candidateLabel));
        inputs.add(row(fieldLabel("Shared separator", compareSeparator), compareSeparator));
        form.add(inputs);
        form.add(Box.createVerticalStrut(4));
        form.add(row(compare, compareSample));
        page.add(form, BorderLayout.NORTH);

        JPanel result = new JPanel(new BorderLayout(0, 7));
        result.setBackground(PAPER);
        result.setBorder(cardBorder());
        JPanel resultHeader = new JPanel(new BorderLayout(7, 0));
        resultHeader.setOpaque(false);
        resultHeader.add(comparisonStatus, BorderLayout.CENTER);
        resultHeader.add(saveComparison, BorderLayout.EAST);
        result.add(resultHeader, BorderLayout.NORTH);
        comparisonResults.getAccessibleContext().setAccessibleName("Header comparison findings");
        JScrollPane scroll = new JScrollPane(comparisonResults);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        result.add(scroll, BorderLayout.CENTER);
        page.add(result, BorderLayout.CENTER);
        return page;
    }

    private static JPanel comparisonInput(JButton button, JLabel file) {
        JPanel panel = new JPanel(new BorderLayout(7, 0));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(row(button), BorderLayout.WEST);
        panel.add(file, BorderLayout.CENTER);
        return panel;
    }

    private void pickComparisonFile(boolean baseline) {
        Path path = chooseFile(baseline ? "Choose baseline export" : "Choose new export", "CSV export", "csv");
        if (path == null) return;
        if (baseline) {
            baselinePath = path;
            setInputLabel(baselineLabel, fileName(path));
        } else {
            candidatePath = path;
            setInputLabel(candidateLabel, fileName(path));
        }
        clearComparison();
    }

    private void clearComparison() {
        comparisonReport = null;
        comparisonResults.setText("");
        comparisonStatus.setText(baselinePath == null || candidatePath == null
            ? "Choose two exports or try the samples" : "Ready to compare");
        updateButtons();
    }

    private void runComparison(boolean sample) {
        if (busy) return;
        Path baseline = baselinePath;
        Path candidate = candidatePath;
        if (!sample && (baseline == null || candidate == null)) return;
        int separator = sample ? 0 : compareSeparator.getSelectedIndex();
        comparisonReport = null;
        comparisonStatus.setText("Comparing headers…");
        comparisonResults.setText("");
        perform(() -> {
            String before = sample ? Demo.csv(false) : FilesEx.read(baseline);
            String after = sample ? Demo.csv(true) : FilesEx.read(candidate);
            return Checker.compareHeaders(before, after, SEPARATORS.get(separator).charAt(0));
        }, result -> showComparison(result, sample), message -> {
            comparisonStatus.setText("Could not compare these exports");
            comparisonResults.setText(message + "\nCheck the chosen files and their shared separator.");
        });
    }

    private void showComparison(Checker.HeaderChanges changes, boolean sample) {
        String title = sample ? "Sample comparison" : "Header comparison";
        String summary = "Added columns: " + columnsLabel(changes.added())
            + "\nRemoved columns: " + columnsLabel(changes.removed())
            + "\nShared column order changed: " + (changes.reordered() ? "Yes" : "No")
            + "\n\nRun the CSV checker with your import rules to verify the new export.";
        comparisonStatus.setText(title);
        comparisonResults.setText(summary);
        comparisonResults.setCaretPosition(0);
        comparisonReport = title + "\n\n" + summary;
    }

    private static String columnsLabel(List<String> names) {
        return names.isEmpty() ? "None" : String.join(", ", names);
    }

    private JPanel manual() {
        JPanel page = new JPanel(new BorderLayout(0, 11));
        page.setBackground(CANVAS);
        page.setBorder(BorderFactory.createEmptyBorder(22, 24, 20, 24));
        page.add(titleCard("User manual", "Follow the sample or check your own export.", CORAL), BorderLayout.NORTH);
        JTextArea instructions;
        try (var resource = Objects.requireNonNull(DesktopApp.class.getResourceAsStream("/desktop/manual.txt"))) {
            instructions = textArea(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalStateException("Could not load the built-in manual", error);
        }
        instructions.getAccessibleContext().setAccessibleName("Cellar Guard user manual");
        instructions.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 13));
        instructions.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(instructions);
        scroll.setBorder(cardBorder());
        page.add(scroll, BorderLayout.CENTER);
        JButton tryDemo = button("Run breaking demo", 'T', true);
        tryDemo.addActionListener(event -> {
            openPage("check", check);
            if (!busy) startDemo();
        });
        page.add(row(tryDemo), BorderLayout.SOUTH);
        return page;
    }

    private void selectCsv() {
        Path selected = chooseFile("Choose CSV export", "CSV export", "csv");
        if (selected != null) useCsv(selected);
    }

    private void useCsv(Path selected) {
        demoSelected = false;
        csvPath = selected;
        inputsChanged();
    }

    private void loadRules() {
        Path selected = chooseFile("Import Cellar Guard rules", "JSON contract", "json");
        if (selected != null) useImportedRules(selected);
    }

    private void useImportedRules(Path selected) {
        demoSelected = false;
        contract = null;
        contractPath = selected;
        inputsChanged();
    }

    private void editRules(RuleDraft draft) {
        JTextArea columns = new JTextArea(draft.columns(), 6, 29);
        columns.setFont(uiFont(14, Font.PLAIN));
        String[] separators = {"Comma (,)", "Semicolon (;)", "Tab"};
        JComboBox<String> delimiter = new JComboBox<>(separators);
        delimiter.setSelectedIndex(draft.separator());
        JTextField name = new JTextField(draft.name());
        JPanel fields = stack(Color.WHITE);
        fields.add(fieldLabel("Integration name", name));
        name.setMaximumSize(new Dimension(Integer.MAX_VALUE, name.getPreferredSize().height));
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        fields.add(name);
        fields.add(Box.createVerticalStrut(8));
        fields.add(fieldLabel("Required column names, one per line", columns));
        JScrollPane columnScroll = new JScrollPane(columns);
        columnScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        fields.add(columnScroll);
        fields.add(Box.createVerticalStrut(8));
        fields.add(fieldLabel("CSV separator", delimiter));
        delimiter.setAlignmentX(Component.LEFT_ALIGNMENT);
        fields.add(delimiter);
        fields.add(Box.createVerticalStrut(8));
        fields.add(label("Load headers from an export that already imports successfully."));
        String[] options = {"Use these rules", "Load headers from CSV…", "Cancel"};
        while (true) {
            int choice = JOptionPane.showOptionDialog(window, fields, "Create import rules",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (choice == 1) {
                Path baseline = chooseFile("Choose a known-good CSV", "CSV export", "csv");
                if (baseline == null) continue;
                RuleDraft current = new RuleDraft(name.getText(), columns.getText(), delimiter.getSelectedIndex());
                loadBaseline(baseline, current);
                return;
            }
            if (choice != 0) return;
            try {
                useCreatedRules(makeRules(name.getText(), columns.getText(), delimiter.getSelectedIndex()));
                return;
            } catch (InputError problem) {
                JOptionPane.showMessageDialog(window, problem.getMessage(), "Check rule inputs", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void useCreatedRules(Contract rules) {
        demoSelected = false;
        contract = rules;
        contractPath = null;
        inputsChanged();
    }

    private void loadBaseline(Path baseline, RuleDraft draft) {
        String previousFeedback = feedback.getText();
        feedback.setText("Reading column names from the known-good export…");
        perform(() -> baselineDraft(baseline, draft), completed -> {
            feedback.setText(previousFeedback);
            editRules(completed);
        }, message -> {
            feedback.setText(previousFeedback);
            JOptionPane.showMessageDialog(window, message, "Could not load CSV headers", JOptionPane.ERROR_MESSAGE);
            editRules(draft);
        });
    }

    private static RuleDraft baselineDraft(Path baseline, RuleDraft draft) {
        List<String> headers = Csv.parse(FilesEx.read(baseline), SEPARATORS.get(draft.separator()).charAt(0), false)
            .header();
        return new RuleDraft(draft.name(), String.join("\n", headers), draft.separator());
    }

    private static Contract makeRules(String name, String columns, int separator) {
        List<String> required = columns.lines().filter(line -> !line.isBlank()).toList();
        if (required.isEmpty()) throw new InputError("Enter at least one required column name.");
        if (required.stream().distinct().count() != required.size()) {
            throw new InputError("Each required column name can appear only once.");
        }
        return Contract.parse(Json.write(Json.map("version", 2, "delimiter", SEPARATORS.get(separator),
            "validateRows", true, "integrations", List.of(Json.map("id", name.strip(), "required", required)))));
    }

    private void startDemo() {
        demoSelected = true;
        demoFixed = false;
        describeInputs();
        runCheck();
    }

    private void inputsChanged() {
        describeInputs();
        if (csvPath != null && hasRules()) {
            runCheck();
        } else if (contractPath != null && contract == null) {
            validateRules();
        } else {
            showNextStep();
        }
    }

    private void showNextStep() {
        String detail = csvPath != null ? "Create rules or import JSON to run the check."
            : contract != null ? "Rules are ready. Choose a CSV export to run the check."
            : "Choose a CSV and add its import rules, or try the built-in demo.";
        resetResult("Ready to check", detail);
        updateButtons();
    }

    private void describeInputs() {
        String csvName = demoSelected ? (demoFixed ? "Fixed demo (order_id)" : "Breaking demo (id)") : fileName(csvPath);
        String contractName = demoSelected ? "Demo import rules" : contractPath != null
            ? fileName(contractPath) : "Rules created in Cellar Guard";
        setInputLabel(csvLabel, Objects.requireNonNullElse(csvName, "No CSV selected"));
        Contract active = activeContract();
        String rulesText = active == null ? Objects.requireNonNullElse(fileName(contractPath), "No rules loaded")
            : contractName + "  ·  " + active.integrations().size() + " integration(s)";
        setInputLabel(rulesLabel, rulesText);
    }

    private static void setInputLabel(JLabel label, String text) {
        label.setToolTipText("Selected: " + text);
        label.setText(text.codePointCount(0, text.length()) > 49
            ? text.substring(0, text.offsetByCodePoints(0, 45)) + "…" : text);
    }

    private Contract activeContract() {
        return demoSelected ? DEMO_CONTRACT : contract;
    }

    private boolean hasRules() {
        return activeContract() != null || contractPath != null;
    }

    private void runCheck() {
        if (busy || !hasRules()) return;
        if (!demoSelected && csvPath == null) {
            if (contractPath != null) validateRules();
            return;
        }
        resetResult("Checking export…", "Applying the selected import rules.");
        if (!demoSelected && contractPath != null) {
            readImportedRules(this::checkCsv);
        } else {
            checkCsv(activeContract());
        }
    }

    private void validateRules() {
        resetResult("Reading import rules…", "Checking the JSON contract.");
        readImportedRules(loaded -> showNextStep());
    }

    private void readImportedRules(Consumer<Contract> next) {
        Path selected = contractPath;
        contract = null;
        describeInputs();
        perform(() -> Contract.parse(FilesEx.read(selected)), loaded -> {
            contract = loaded;
            describeInputs();
            next.accept(loaded);
        }, message -> showError("Rules error", "Fix the JSON file and retry, or select another contract.",
            selected.getFileName() + "\n" + message));
    }

    private void checkCsv(Contract selectedContract) {
        Path selectedCsv = csvPath;
        boolean sample = demoSelected;
        boolean fixed = demoFixed;
        perform(() -> Checker.check(selectedContract, sample ? Demo.csv(!fixed) : FilesEx.read(selectedCsv), false),
            this::showResult, message -> showError("CSV input error", "Fix the CSV and check again.",
                sample ? message : selectedCsv.getFileName() + "\n" + message));
    }

    private void showResult(Map<String, Object> result) {
        boolean blocked = Boolean.TRUE.equals(result.get("blocking"));
        boolean passed = Boolean.TRUE.equals(result.get("passed"));
        String title = blocked ? "BLOCK · Changes needed" : passed ? "PASS · Export meets the rules"
            : "WARN · Review findings";
        verdict.setText(title);
        decorateVerdict(blocked ? CORAL : passed ? BLUE : YELLOW);
        String detail = blocked ? "Review the issue below, correct the export, and check it again."
            : passed ? "All selected integrations passed." : "Only advisory integrations reported issues.";
        if (demoSelected && blocked) detail = "The inventory importer expects order_id. Apply the demo fix to compare results.";
        if (demoSelected && demoFixed && passed) detail = "Restoring order_id fixed the inventory import rule.";
        feedback.setText(detail);
        int blockedCount = (int) result.get("failedIntegrations");
        int warnings = (int) result.get("warningIntegrations");
        int passes = ((List<?>) result.get("results")).size() - blockedCount - warnings;
        resultCounts.setText(passes + " passed   ·   " + blockedCount + " blocked   ·   " + warnings + " warnings");
        report = result;
        renderFindings();
    }

    private void renderFindings() {
        if (report == null) return;
        ResultView view = (ResultView) resultView.getSelectedItem();
        findings.setText(view == ResultView.COLUMNS ? showColumns(report) : explain(report, view == ResultView.ISSUES));
        findings.setCaretPosition(0);
    }

    private static String showColumns(Map<String, Object> result) {
        List<?> headers = (List<?>) result.get("header");
        StringBuilder text = new StringBuilder(headers.size() + " columns in export\n\n");
        for (int index = 0; index < headers.size(); index++) {
            text.append(index + 1).append("  ").append(headers.get(index)).append('\n');
        }
        return text.toString();
    }

    private static String explain(Map<String, Object> result, boolean onlyIssues) {
        StringBuilder text = new StringBuilder();
        for (Object raw : (List<?>) result.get("results")) {
            var integration = Json.object(raw);
            if (onlyIssues && Boolean.TRUE.equals(integration.get("passed"))) continue;
            String status = Boolean.TRUE.equals(integration.get("passed")) ? "PASS"
                : "warning".equals(integration.get("severity")) ? "WARN" : "BLOCK";
            text.append(status).append("  ").append(integration.get("integration"))
                .append("  ·  ").append(integration.get("owner")).append('\n');
            for (Object rawIssue : (List<?>) integration.get("issues")) {
                var issue = Json.object(rawIssue);
                String column = (String) issue.get("column");
                int record = (int) issue.get("record");
                text.append("    ");
                if (!column.isEmpty()) text.append(column).append("  ·  ");
                if (record == 1) text.append("CSV header  ·  ");
                if (record > 1) text.append("Record ").append(record).append("  ·  ");
                if ("MISSING_COLUMN".equals(issue.get("code"))) text.append("Required column is missing. ");
                text.append(issue.get("message")).append('\n');
            }
            if (Boolean.TRUE.equals(integration.get("truncated"))) {
                text.append("    Showing the first 200 of ").append(integration.get("issueCount")).append(" issues.\n");
            }
            text.append('\n');
        }
        if (text.isEmpty()) text.append("No rule issues found.\n\n");
        String scope = (String) result.get("scope");
        text.append(switch (scope) {
            case "headers-only" -> "Scope: CSV headers.";
            case "headers-and-row-structure" -> "Scope: CSV headers and record widths.";
            default -> "Scope: CSV headers and declared row rules.";
        });
        if (!scope.equals("headers-only")) text.append("  Records checked: ").append(result.get("rowsChecked")).append('.');
        return text.toString();
    }

    private <T> void perform(Callable<T> action, Consumer<T> complete, Consumer<String> fail) {
        busy = true;
        updateButtons();
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception {
                return action.call();
            }

            @Override protected void done() {
                busy = false;
                try {
                    complete.accept(get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    fail.accept("The operation was interrupted. Try again.");
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    fail.accept(cause instanceof InputError ? cause.getMessage()
                        : "Check the file path and permissions, then retry.");
                } finally {
                    updateButtons();
                }
            }
        }.execute();
    }

    private void showError(String title, String detail, String explanation) {
        resetResult(title, detail);
        findings.setText(explanation);
        findings.setCaretPosition(0);
    }

    private void decorateVerdict(Color color) {
        verdict.setForeground(INK);
        verdict.setBackground(color);
        verdict.setOpaque(true);
        verdict.setBorder(boxBorder(2, 2, 3, 8));
    }

    private void resetResult(String title, String detail) {
        verdict.setText(title);
        decorateVerdict(BLUE);
        feedback.setText(detail);
        findings.setText("");
        resultCounts.setText("");
        report = null;
    }

    private void updateButtons() {
        for (JButton input : List.of(chooseCsv, buildRules, importRules, demo)) input.setEnabled(!busy);
        saveRules.setEnabled(!busy && activeContract() != null);
        boolean validateOnly = !demoSelected && csvPath == null && contractPath != null;
        check.setText(validateOnly ? "Validate rules" : "Check export");
        check.setMnemonic(validateOnly ? 'V' : 'E');
        check.setEnabled(!busy && hasRules() && (csvPath != null || demoSelected || validateOnly));
        demo.setVisible(!demoSelected);
        fixDemo.setVisible(demoSelected);
        leaveDemo.setVisible(demoSelected);
        leaveDemo.setEnabled(!busy);
        fixDemo.setEnabled(!busy && demoSelected && !demoFixed && report != null);
        saveReport.setEnabled(!busy && report != null);
        copyReport.setEnabled(!busy && report != null);
        resultView.setEnabled(!busy && report != null);
        pickBaseline.setEnabled(!busy);
        pickCandidate.setEnabled(!busy);
        compareSeparator.setEnabled(!busy);
        compare.setEnabled(!busy && baselinePath != null && candidatePath != null);
        compareSample.setEnabled(!busy);
        saveComparison.setEnabled(!busy && comparisonReport != null);
    }

    private void copyText(String reportText) {
        try {
            StringSelection text = new StringSelection(reportText);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(text, null);
            JOptionPane.showMessageDialog(window, "Report copied to the clipboard.", "Cellar Guard",
                JOptionPane.INFORMATION_MESSAGE);
        } catch (IllegalStateException error) {
            JOptionPane.showMessageDialog(window, "The clipboard is busy. Try copying again or save the report.",
                "Could not copy report", JOptionPane.ERROR_MESSAGE);
        }
    }

    private Path chooseFile(String title, String type, String extension) {
        JFileChooser chooser = chooser(title, type, extension);
        if (chooser.showOpenDialog(window) != JFileChooser.APPROVE_OPTION) return null;
        lastDirectory = chooser.getCurrentDirectory();
        return chooser.getSelectedFile().toPath();
    }

    private void saveFile(String suggested, String type, String extension, String text) {
        JFileChooser chooser = chooser("Save " + type.toLowerCase(java.util.Locale.ROOT), type, extension);
        chooser.setSelectedFile(new File(suggested));
        if (chooser.showSaveDialog(window) != JFileChooser.APPROVE_OPTION) return;
        lastDirectory = chooser.getCurrentDirectory();
        Path selected = chooser.getSelectedFile().toPath();
        Path target = selected.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith("." + extension)
            ? selected : selected.resolveSibling(selected.getFileName() + "." + extension);
        try {
            for (Path input : new Path[]{csvPath, contractPath, baselinePath, candidatePath}) {
                if (input != null && (target.toAbsolutePath().normalize().equals(input.toAbsolutePath().normalize())
                    || (Files.exists(target) && Files.isSameFile(target, input)))) {
                    throw new InputError("Choose a different filename to preserve the original input.");
                }
            }
            if (Files.exists(target) && JOptionPane.showConfirmDialog(window, "Replace the existing file?",
                "Confirm replacement", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            FilesEx.atomicWrite(target, text + "\n");
        } catch (IOException | InputError problem) {
            JOptionPane.showMessageDialog(window, problem instanceof InputError ? problem.getMessage()
                : "Check the output location and permissions.", "Could not save file", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JOptionPane.showMessageDialog(window, "Saved " + target.getFileName(), "Cellar Guard",
            JOptionPane.INFORMATION_MESSAGE);
    }

    private JFileChooser chooser(String title, String type, String extension) {
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setDialogTitle(title);
        chooser.setFileFilter(extension.equals("csv")
            ? new FileNameExtensionFilter("CSV and TSV exports (*.csv, *.tsv)", "csv", "tsv")
            : new FileNameExtensionFilter(type + " (*." + extension + ")", extension));
        return chooser;
    }

    private static String fileName(Path path) {
        return path == null ? null : path.getFileName().toString();
    }

    private static JLabel label(String content) {
        JLabel label = new JLabel(content);
        label.putClientProperty("html.disable", true);
        label.setForeground(MUTED);
        label.setFont(uiFont(14, Font.PLAIN));
        return label;
    }

    private static JLabel fieldLabel(String content, Component field) {
        JLabel label = label(content);
        label.setLabelFor(field);
        return label;
    }

    private static JLabel heading(String content, int size) {
        JLabel label = label(content);
        label.setForeground(INK);
        label.setFont(uiFont(size, Font.BOLD));
        return label;
    }

    private static JPanel stack(Color background) {
        JPanel panel = new JPanel();
        panel.setBackground(background);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static JPanel row(Component... children) {
        JPanel panel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 7, 3));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (Component child : children) panel.add(child);
        return panel;
    }

    private static JTextArea textArea(String content) {
        JTextArea area = new JTextArea(content);
        area.setForeground(INK);
        area.setBackground(PAPER);
        area.setFont(uiFont(14, Font.PLAIN));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        ((DefaultCaret) area.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        return area;
    }

    // Invoked by the package smoke test to verify the actual launch and button wiring.
    private static void smokeTest() throws Exception {
        var start = new FutureTask<>(() -> {
            DesktopApp app = new DesktopApp();
            app.show();
            if (!app.window.isShowing()) throw new IllegalStateException("Cellar Guard did not open");
            return app;
        });
        SwingUtilities.invokeLater(start);
        DesktopApp app = start.get(20, TimeUnit.SECONDS);
        try {
            SwingUtilities.invokeAndWait(() -> {
                app.openPage("compare", app.compare);
                app.compareSample.doClick();
            });
            app.awaitStatus(app.comparisonStatus, "Sample comparison");
            SwingUtilities.invokeAndWait(() -> {
                if (!app.comparisonResults.getText().contains("Removed columns: order_id")
                    || !app.comparisonResults.getText().contains("Added columns: id")
                    || !app.saveComparison.isEnabled()) {
                    throw new IllegalStateException("The sample comparison did not report the changed columns");
                }
                app.openPage("check", app.check);
                app.demo.doClick();
            });
            app.awaitVerdict("BLOCK");
            SwingUtilities.invokeAndWait(() -> {
                app.resultView.setSelectedItem(ResultView.ISSUES);
                if (!app.findings.getText().contains("order_id") || app.findings.getText().contains("finance-summary")) {
                    throw new IllegalStateException("Issues view did not filter the passing integration");
                }
                app.resultView.setSelectedItem(ResultView.COLUMNS);
                if (!app.findings.getText().contains("1  id")) throw new IllegalStateException("Missing CSV columns");
                app.resultView.setSelectedItem(ResultView.ALL);
            });
            SwingUtilities.invokeAndWait(app.fixDemo::doClick);
            app.awaitVerdict("PASS");

            Path directory = Files.createTempDirectory("cellar-guard-smoke-");
            Path csv = directory.resolve("candidate.csv");
            Path baseline = directory.resolve("known-good.csv");
            Path rules = directory.resolve("rules.json");
            try {
                Files.writeString(baseline, "\"customer;code\";amount\nC;200\n");
                RuleDraft draft = baselineDraft(baseline, new RuleDraft("example", "", 1));
                if (!draft.columns().equals("customer;code\namount")) {
                    throw new IllegalStateException("Quoted baseline headers were not read correctly");
                }
                Contract starter = makeRules(draft.name(), draft.columns(), draft.separator());
                Files.writeString(csv, "amount;\"customer;code\"\n100;A\n");
                SwingUtilities.invokeAndWait(() -> {
                    app.openPage("compare", app.compare);
                    app.baselinePath = baseline;
                    app.candidatePath = csv;
                    app.compareSeparator.setSelectedIndex(1);
                    app.compare.doClick();
                });
                app.awaitStatus(app.comparisonStatus, "Header comparison");
                SwingUtilities.invokeAndWait(() -> {
                    if (!app.comparisonResults.getText().contains("Shared column order changed: Yes")) {
                        throw new IllegalStateException("File comparison did not report the reordered columns");
                    }
                    app.openPage("check", app.check);
                });
                Files.writeString(csv, "\"customer;code\";amount\nA;100\n");
                SwingUtilities.invokeAndWait(() -> {
                    app.useCsv(csv);
                    app.useCreatedRules(starter);
                });
                app.awaitVerdict("PASS");

                SwingUtilities.invokeAndWait(app.demo::doClick);
                app.awaitVerdict("BLOCK");
                SwingUtilities.invokeAndWait(app.leaveDemo::doClick);
                app.awaitVerdict("PASS");

                Files.writeString(rules, starter.canonical());
                SwingUtilities.invokeAndWait(() -> app.useImportedRules(rules));
                app.awaitVerdict("PASS");
                Files.writeString(rules, "{");
                SwingUtilities.invokeAndWait(app.check::doClick);
                app.awaitVerdict("Rules error");
                SwingUtilities.invokeAndWait(() -> {
                    if (app.saveReport.isEnabled() || app.copyReport.isEnabled() || app.saveRules.isEnabled()) {
                        throw new IllegalStateException("An old report or rule set is still available");
                    }
                });

                Contract changed = makeRules("example", "customer;code\nnew-column", 1);
                Files.writeString(rules, changed.canonical());
                SwingUtilities.invokeAndWait(app.check::doClick);
                app.awaitVerdict("BLOCK");

                Files.writeString(csv, "\"customer;code\";amount\nA\n");
                SwingUtilities.invokeAndWait(app.check::doClick);
                app.awaitVerdict("CSV input error");
                SwingUtilities.invokeAndWait(() -> {
                    if (app.saveReport.isEnabled() || app.copyReport.isEnabled()) {
                        throw new IllegalStateException("A previous report survived a CSV input error");
                    }
                });

                SwingUtilities.invokeAndWait(() -> {
                    app.csvPath = null;
                    app.useImportedRules(rules);
                });
                app.awaitVerdict("Ready");
                Files.writeString(rules, "{");
                SwingUtilities.invokeAndWait(app.check::doClick);
                app.awaitVerdict("Rules error");
            } finally {
                Files.deleteIfExists(rules);
                Files.deleteIfExists(baseline);
                Files.deleteIfExists(csv);
                Files.deleteIfExists(directory);
            }
        } finally {
            SwingUtilities.invokeAndWait(app.window::dispose);
        }
    }

    private void awaitVerdict(String expected) throws Exception {
        awaitStatus(verdict, expected);
    }

    private void awaitStatus(JLabel label, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            var status = new FutureTask<>(() -> busy ? "" : label.getText());
            SwingUtilities.invokeLater(status);
            String result = status.get(5, TimeUnit.SECONDS);
            if (result.startsWith(expected)) return;
            if (!result.isEmpty()) throw new IllegalStateException("Expected " + expected + ", got " + result);
            Thread.sleep(40);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Timed out waiting for " + expected);
    }
}
