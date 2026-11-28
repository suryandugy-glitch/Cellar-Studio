package io.cellar.desktop;

import io.cellar.Checker;
import io.cellar.Contract;
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
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.DefaultCaret;

public final class DesktopApp {
    private static final Color INK = new Color(0x212133);
    private static final Color MUTED = new Color(0x535469);
    private static final Color CANVAS = new Color(0xf5f4fa);
    private static final Color LINE = new Color(0xd9d8e5);
    private static final Color ACCENT = new Color(0x423584);
    private static final Color NAV_TEXT = new Color(0xdedbf3);
    private static final Contract DEMO_CONTRACT = Contract.parse(Demo.CONFIG);

    private final JFrame window = new JFrame("Cellar Guard");
    private final CardLayout pages = new CardLayout();
    private final JPanel content = new JPanel(pages);
    private final JLabel verdict = heading("Ready to check", 21);
    private final JLabel feedback = label("Choose a CSV and add its import rules, or try the built-in demo.");
    private final JLabel csvLabel = label("No CSV selected");
    private final JLabel rulesLabel = label("No rules loaded");
    private final JTextArea findings = textArea("");
    private final JButton chooseCsv = button("Choose CSV…", 'C', false);
    private final JButton buildRules = button("Create rules…", 'R', false);
    private final JButton importRules = button("Import JSON…", 'I', false);
    private final JButton saveRules = button("Save rules…", 'L', false);
    private final JButton check = button("Check export", 'E', true);
    private final JButton demo = button("Run breaking demo", 'D', false);
    private final JButton fixDemo = button("Apply demo fix & recheck", 'F', false);
    private final JButton leaveDemo = button("Back to my files", 'B', false);
    private final JButton saveReport = button("Save report…", 'P', false);

    private Contract contract;
    private Path contractPath;
    private Path csvPath;
    private boolean demoSelected;
    private boolean demoFixed;
    private boolean busy;
    private String report;

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
        content.add(manual(), "manual");
        window.setContentPane(layout);
        window.setMinimumSize(new Dimension(780, 540));
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        window.setSize(Math.min(1120, screen.width - 40), Math.min(790, screen.height - 100));
        window.setLocationRelativeTo(null);

        chooseCsv.addActionListener(event -> selectCsv());
        buildRules.addActionListener(event -> createRules());
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
        saveReport.addActionListener(event -> saveFile("cellar-guard-report.txt", "Text report", "txt", report));
        window.getRootPane().setDefaultButton(check);
        updateButtons();
    }

    private void show() {
        window.setVisible(true);
    }

    private JPanel sidebar() {
        JPanel sidebar = stack(INK);
        sidebar.setBorder(BorderFactory.createEmptyBorder(25, 19, 19, 19));
        sidebar.setPreferredSize(new Dimension(172, 0));

        JLabel brand = heading("Cellar Guard", 19);
        brand.setForeground(Color.WHITE);
        sidebar.add(brand);
        sidebar.add(Box.createVerticalStrut(29));
        JButton workspace = button("CSV checker", 'W', false);
        JButton help = button("User manual", 'M', false);
        for (JButton nav : List.of(workspace, help)) {
            nav.setAlignmentX(Component.LEFT_ALIGNMENT);
            nav.setMaximumSize(new Dimension(Integer.MAX_VALUE, nav.getPreferredSize().height));
            sidebar.add(nav);
            sidebar.add(Box.createVerticalStrut(9));
        }
        workspace.addActionListener(event -> {
            pages.show(content, "check");
            window.getRootPane().setDefaultButton(check);
        });
        help.addActionListener(event -> {
            pages.show(content, "manual");
            window.getRootPane().setDefaultButton(null);
        });

        sidebar.add(Box.createVerticalGlue());
        JLabel version = label("Version " + Main.VERSION);
        version.setForeground(NAV_TEXT);
        sidebar.add(version);
        return sidebar;
    }

    private JPanel workspace() {
        JPanel page = new JPanel(new BorderLayout(0, 14));
        page.setBackground(CANVAS);
        page.setBorder(BorderFactory.createEmptyBorder(22, 24, 20, 24));

        JPanel form = stack(CANVAS);
        form.add(heading("Check a CSV export", 30));
        form.add(Box.createVerticalStrut(5));
        form.add(label("See whether your CSV will work with the system that receives it."));
        form.add(Box.createVerticalStrut(14));

        JPanel inputCard = stack(Color.WHITE);
        inputCard.setBorder(cardBorder());
        csvLabel.setToolTipText("Choose the export you want to verify");
        rulesLabel.setToolTipText("Create or import rules that describe the receiving system");
        JPanel csvRow = new JPanel(new BorderLayout(7, 0));
        csvRow.setOpaque(false);
        csvRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        csvRow.add(row(heading("1  CSV export", 15), chooseCsv), BorderLayout.WEST);
        csvRow.add(csvLabel, BorderLayout.CENTER);
        inputCard.add(csvRow);
        inputCard.add(Box.createVerticalStrut(8));
        inputCard.add(row(heading("2  Import rules", 15), buildRules, importRules, saveRules));
        inputCard.add(row(rulesLabel));
        inputCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(inputCard);
        form.add(Box.createVerticalStrut(8));
        form.add(row(check, demo, fixDemo, leaveDemo));
        page.add(form, BorderLayout.NORTH);

        JPanel results = new JPanel(new BorderLayout(0, 10));
        results.setBackground(Color.WHITE);
        results.setBorder(cardBorder());
        JPanel status = new JPanel(new BorderLayout(0, 6));
        status.setOpaque(false);
        JPanel resultTitle = new JPanel(new BorderLayout(9, 0));
        resultTitle.setOpaque(false);
        resultTitle.add(verdict, BorderLayout.CENTER);
        resultTitle.add(saveReport, BorderLayout.EAST);
        status.add(resultTitle, BorderLayout.NORTH);
        status.add(feedback, BorderLayout.SOUTH);
        results.add(status, BorderLayout.NORTH);
        findings.setRows(4);
        findings.getAccessibleContext().setAccessibleName("Check findings");
        JScrollPane scroll = new JScrollPane(findings);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        results.add(scroll, BorderLayout.CENTER);
        page.add(results, BorderLayout.CENTER);
        return page;
    }

    private JPanel manual() {
        JPanel page = new JPanel(new BorderLayout(0, 11));
        page.setBackground(CANVAS);
        page.setBorder(BorderFactory.createEmptyBorder(22, 24, 20, 24));
        JLabel title = heading("User manual", 30);
        page.add(title, BorderLayout.NORTH);
        JTextArea instructions;
        try (var resource = Objects.requireNonNull(DesktopApp.class.getResourceAsStream("/desktop/manual.txt"))) {
            instructions = textArea(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalStateException("Could not load the built-in manual", error);
        }
        title.setLabelFor(instructions);
        instructions.getAccessibleContext().setAccessibleName("Cellar Guard user manual");
        instructions.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 13));
        instructions.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(instructions);
        scroll.setBorder(cardBorder());
        page.add(scroll, BorderLayout.CENTER);
        JButton tryDemo = button("Run breaking demo", 'T', true);
        tryDemo.addActionListener(event -> {
            pages.show(content, "check");
            window.getRootPane().setDefaultButton(check);
            if (!busy) startDemo();
        });
        page.add(row(tryDemo), BorderLayout.SOUTH);
        return page;
    }

    private void selectCsv() {
        Path selected = chooseFile("Choose CSV export", "CSV export", "csv");
        if (selected == null) return;
        demoSelected = false;
        csvPath = selected;
        inputsChanged();
    }

    private void loadRules() {
        Path selected = chooseFile("Import Cellar Guard rules", "JSON contract", "json");
        if (selected == null) return;
        perform(() -> Contract.parse(FilesEx.read(selected)), loaded -> acceptRules(loaded, selected));
    }

    private void createRules() {
        JTextArea columns = new JTextArea(6, 29);
        columns.setFont(uiFont(14, Font.PLAIN));
        String[] separators = {"Comma (,)", "Semicolon (;)", "Tab"};
        JComboBox<String> delimiter = new JComboBox<>(separators);
        JTextField name = new JTextField("customer-import");
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
        while (JOptionPane.showConfirmDialog(window, fields, "Create import rules",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                acceptRules(makeRules(name.getText(), columns.getText(), delimiter.getSelectedIndex()), null);
                return;
            } catch (InputError problem) {
                JOptionPane.showMessageDialog(window, problem.getMessage(), "Check rule inputs", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static Contract makeRules(String name, String columns, int separator) {
        List<String> required = columns.lines().filter(line -> !line.isBlank()).toList();
        if (required.isEmpty()) throw new InputError("Enter at least one required column name.");
        return Contract.parse(Json.write(Json.map("version", 2, "delimiter", List.of(",", ";", "\t").get(separator),
            "validateRows", true, "integrations", List.of(Json.map("id", name.strip(), "required", required)))));
    }

    private void acceptRules(Contract selected, Path source) {
        demoSelected = false;
        contract = selected;
        contractPath = source;
        inputsChanged();
    }

    private void startDemo() {
        demoSelected = true;
        demoFixed = false;
        describeInputs();
        runCheck();
    }

    private void inputsChanged() {
        describeInputs();
        resetResult("Ready to check", csvPath == null ? "Choose a CSV export to check."
            : contract == null ? "Create or import the rules for your export." : "Both inputs are ready. Click Check export.");
        updateButtons();
    }

    private void describeInputs() {
        String csvName = demoSelected ? (demoFixed ? "Fixed demo (order_id)" : "Breaking demo (id)") : fileName(csvPath);
        String contractName = demoSelected ? "Demo import rules" : contractPath != null
            ? fileName(contractPath) : "Rules created in Cellar Guard";
        setInputLabel(csvLabel, Objects.requireNonNullElse(csvName, "No CSV selected"));
        Contract active = activeContract();
        setInputLabel(rulesLabel, active == null ? "No rules loaded"
            : contractName + "  ·  " + active.integrations().size() + " integration(s)");
    }

    private static void setInputLabel(JLabel label, String text) {
        label.setToolTipText("Selected: " + text);
        label.setText(text.codePointCount(0, text.length()) > 49
            ? text.substring(0, text.offsetByCodePoints(0, 45)) + "…" : text);
    }

    private Contract activeContract() {
        return demoSelected ? DEMO_CONTRACT : contract;
    }

    private void runCheck() {
        Contract selectedContract = activeContract();
        if (selectedContract == null || (!demoSelected && csvPath == null)) return;
        resetResult("Checking export…", "Applying the selected import rules.");
        Path selectedCsv = csvPath;
        boolean sample = demoSelected;
        boolean fixed = demoFixed;
        perform(() -> Checker.check(selectedContract, sample ? Demo.csv(!fixed) : FilesEx.read(selectedCsv), false),
            this::showResult);
    }

    private void showResult(Map<String, Object> result) {
        boolean blocked = Boolean.TRUE.equals(result.get("blocking"));
        boolean passed = Boolean.TRUE.equals(result.get("passed"));
        String title = blocked ? "BLOCK · Changes needed" : passed ? "PASS · Export meets the rules"
            : "WARN · Review findings";
        verdict.setText(title);
        verdict.setForeground(blocked ? new Color(0x962e3e) : passed ? new Color(0x176245) : new Color(0x735000));
        String detail = blocked ? "Review the issue below, correct the export, and check it again."
            : passed ? "All selected integrations passed." : "Only advisory integrations reported issues.";
        if (demoSelected && blocked) detail = "The inventory importer expects order_id. Apply the demo fix to compare results.";
        if (demoSelected && demoFixed && passed) detail = "Restoring order_id fixed the inventory import rule.";
        feedback.setText(detail);
        findings.setText(explain(result));
        findings.setCaretPosition(0);
        report = Reports.text(result);
    }

    private static String explain(Map<String, Object> result) {
        StringBuilder text = new StringBuilder();
        for (Object raw : (List<?>) result.get("results")) {
            var integration = Json.object(raw);
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
        String scope = (String) result.get("scope");
        text.append(switch (scope) {
            case "headers-only" -> "Scope: CSV headers.";
            case "headers-and-row-structure" -> "Scope: CSV headers and record widths.";
            default -> "Scope: CSV headers and declared row rules.";
        });
        if (!scope.equals("headers-only")) text.append("  Records checked: ").append(result.get("rowsChecked")).append('.');
        return text.toString();
    }

    private <T> void perform(java.util.concurrent.Callable<T> action, Consumer<T> complete) {
        busy = true;
        updateButtons();
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception {
                return action.call();
            }

            @Override protected void done() {
                try {
                    complete.accept(get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    resetResult("Check interrupted", "Retry the operation.");
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    String message = cause instanceof InputError ? cause.getMessage()
                        : "Check the file path and permissions, then retry.";
                    resetResult("Input error · Check did not run", "Correct the input and retry.");
                    findings.setText(message);
                } finally {
                    busy = false;
                    updateButtons();
                }
            }
        }.execute();
    }

    private void resetResult(String title, String detail) {
        verdict.setText(title);
        verdict.setForeground(INK);
        feedback.setText(detail);
        findings.setText("");
        report = null;
    }

    private void updateButtons() {
        for (JButton input : List.of(chooseCsv, buildRules, importRules, demo)) input.setEnabled(!busy);
        saveRules.setEnabled(!busy && activeContract() != null);
        check.setEnabled(!busy && activeContract() != null && (csvPath != null || demoSelected));
        demo.setVisible(!demoSelected);
        fixDemo.setVisible(demoSelected);
        leaveDemo.setVisible(demoSelected);
        leaveDemo.setEnabled(!busy);
        fixDemo.setEnabled(!busy && demoSelected && !demoFixed && report != null);
        saveReport.setEnabled(!busy && report != null);
    }

    private Path chooseFile(String title, String type, String extension) {
        JFileChooser chooser = chooser(title, type, extension);
        return chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
    }

    private void saveFile(String suggested, String type, String extension, String text) {
        JFileChooser chooser = chooser("Save " + type.toLowerCase(java.util.Locale.ROOT), type, extension);
        chooser.setSelectedFile(new File(suggested));
        if (chooser.showSaveDialog(window) != JFileChooser.APPROVE_OPTION) return;
        Path selected = chooser.getSelectedFile().toPath();
        Path target = selected.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith("." + extension)
            ? selected : selected.resolveSibling(selected.getFileName() + "." + extension);
        try {
            for (Path input : new Path[]{csvPath, contractPath}) {
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

    private static JFileChooser chooser(String title, String type, String extension) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileFilter(new FileNameExtensionFilter(type + " (*." + extension + ")", extension));
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

    private static Font uiFont(int size, int style) {
        return UIManager.getFont("Label.font").deriveFont(style, (float) size);
    }

    private static JButton button(String content, char key, boolean primary) {
        JButton button = new JButton(content);
        button.setMnemonic(key);
        button.setFont(uiFont(13, Font.BOLD));
        button.setBackground(primary ? ACCENT : Color.WHITE);
        button.setForeground(primary ? Color.WHITE : INK);
        button.setMargin(new java.awt.Insets(6, 10, 6, 10));
        return button;
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

    private static javax.swing.border.Border cardBorder() {
        return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),
            BorderFactory.createEmptyBorder(11, 11, 11, 11));
    }

    private static JTextArea textArea(String content) {
        JTextArea area = new JTextArea(content);
        area.setForeground(INK);
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
            SwingUtilities.invokeAndWait(app.demo::doClick);
            app.awaitVerdict("BLOCK");
            SwingUtilities.invokeAndWait(app.fixDemo::doClick);
            app.awaitVerdict("PASS");

            Path csv = Files.createTempFile("cellar-guard-smoke-", ".csv");
            try {
                Files.writeString(csv, "customer;amount\nA;100\n");
                SwingUtilities.invokeAndWait(() -> {
                    app.acceptRules(makeRules("example", "customer\namount", 1), null);
                    app.csvPath = csv;
                    app.inputsChanged();
                    app.check.doClick();
                });
                app.awaitVerdict("PASS");
                SwingUtilities.invokeAndWait(app.demo::doClick);
                app.awaitVerdict("BLOCK");
                SwingUtilities.invokeAndWait(() -> {
                    app.leaveDemo.doClick();
                    app.check.doClick();
                });
                app.awaitVerdict("PASS");
                Files.writeString(csv, "customer;amount\nA\n");
                SwingUtilities.invokeAndWait(app.check::doClick);
                app.awaitVerdict("Input error");
                SwingUtilities.invokeAndWait(() -> {
                    if (app.saveReport.isEnabled()) throw new IllegalStateException("Stale report is still available");
                });
            } finally {
                Files.deleteIfExists(csv);
            }
        } finally {
            SwingUtilities.invokeAndWait(app.window::dispose);
        }
    }

    private void awaitVerdict(String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            var status = new FutureTask<>(() -> busy ? "" : verdict.getText());
            SwingUtilities.invokeLater(status);
            String result = status.get(5, TimeUnit.SECONDS);
            if (result.startsWith(expected)) return;
            if (!result.isEmpty()) throw new IllegalStateException("Expected " + expected + ", got " + result);
            Thread.sleep(40);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Timed out waiting for " + expected);
    }
}
