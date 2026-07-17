package edu.utem.ftmk.client;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MasakGramClient — Nutrition Comparison Dashboard
 *
 * Displays all experiment results in a single comparison table showing:
 * - Reel ID
 * - Model ID
 * - Technique ID
 * - Nutrition Result (LLM generated)
 * - Ground Truth Nutrition
 * - Hallucination Percentage
 * - Status
 */
public class MasakGramClient extends JFrame {

	private static final long serialVersionUID = 1L;
	private static final String SERVER_HOST = "localhost";
    private static final int    SERVER_PORT = 5000;

    private Socket         socket;
    private PrintWriter    out;
    private BufferedReader in;

    // ================================================================
    // COLOUR THEME (Simple & Clean)
    // ================================================================
    private static final Color BG_MAIN     = new Color(248, 245, 240);
    private static final Color BG_PANEL    = new Color(255, 252, 248);
    private static final Color BG_HEADER   = new Color(240, 237, 235);
    private static final Color BORDER      = new Color(200, 195, 190);
    private static final Color TEXT        = new Color(30, 25, 25);
    private static final Color TEXT_MUTED  = new Color(80, 70, 70);
    private static final Color TEXT_GHOST  = new Color(180, 170, 170);
    private static final Color ACCENT_GREEN = new Color(70, 150, 100);
    private static final Color ACCENT_RED   = new Color(190, 80, 80);
    private static final Color ACCENT_ORANGE = new Color(200, 140, 60);
    private static final Color BG_GHOST     = new Color(235, 232, 230);

    private static final Font FONT_TITLE = new Font("Segoe UI", Font.BOLD, 16);
    private static final Font FONT_HEADER = new Font("Segoe UI", Font.BOLD, 12);
    private static final Font FONT_BODY = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font FONT_BUTTON = new Font("Segoe UI", Font.BOLD, 12);
    private static final Font FONT_LABEL = new Font("Segoe UI", Font.BOLD, 12);
    private static final Font FONT_STAT = new Font("Segoe UI", Font.BOLD, 26);
    private static final Font FONT_TABLE = new Font("Segoe UI", Font.PLAIN, 11);
    private static final Font FONT_LOG = new Font("Consolas", Font.PLAIN, 11);

    // Stat labels
    private JLabel statExperiments, statCompleted, statJsonValid, statTranscripts;

    // Controls
    private JComboBox<String> modelCombo;
    private JCheckBox zeroShot, fewShot, chainOfThought, structuredOutput;
    private JButton   runBtn, stopBtn, refreshBtn;

    // Progress
    private JProgressBar progressBar;
    private JLabel       progressLabel;
    private JLabel       connLabel;
    private JLabel       statusLabel;

    // Comparison table
    private DefaultTableModel comparisonTableModel;
    private JTable comparisonTable;
    private JButton viewDetailsBtn;

    // Export + execution-time controls
    private JComboBox<String> exportLayerCombo;
    private JButton exportBtn;
    private DefaultTableModel executionTableModel;
    private JTable executionTable;

    // Log area
    private JTextArea logArea;

    // Batch state
    private volatile boolean batchRunning = false;
    private int totalTranscriptCount = 0;

    // ----------------------------------------------------------------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new MasakGramClient().setVisible(true);
        });
    }

    // ----------------------------------------------------------------
    public MasakGramClient() {
        setTitle("MasakGramPrompt — Nutrition Comparison");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1400, 800);
        setMinimumSize(new Dimension(1200, 700));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_MAIN);
        setLayout(new BorderLayout(0, 0));

        add(buildTopBar(),    BorderLayout.NORTH);
        add(buildMainPanel(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        connectToServer();
    }

    // ================================================================
    // TOP BAR
    // ================================================================
    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BG_HEADER);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        bar.setPreferredSize(new Dimension(0, 44));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 10));
        left.setOpaque(false);
        JLabel title = new JLabel("  MasakGramPrompt — Nutrition Comparison");
        title.setFont(FONT_TITLE);
        title.setForeground(TEXT);
        left.add(title);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        right.setOpaque(false);
        connLabel = new JLabel("● Disconnected");
        connLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
        connLabel.setForeground(ACCENT_RED);
        right.add(connLabel);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // ================================================================
    // MAIN PANEL
    // ================================================================
    private JPanel buildMainPanel() {
        JPanel main = new JPanel(new BorderLayout(0, 8));
        main.setBackground(BG_MAIN);
        main.setBorder(new EmptyBorder(10, 14, 8, 14));
        main.add(buildStatCards(),  BorderLayout.NORTH);
        main.add(buildCenterRow(),  BorderLayout.CENTER);
        return main;
    }

    // ================================================================
    // STAT CARDS
    // ================================================================
    private JPanel buildStatCards() {
        JPanel row = new JPanel(new GridLayout(1, 4, 10, 0));
        row.setBackground(BG_MAIN);
        row.setPreferredSize(new Dimension(0, 70));

        statExperiments = new JLabel("—");
        statCompleted   = new JLabel("—");
        statJsonValid   = new JLabel("—");
        statTranscripts = new JLabel("—");

        row.add(makeStatCard(statExperiments, "TOTAL EXPERIMENTS"));
        row.add(makeStatCard(statCompleted,   "COMPLETED"));
        row.add(makeStatCard(statJsonValid,   "JSON VALID"));
        row.add(makeStatCard(statTranscripts, "TRANSCRIPTS"));
        return row;
    }

    private JPanel makeStatCard(JLabel val, String title) {
        JPanel card = new JPanel(new BorderLayout(0, 2));
        card.setBackground(BG_PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(8, 14, 8, 14)));
        val.setFont(FONT_STAT);
        val.setForeground(TEXT);
        JLabel lbl = new JLabel(title);
        lbl.setFont(FONT_LABEL);
        lbl.setForeground(TEXT_MUTED);
        card.add(val, BorderLayout.CENTER);
        card.add(lbl, BorderLayout.SOUTH);
        return card;
    }

    // ================================================================
    // CENTER ROW
    // ================================================================
    private JSplitPane buildCenterRow() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildControlPanel(), buildResultsPanel());
        split.setDividerLocation(350);
        split.setBackground(BG_MAIN);
        split.setBorder(null);
        split.setDividerSize(6);
        return split;
    }

    // ================================================================
    // LEFT — CONTROL PANEL
    // ================================================================
    private JPanel buildControlPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_PANEL);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(14, 16, 14, 16)));

        // ============================================================
        // 1. RUN EXPERIMENT
        // ============================================================
        JLabel titleLabel = new JLabel("RUN EXPERIMENT", SwingConstants.CENTER);
        titleLabel.setFont(FONT_HEADER);
        titleLabel.setForeground(TEXT_MUTED);
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(titleLabel);
        panel.add(Box.createVerticalStrut(8));

        JTextArea notice = new JTextArea(
            "Runs ALL transcripts × selected techniques.\n" +
            "Results displayed in comparison table.");
        notice.setEditable(false);
        notice.setWrapStyleWord(true);
        notice.setLineWrap(true);
        notice.setOpaque(true);
        notice.setBackground(new Color(245, 242, 240));
        notice.setForeground(TEXT);
        notice.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        notice.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, new Color(180, 170, 165)),
                new EmptyBorder(6, 10, 6, 10)));
        notice.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        notice.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(notice);

        panel.add(Box.createVerticalStrut(12));

        // ============================================================
        // 2. LLM MODEL
        // ============================================================
        JLabel modelLabel = new JLabel("LLM MODEL", SwingConstants.CENTER);
        modelLabel.setFont(FONT_LABEL);
        modelLabel.setForeground(TEXT_MUTED);
        modelLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(modelLabel);
        panel.add(Box.createVerticalStrut(5));

        modelCombo = new JComboBox<>(new String[]{
            "llama3.2:3b",
            "phi4-mini",
            "qwen2.5:3b",
            "aisingapore/Gemma-SEA-LION-v4-4B-VL",
            "medgemma:4b"
        });
        styleComboCentered(modelCombo);
        panel.add(modelCombo);

        panel.add(Box.createVerticalStrut(12));

        // ============================================================
        // 3. PROMPT TECHNIQUES
        // ============================================================
        JLabel techLabel = new JLabel("PROMPT TECHNIQUES", SwingConstants.CENTER);
        techLabel.setFont(FONT_LABEL);
        techLabel.setForeground(TEXT_MUTED);
        techLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(techLabel);
        panel.add(Box.createVerticalStrut(6));

        JPanel techGrid = new JPanel(new GridLayout(2, 2, 8, 8));
        techGrid.setOpaque(false);
        techGrid.setMaximumSize(new Dimension(300, 60));
        techGrid.setAlignmentX(Component.CENTER_ALIGNMENT);

        zeroShot         = makeTechCheck("zero-shot");
        fewShot          = makeTechCheck("few-shot");
        chainOfThought   = makeTechCheck("chain-of-thought");
        structuredOutput = makeTechCheck("structured-output");

        zeroShot.setSelected(true);
        fewShot.setSelected(true);
        chainOfThought.setSelected(true);
        structuredOutput.setSelected(true);

        techGrid.add(zeroShot);
        techGrid.add(fewShot);
        techGrid.add(chainOfThought);
        techGrid.add(structuredOutput);
        panel.add(techGrid);

        panel.add(Box.createVerticalStrut(12));

        // ============================================================
        // 4. BATCH PROGRESS
        // ============================================================
        JLabel progressTitle = new JLabel("BATCH PROGRESS", SwingConstants.CENTER);
        progressTitle.setFont(FONT_LABEL);
        progressTitle.setForeground(TEXT_MUTED);
        progressTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(progressTitle);
        panel.add(Box.createVerticalStrut(5));

        progressBar = new JProgressBar(0, 100);
        progressBar.setValue(0);
        progressBar.setStringPainted(true);
        progressBar.setString("0 / 0");
        progressBar.setBackground(new Color(235, 232, 228));
        progressBar.setForeground(new Color(160, 150, 145));
        progressBar.setFont(new Font("Segoe UI", Font.BOLD, 11));
        progressBar.setBorder(BorderFactory.createLineBorder(BORDER));
        progressBar.setMaximumSize(new Dimension(300, 22));
        progressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(progressBar);
        panel.add(Box.createVerticalStrut(4));

        progressLabel = new JLabel("Idle", SwingConstants.CENTER);
        progressLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        progressLabel.setForeground(TEXT_MUTED);
        progressLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(progressLabel);

        panel.add(Box.createVerticalStrut(12));

        // ============================================================
        // 5. BUTTONS
        // ============================================================
        JPanel btnRow = new JPanel(new GridLayout(1, 3, 10, 0));
        btnRow.setOpaque(false);
        btnRow.setMaximumSize(new Dimension(300, 36));
        btnRow.setAlignmentX(Component.CENTER_ALIGNMENT);

        runBtn     = makeButton("Run All");
        stopBtn    = makeButton("Stop");
        refreshBtn = makeButton("Refresh");

        stopBtn.setEnabled(false);
        runBtn.setEnabled(true);
        refreshBtn.setEnabled(true);

        runBtn.addActionListener(e -> startBatch());
        stopBtn.addActionListener(e -> requestStop());
        refreshBtn.addActionListener(e -> { loadStats(); loadComparisonData(); });

        btnRow.add(runBtn);
        btnRow.add(stopBtn);
        btnRow.add(refreshBtn);
        panel.add(btnRow);

        panel.add(Box.createVerticalStrut(12));

        // ============================================================
        // 6. EXPORT CSV
        // ============================================================
        JLabel exportLabel = new JLabel("EXPORT CSV", SwingConstants.CENTER);
        exportLabel.setFont(FONT_LABEL);
        exportLabel.setForeground(TEXT_MUTED);
        exportLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(exportLabel);
        panel.add(Box.createVerticalStrut(5));

        exportLayerCombo = new JComboBox<>(new String[]{
            "LAYER 1A", "LAYER 1B", "LAYER 2A", "LAYER 2B", "LAYER 2C",
            "LAYER 3A", "LAYER 3B", "LAYER 3C", "LAYER 4", "LAYER 5",
            "EXECUTION TIME"
        });
        styleComboCentered(exportLayerCombo);
        panel.add(exportLayerCombo);
        panel.add(Box.createVerticalStrut(8));

        JPanel exportBtnRow = new JPanel(new GridLayout(1, 2, 10, 0));
        exportBtnRow.setOpaque(false);
        exportBtnRow.setMaximumSize(new Dimension(300, 36));
        exportBtnRow.setAlignmentX(Component.CENTER_ALIGNMENT);

        exportBtn = makeButton("Export");
        JButton timeBtn = makeButton("Refresh Time");

        exportBtn.addActionListener(e -> exportSelectedCsv());
        timeBtn.addActionListener(e -> loadExecutionTimeSummary());

        exportBtnRow.add(exportBtn);
        exportBtnRow.add(timeBtn);
        panel.add(exportBtnRow);

        panel.add(Box.createVerticalStrut(12));

        // ============================================================
        // 7. RUN ORDER GUIDE
        // ============================================================
        JLabel guideLabel = new JLabel("RUN ORDER", SwingConstants.CENTER);
        guideLabel.setFont(FONT_LABEL);
        guideLabel.setForeground(TEXT_MUTED);
        guideLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(guideLabel);
        panel.add(Box.createVerticalStrut(6));

        String[][] stepsData = {
            {"1", "llama3.2:3b"},
            {"2", "phi4-mini"},
            {"3", "qwen2.5:3b"},
            {"4", "Gemma-SEA-LION"},
            {"5", "medgemma:4b"}
        };
        
        JPanel guidePanel = new JPanel(new GridLayout(stepsData.length, 2, 4, 2));
        guidePanel.setOpaque(false);
        guidePanel.setMaximumSize(new Dimension(280, 100));
        guidePanel.setAlignmentX(Component.CENTER_ALIGNMENT);

        for (String[] row : stepsData) {
            JLabel numLabel = new JLabel(row[0], SwingConstants.RIGHT);
            numLabel.setFont(new Font("Consolas", Font.PLAIN, 11));
            numLabel.setForeground(TEXT_MUTED);
            JLabel nameLabel = new JLabel(row[1]);
            nameLabel.setFont(new Font("Consolas", Font.PLAIN, 11));
            nameLabel.setForeground(TEXT_MUTED);
            guidePanel.add(numLabel);
            guidePanel.add(nameLabel);
        }
        
        panel.add(guidePanel);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    // ================================================================
    // RIGHT — COMPARISON TABLE
    // ================================================================
    private JPanel buildResultsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(BG_MAIN);
        panel.setBorder(new EmptyBorder(0, 0, 0, 0));

        // Table header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(BG_HEADER);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                new EmptyBorder(6, 12, 6, 12)));

        JLabel titleLabel = new JLabel("NUTRITION COMPARISON");
        titleLabel.setFont(FONT_HEADER);
        titleLabel.setForeground(TEXT_MUTED);
        headerPanel.add(titleLabel, BorderLayout.WEST);

        JLabel countLabel = new JLabel("Loading...");
        countLabel.setFont(FONT_BODY);
        countLabel.setForeground(TEXT_MUTED);
        countLabel.setName("countLabel");
        headerPanel.add(countLabel, BorderLayout.EAST);

        // Clear instruction for the user
        JPanel detailsActionPanel = new JPanel(
                new FlowLayout(FlowLayout.CENTER, 12, 0)
        );
        detailsActionPanel.setOpaque(false);

        JLabel instructionLabel = new JLabel(
                "Select an experiment row to view its full details"
        );
        instructionLabel.setFont(
                new Font("Segoe UI", Font.PLAIN, 11)
        );
        instructionLabel.setForeground(TEXT_MUTED);

        viewDetailsBtn = makeButton("View Details");
        viewDetailsBtn.setEnabled(false);
        viewDetailsBtn.setPreferredSize(
                new Dimension(115, 28)
        );

        viewDetailsBtn.setToolTipText(
                "Open the selected experiment details"
        );

        viewDetailsBtn.addActionListener(
                e -> openSelectedExperiment()
        );

        detailsActionPanel.add(instructionLabel);
        detailsActionPanel.add(viewDetailsBtn);

        headerPanel.add(
                detailsActionPanel,
                BorderLayout.CENTER
        );

        panel.add(headerPanel, BorderLayout.NORTH);

        // Comparison Table
        String[] cols = {
        	    "Experiment ID",
        	    "Reel ID",
        	    "Model",
        	    "Technique",
        	    "Nutrition Result",
        	    "Ground Truth",
        	    "Hallucination",
        	    "Status"
        	};
        
        comparisonTableModel = new DefaultTableModel(cols, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        
        comparisonTable = new JTable(comparisonTableModel);
        comparisonTable.removeColumn(comparisonTable.getColumnModel().getColumn(0));
        
        comparisonTable.setSelectionMode(
                ListSelectionModel.SINGLE_SELECTION
        );

        comparisonTable.setCursor(
                Cursor.getPredefinedCursor(
                        Cursor.HAND_CURSOR
                )
        );

        comparisonTable.setToolTipText(
                "Select a row and click View Details, "
                + "or double-click the row"
        );
        
        comparisonTable.getSelectionModel().addListSelectionListener(e -> {
        	if (e.getValueIsAdjusting()) {
                return;
            }

            boolean rowSelected =
                    comparisonTable.getSelectedRow() >= 0;

            viewDetailsBtn.setEnabled(rowSelected);

            if (rowSelected) {
                setStatus(
                    "Experiment selected — click View Details "
                    + "or double-click the row."
                );
            }
        });
        
        comparisonTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
            	if (e.getClickCount() == 2
            	        && comparisonTable.getSelectedRow() >= 0) {

            	    openSelectedExperiment();
            	}
            }
        });
        
        styleComparisonTable(comparisonTable);
        configureComparisonColumns(comparisonTable);

        JScrollPane tableScroll = new JScrollPane(comparisonTable);

        tableScroll.setBorder(
            BorderFactory.createLineBorder(BORDER)
        );

        tableScroll.getViewport().setBackground(BG_PANEL);
        tableScroll.setHorizontalScrollBarPolicy(
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );

        comparisonTable.setFillsViewportHeight(true);
        
        panel.add(tableScroll, BorderLayout.CENTER);

        // Bottom: Log + Execution Time
        JPanel bottomPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        bottomPanel.setBackground(BG_MAIN);
        bottomPanel.setPreferredSize(new Dimension(0, 140));

        // Log Panel
        JPanel logPanel = new JPanel(new BorderLayout(0, 4));
        logPanel.setBackground(BG_PANEL);
        logPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(8, 12, 8, 12)));

        JLabel logTitle = new JLabel("LOG", SwingConstants.CENTER);
        logTitle.setFont(FONT_HEADER);
        logTitle.setForeground(TEXT_MUTED);
        logPanel.add(logTitle, BorderLayout.NORTH);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setWrapStyleWord(true);
        logArea.setLineWrap(true);
        logArea.setBackground(new Color(250, 248, 245));
        logArea.setForeground(TEXT);
        logArea.setFont(FONT_LOG);
        logArea.setBorder(new EmptyBorder(4, 8, 4, 8));

        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createLineBorder(BORDER));
        logPanel.add(logScroll, BorderLayout.CENTER);
        bottomPanel.add(logPanel);

        // Execution Time Panel
        JPanel executionPanel = buildExecutionTimePanel();
        bottomPanel.add(executionPanel);

        panel.add(bottomPanel, BorderLayout.SOUTH);
        return panel;
    }
    
    private void openSelectedExperiment() {
        int selectedViewRow = comparisonTable.getSelectedRow();

        if (selectedViewRow < 0) {
            JOptionPane.showMessageDialog(
                this,
                "Please select an experiment.",
                "No Experiment Selected",
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        // Convert the visible row number to the table-model row number
        int modelRow =
            comparisonTable.convertRowIndexToModel(selectedViewRow);

        // Column 0 still contains Experiment ID in the model,
        // even though it is hidden from the visible JTable
        Object idValue =
            comparisonTableModel.getValueAt(modelRow, 0);

        if (idValue == null) {
            JOptionPane.showMessageDialog(
                this,
                "Experiment ID is unavailable.",
                "Experiment Error",
                JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        try {
            int experimentId =
                Integer.parseInt(idValue.toString());

            System.out.println(
                "Selected experiment ID: " + experimentId
            );

            // Later, this will load the metadata,
            // nutrition totals and ingredients.
            loadExperimentDetails(experimentId);

        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(
                this,
                "Invalid experiment ID: " + idValue,
                "Experiment Error",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }
    
    private void loadExperimentDetails(int experimentId) {
        setStatus("Loading experiment #" + experimentId + "...");

        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                String address =
                    "http://localhost:8080/api/experiment-detail?id="
                    + experimentId;

                URL url = URI.create(address).toURL();

                connection =
                    (HttpURLConnection) url.openConnection();

                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(30_000);

                int statusCode = connection.getResponseCode();

                InputStream stream = statusCode >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();

                String response;

                try (
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream)
                    )
                ) {
                    StringBuilder result = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        result.append(line);
                    }

                    response = result.toString();
                }

                if (statusCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException(
                        "Server returned HTTP "
                        + statusCode
                        + ": "
                        + response
                    );
                }

                JSONObject detail = new JSONObject(response);

                SwingUtilities.invokeLater(() -> {
                    showExperimentDetailDialog(detail);
                    setStatus("Loaded experiment #" + experimentId);
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    setStatus("Unable to load experiment details");

                    JOptionPane.showMessageDialog(
                        MasakGramClient.this,
                        "Could not load the experiment details.\n\n"
                        + e.getMessage(),
                        "Details Error",
                        JOptionPane.ERROR_MESSAGE
                    );
                });

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "load-experiment-detail").start();
    }
    
    private void showExperimentDetailDialog(JSONObject root) {

        // DatabaseManager returns one flat JSON object
        JSONObject metadata = root;
        JSONObject nutrition = root;

        JSONArray modelIngredients =
                root.optJSONArray("ingredients");

        JSONArray annotatorIngredients =
                root.optJSONArray(
                        "ground_truth_ingredients"
                );

        if (annotatorIngredients == null) {
        	annotatorIngredients = new JSONArray();
        }

        if (modelIngredients == null) {
            modelIngredients = new JSONArray();
        }

        JDialog dialog = new JDialog(
                this,
                "Experiment Details",
                true
        );

        dialog.setDefaultCloseOperation(
                JDialog.DISPOSE_ON_CLOSE
        );

        JPanel content = new JPanel();
        content.setLayout(
                new BoxLayout(content, BoxLayout.Y_AXIS)
        );
        content.setBackground(BG_MAIN);
        content.setBorder(
                new EmptyBorder(16, 16, 16, 16)
        );

        // 1. Metadata
        content.add(buildMetadataSection(metadata));
        content.add(Box.createVerticalStrut(12));

        // 2. Nutrition totals
        content.add(buildNutritionSection(nutrition));
        content.add(Box.createVerticalStrut(12));

        // 3. Hallucination summary
        content.add(buildHallucinationSummary(root));
        content.add(Box.createVerticalStrut(12));

        // 4. Model versus annotator comparison
        content.add(
                buildIngredientComparisonSection(
                        modelIngredients
                )
        );
        
        // 5. All ingredients entered by annotator
        if (annotatorIngredients.length() > 0) {
            content.add(Box.createVerticalStrut(12));

            content.add(
                buildAnnotatorIngredientsSection(
                		annotatorIngredients
                )
            );
        }

        JScrollPane pageScroll =
                new JScrollPane(content);

        pageScroll.setBorder(null);
        pageScroll.getVerticalScrollBar()
                  .setUnitIncrement(18);

        dialog.add(pageScroll);
        dialog.setSize(1400, 850);
        dialog.setMinimumSize(
                new Dimension(1050, 650)
        );
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
    private JPanel buildHallucinationSummary(JSONObject root) {

        JPanel outer = createSectionPanel(
                "3. INGREDIENT COMPARISON SUMMARY"
        );

        JPanel grid = new JPanel(
                new GridLayout(1, 5, 10, 0)
        );
        grid.setOpaque(false);

        int matched =
                root.optInt("matched_count", 0);

        int hallucinated =
                root.optInt(
                        "hallucinated_count",
                        0
                );

        int omitted =
                root.optInt("omitted_count", 0);

        boolean evaluated =
                root.optBoolean(
                        "hallucination_evaluated",
                        root.has("hallucination_rate")
                        && !root.isNull(
                                "hallucination_rate"
                        )
                );

        String rateText;
        String recallText;

        if (evaluated) {
            double rate =
                    root.optDouble(
                            "hallucination_rate",
                            0.0
                    );

            double recall =
                    root.optDouble(
                            "ingredient_recall",
                            0.0
                    );

            rateText = String.format("%.2f%%", rate);
            recallText = String.format(
                    "%.2f%%",
                    recall
            );

        } else {
            rateText = "N/A";
            recallText = "N/A";
        }

        grid.add(makeInfoCard(
                "MATCHED",
                String.valueOf(matched),
                ACCENT_GREEN
        ));

        grid.add(makeInfoCard(
                "HALLUCINATED",
                String.valueOf(hallucinated),
                hallucinated > 0
                        ? ACCENT_RED
                        : ACCENT_GREEN
        ));

        grid.add(makeInfoCard(
                "OMITTED",
                String.valueOf(omitted),
                omitted > 0
                        ? ACCENT_ORANGE
                        : ACCENT_GREEN
        ));

        grid.add(makeInfoCard(
                "HALLUCINATION RATE",
                rateText,
                !evaluated
                        ? TEXT_MUTED
                        : hallucinated > 0
                            ? ACCENT_RED
                            : ACCENT_GREEN
        ));

        grid.add(makeInfoCard(
                "INGREDIENT RECALL",
                recallText,
                TEXT
        ));

        outer.add(grid, BorderLayout.CENTER);

        return outer;
    }
    
    private JPanel buildIngredientComparisonSection(
            JSONArray ingredients) {

        JPanel outer = createSectionPanel(
                "4. MODEL VS ANNOTATOR INGREDIENTS"
        );

        String[] columns = {
        	    "Model Name (Original)",
        	    "Model Name (EN)",
        	    "Annotator Name (Original)",
        	    "Annotator Name (EN)",
        	    "Original Match",
        	    "English Match",
        	    "Hallucinated",
        	    "Translation"
        	};

        DefaultTableModel model =
                new DefaultTableModel(columns, 0) {

            @Override
            public boolean isCellEditable(
                    int row,
                    int column) {
                return false;
            }
        };

        for (int i = 0;
             i < ingredients.length();
             i++) {

            JSONObject ingredient =
                    ingredients.optJSONObject(i);

            if (ingredient == null) {
                continue;
            }
            
            model.addRow(new Object[]{
            	    ingredient.optString(
            	            "name_original",
            	            "—"
            	    ),

            	    ingredient.optString(
            	            "name_en",
            	            "—"
            	    ),

            	    ingredient.isNull(
            	            "matched_ground_truth_original"
            	    )
            	        ? "No match"
            	        : ingredient.optString(
            	                "matched_ground_truth_original",
            	                "No match"
            	        ),

            	    ingredient.isNull(
            	            "matched_ground_truth_en"
            	    )
            	        ? "No match"
            	        : ingredient.optString(
            	                "matched_ground_truth_en",
            	                "No match"
            	        ),

            	    String.format(
            	            "%.2f%%",
            	            ingredient.optDouble(
            	                    "original_match_score",
            	                    0.0
            	            )
            	    ),

            	    String.format(
            	            "%.2f%%",
            	            ingredient.optDouble(
            	                    "english_match_score",
            	                    0.0
            	            )
            	    ),

            	    ingredient.optBoolean(
            	            "hallucinated",
            	            false
            	    )
            	        ? "YES"
            	        : "NO",

            	    ingredient.optBoolean(
            	            "translation_correct",
            	            false
            	    )
            	        ? "CORRECT"
            	        : "MISMATCH"
            	});
        }

        JTable table = new JTable(model);

        table.setAutoResizeMode(
                JTable.AUTO_RESIZE_ALL_COLUMNS
        );
        
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setResizingAllowed(true);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(true);

        table.setFillsViewportHeight(true);
        table.setRowHeight(34);
        table.setFont(FONT_TABLE);
        table.setGridColor(BORDER);
        table.setSelectionBackground(
                new Color(220, 215, 210)
        );

        int[] preferredWidths = {
        	    190, // Model Original
        	    190, // Model EN
        	    210, // Annotator Original
        	    190, // Annotator EN
        	    105, // Original Match
        	    105, // English Match
        	    100, // Hallucinated
        	    110  // Translation
        	};

        	int[] minimumWidths = {
        	    120,
        	    120,
        	    130,
        	    120,
        	    90,
        	    90,
        	    85,
        	    95
        	};

        	for (int i = 0;
        	     i < table.getColumnModel().getColumnCount();
        	     i++) {

        	    TableColumn column =
        	        table.getColumnModel().getColumn(i);

        	    column.setPreferredWidth(
        	        preferredWidths[i]
        	    );

        	    column.setMinWidth(
        	        minimumWidths[i]
        	    );
        	}

        DefaultTableCellRenderer headerRenderer =
                (DefaultTableCellRenderer)
                table.getTableHeader()
                     .getDefaultRenderer();

        headerRenderer.setHorizontalAlignment(
                SwingConstants.CENTER
        );

        table.setDefaultRenderer(
                Object.class,
                new DefaultTableCellRenderer() {

            @Override
            public Component getTableCellRendererComponent(
                    JTable table,
                    Object value,
                    boolean isSelected,
                    boolean hasFocus,
                    int row,
                    int column) {

                super.getTableCellRendererComponent(
                        table,
                        value,
                        isSelected,
                        hasFocus,
                        row,
                        column
                );
                
                setFont(FONT_TABLE);

                int modelRow =
                        table.convertRowIndexToModel(
                                row
                        );

                String result =
                        String.valueOf(
                            table.getModel()
                                 .getValueAt(
                                    modelRow,
                                    6
                                 )
                        );

                boolean hallucinated =
                        result.contains("YES");

                setHorizontalAlignment(
                        SwingConstants.CENTER
                );

                if (!isSelected) {
                    if (hallucinated) {
                        setBackground(
                                new Color(
                                    255,
                                    225,
                                    225
                                )
                        );
                        setForeground(ACCENT_RED);

                    } else {
                        setBackground(
                                row % 2 == 0
                                    ? BG_PANEL
                                    : new Color(
                                        248,
                                        245,
                                        240
                                    )
                        );
                        setForeground(TEXT);
                    }
                }

                if (column == 6 || column == 7) {
                    setFont(
                        new Font(
                            "Segoe UI",
                            Font.BOLD,
                            11
                        )
                    );
                }

                return this;
            }
        });

        JScrollPane scroll = new JScrollPane(table);

        scroll.setPreferredSize(
                new Dimension(1200, 280)
        );
        
        scroll.setMaximumSize(
                new Dimension(
                        Integer.MAX_VALUE,
                        280
                )
        );

        scroll.setAlignmentX(
                Component.LEFT_ALIGNMENT
        );
        
        scroll.setBorder(
                BorderFactory.createLineBorder(BORDER)
        );

        scroll.getViewport().setBackground(BG_PANEL);

        scroll.setHorizontalScrollBarPolicy(
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );

        outer.add(scroll, BorderLayout.CENTER);

        return outer;
    }
    
    private JPanel buildMetadataSection(JSONObject metadata) {
        JPanel outer = createSectionPanel(
            "1. EXPERIMENT METADATA"
        );

        JPanel grid = new JPanel(
            new GridLayout(2, 4, 10, 10)
        );
        grid.setOpaque(false);

        grid.add(makeInfoCard(
            "MODEL",
            metadata.optString("model_name", "—"),
            null
        ));

        grid.add(makeInfoCard(
            "TECHNIQUE",
            metadata.optString("technique_name", "—"),
            null
        ));

        String status =
            metadata.optString("status", "unknown");

        Color statusColor =
            "completed".equalsIgnoreCase(status)
                ? ACCENT_GREEN
                : "failed".equalsIgnoreCase(status)
                    ? ACCENT_RED
                    : ACCENT_ORANGE;

        grid.add(makeInfoCard(
            "STATUS",
            status.toUpperCase(),
            statusColor
        ));

        grid.add(makeInfoCard(
            "TRANSCRIPT",
            metadata.optString("file_name", "—"),
            null
        ));

        grid.add(makeInfoCard(
            "RECIPE",
            metadata.optString(
                "recipe_name",
                "—"
            ),
            null
        ));

        grid.add(makeInfoCard(
            "SERVINGS",
            metadata.has("servings_estimated")
            && !metadata.isNull("servings_estimated")
                ? metadata.optString("servings_estimated")
                : "—",
            null
        ));

        boolean valid =
            metadata.optBoolean("json_valid", false);

        grid.add(makeInfoCard(
            "JSON VALID",
            valid ? "VALID" : "INVALID",
            valid ? ACCENT_GREEN : ACCENT_RED
        ));

        // Empty tile keeps the 4 × 2 grid balanced.
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        grid.add(spacer);

        outer.add(grid, BorderLayout.CENTER);

        return outer;
    }
    
    private JPanel createSectionPanel(String title) {
        JPanel panel = new JPanel(new BorderLayout(0, 10)) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getMaximumSize() {
                Dimension preferred = getPreferredSize();

                return new Dimension(
                        Integer.MAX_VALUE,
                        preferred.height
                );
            }
        };

        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBackground(BG_PANEL);

        panel.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(12, 12, 12, 12)
            )
        );

        JLabel heading = new JLabel(title);
        heading.setFont(FONT_HEADER);
        heading.setForeground(TEXT_MUTED);

        panel.add(heading, BorderLayout.NORTH);

        return panel;
    }

    private JPanel makeInfoCard(
            String title,
            String value,
            Color valueColor) {

        JPanel card = new JPanel(
            new BorderLayout(0, 5)
        );

        card.setBackground(
            new Color(250, 248, 245)
        );

        card.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(10, 12, 10, 12)
            )
        );

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(
            new Font("Segoe UI", Font.BOLD, 10)
        );
        titleLabel.setForeground(TEXT_MUTED);

        JLabel valueLabel = new JLabel(value);
        valueLabel.setFont(
            new Font("Segoe UI", Font.BOLD, 14)
        );
        valueLabel.setForeground(
            valueColor == null ? TEXT : valueColor
        );

        card.add(titleLabel, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);

        return card;
    }
    
    private JPanel buildNutritionSection(JSONObject nutrition) {

        JPanel outer = createSectionPanel(
            "2. RECIPE NUTRITION TOTALS"
        );

        JPanel grid = new JPanel(
            new GridLayout(2, 4, 10, 10)
        );
        grid.setOpaque(false);

        grid.add(makeMetricCard(
        	    "CALORIES",
        	    formatMetric(nutrition, "total_calories"),
        	    "kcal"
        	));

        	grid.add(makeMetricCard(
        	    "PROTEIN",
        	    formatMetric(nutrition, "total_protein_g"),
        	    "g"
        	));

        	grid.add(makeMetricCard(
        	    "TOTAL FAT",
        	    formatMetric(nutrition, "total_fat_g"),
        	    "g"
        	));

        	grid.add(makeMetricCard(
        	    "CARBOHYDRATES",
        	    formatMetric(nutrition, "total_carbohydrate_g"),
        	    "g"
        	));

        	grid.add(makeMetricCard(
        	    "SODIUM",
        	    formatMetric(nutrition, "total_sodium_mg"),
        	    "mg"
        	));

        	grid.add(makeMetricCard(
        	    "DIETARY FIBER",
        	    formatMetric(nutrition, "total_fiber_g"),
        	    "g"
        	));

        	grid.add(makeMetricCard(
        	    "TOTAL SUGARS",
        	    formatMetric(nutrition, "total_sugars_g"),
        	    "g"
        	));

        	grid.add(makeMetricCard(
        	    "CHOLESTEROL",
        	    formatMetric(nutrition, "total_cholesterol_mg"),
        	    "mg"
        	));

        outer.add(grid, BorderLayout.CENTER);

        return outer;
    }

    private JPanel makeMetricCard(
            String label,
            String value,
            String unit) {

        JPanel card = new JPanel(
            new BorderLayout(0, 4)
        );

        card.setBackground(
            new Color(250, 248, 245)
        );

        card.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(12, 12, 12, 12)
            )
        );

        JLabel nameLabel = new JLabel(
            label,
            SwingConstants.CENTER
        );

        nameLabel.setFont(
            new Font("Segoe UI", Font.BOLD, 10)
        );
        nameLabel.setForeground(TEXT_MUTED);

        JLabel valueLabel = new JLabel(
            value + " " + unit,
            SwingConstants.CENTER
        );

        valueLabel.setFont(
            new Font("Segoe UI", Font.BOLD, 20)
        );
        valueLabel.setForeground(TEXT);

        card.add(nameLabel, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);

        return card;
    }

    private String formatMetric(
            JSONObject object,
            String key) {

        if (!object.has(key) || object.isNull(key)) {
            return "—";
        }

        try {
            return String.format(
                "%.1f",
                object.getDouble(key)
            );
        } catch (Exception e) {
            return "—";
        }
    }
    
    private JPanel buildIngredientSection(
            JSONArray ingredients) {

        JPanel outer = createSectionPanel(
            "3. EXTRACTED INGREDIENTS"
        );

        String[] columns = {
            "Name (Original)",
            "Name (EN)",
            "Qty",
            "Unit (Original)",
            "Unit (EN)",
            "Weight (g)",
            "Calories",
            "Protein (g)",
            "Fat (g)",
            "Carbs (g)",
            "Hallucinated"
        };

        DefaultTableModel model =
            new DefaultTableModel(columns, 0) {

            @Override
            public boolean isCellEditable(
                    int row,
                    int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(
                    int columnIndex) {

                if (columnIndex == 10) {
                    return Boolean.class;
                }

                return Object.class;
            }
        };

        for (int i = 0; i < ingredients.length(); i++) {
            JSONObject ingredient =
                ingredients.optJSONObject(i);

            if (ingredient == null) {
                continue;
            }

            model.addRow(new Object[]{
                ingredient.optString("name_original", "—"),
                ingredient.optString("name_en", "—"),
                nullableValue(ingredient, "quantity_value"),
                ingredient.optString("unit_original", "—"),
                ingredient.optString("unit_en", "—"),
                nullableValue(ingredient, "estimated_weight_g"),
                nullableValue(ingredient, "calories"),
                nullableValue(ingredient, "protein_g"),
                nullableValue(ingredient, "total_fat_g"),
                nullableValue(ingredient, "total_carbohydrate_g"),
                ingredient.has("hallucinated")
                ? ingredient.optBoolean("hallucinated")
                : null
            });
        }

        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setResizingAllowed(true);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(true);
        
        table.setRowHeight(30);
        table.setFont(FONT_TABLE);
        table.setGridColor(BORDER);
        table.setSelectionBackground(
            new Color(220, 215, 210)
        );

        centerIngredientHeaders(table);
        configureIngredientColumns(table);
        applyIngredientRenderer(table);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(
            new Dimension(1200, 280)
        );
        scroll.setBorder(
            BorderFactory.createLineBorder(BORDER)
        );

        outer.add(scroll, BorderLayout.CENTER);

        return outer;
    }

    private Object nullableValue(
            JSONObject object,
            String key) {

        if (!object.has(key) || object.isNull(key)) {
            return "—";
        }

        return object.get(key);
    }
    
    private void configureIngredientColumns(
            JTable table) {

        int[] widths = {
            180, // Name Original
            180, // Name EN
            70,  // Qty
            120, // Unit Original
            90,  // Unit EN
            90,  // Weight
            90,  // Calories
            90,  // Protein
            80,  // Fat
            80,  // Carbs
            120  // Hallucinated
        };

        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel()
                 .getColumn(i)
                 .setPreferredWidth(widths[i]);
        }
    }

    private void centerIngredientHeaders(
            JTable table) {

        DefaultTableCellRenderer renderer =
            (DefaultTableCellRenderer)
            table.getTableHeader()
                 .getDefaultRenderer();

        renderer.setHorizontalAlignment(
            SwingConstants.CENTER
        );
    }
    
    private void applyIngredientRenderer(
            JTable table) {

        table.setDefaultRenderer(
            Object.class,
            new DefaultTableCellRenderer() {

                @Override
                public Component getTableCellRendererComponent(
                        JTable table,
                        Object value,
                        boolean isSelected,
                        boolean hasFocus,
                        int row,
                        int column) {

                    super.getTableCellRendererComponent(
                        table,
                        value,
                        isSelected,
                        hasFocus,
                        row,
                        column
                    );

                    int modelRow =
                        table.convertRowIndexToModel(row);

                    boolean hallucinated =
                        Boolean.TRUE.equals(
                            table.getModel().getValueAt(
                                modelRow,
                                10
                            )
                        );

                    setHorizontalAlignment(
                        SwingConstants.CENTER
                    );

                    if (!isSelected) {
                        if (hallucinated) {
                            setBackground(
                                new Color(255, 225, 225)
                            );
                            setForeground(ACCENT_RED);
                        } else {
                            setBackground(
                                row % 2 == 0
                                    ? BG_PANEL
                                    : new Color(
                                        248,
                                        245,
                                        240
                                    )
                            );
                            setForeground(TEXT);
                        }
                    }

                    if (column == 10) {
                        setText(
                            hallucinated
                                ? "YES"
                                : "NO"
                        );
                        setFont(
                            new Font(
                                "Segoe UI",
                                Font.BOLD,
                                11
                            )
                        );
                    }

                    return this;
                }
            }
        );

        table.setDefaultRenderer(
            Boolean.class,
            table.getDefaultRenderer(Object.class)
        );
    }

    // ================================================================
    // EXECUTION TIME PANEL
    // ================================================================
    private JPanel buildExecutionTimePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(BG_PANEL);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(8, 12, 8, 12)));

        JLabel title = new JLabel("EXECUTION TIME", SwingConstants.CENTER);
        title.setFont(FONT_HEADER);
        title.setForeground(TEXT_MUTED);
        panel.add(title, BorderLayout.NORTH);

        String[] cols = {"Model", "Technique", "Runs", "Avg(s)", "Min(s)", "Max(s)"};
        executionTableModel = new DefaultTableModel(cols, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        executionTable = new JTable(executionTableModel);
        styleTableSmall(executionTable);
        executionTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        JScrollPane sp = new JScrollPane(executionTable);
        sp.setBorder(BorderFactory.createLineBorder(BORDER));
        sp.getViewport().setBackground(BG_PANEL);
        panel.add(sp, BorderLayout.CENTER);
        return panel;
    }

    // ================================================================
    // EXPORT METHODS
    // ================================================================
    private void exportSelectedCsv() {
        String layer = (String) exportLayerCombo.getSelectedItem();
        if (layer == null || layer.isBlank()) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save CSV Export");
        chooser.setSelectedFile(new File(defaultExportFileName(layer)));
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "CSV files (*.csv)", "csv"));

        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            setStatus("CSV export cancelled");
            return;
        }

        File destination = chooser.getSelectedFile();
        if (!destination.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            destination = new File(destination.getAbsolutePath() + ".csv");
        }

        if (destination.exists()) {
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    "The file already exists. Replace it?\n" + destination.getAbsolutePath(),
                    "Replace CSV File",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                setStatus("CSV export cancelled");
                return;
            }
        }

        File outputFile = destination;
        exportBtn.setEnabled(false);
        setStatus("Exporting " + layer + "...");

        new Thread(() -> downloadCsv(layer, outputFile), "csv-export").start();
    }

    private void downloadCsv(String layer, File destination) {
        HttpURLConnection connection = null;
        File partialFile = new File(destination.getAbsolutePath() + ".part");

        try {
            java.nio.file.Files.deleteIfExists(partialFile.toPath());

            String endpoint = "http://localhost:8080/api/export?layer="
                    + URLEncoder.encode(layer, java.nio.charset.StandardCharsets.UTF_8);

            connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(120_000);
            connection.setRequestProperty("Accept", "text/csv");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                String responseBody = readHttpMessage(connection.getErrorStream());
                String message = extractServerError(responseBody);
                if (message.isBlank()) message = "HTTP " + status;
                throw new HttpExportException(status, message);
            }

            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(partialFile))) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }

            java.nio.file.Files.move(
                    partialFile.toPath(),
                    destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            SwingUtilities.invokeLater(() -> {
                exportBtn.setEnabled(true);
                setStatus("CSV saved: " + destination.getAbsolutePath());
                JOptionPane.showMessageDialog(
                        this,
                        "CSV exported successfully.\n\n" + destination.getAbsolutePath(),
                        "Export Complete",
                        JOptionPane.INFORMATION_MESSAGE);
            });

        } catch (Exception e) {
            try {
                java.nio.file.Files.deleteIfExists(partialFile.toPath());
            } catch (IOException ignored) {}

            final String userMessage;
            if (e instanceof HttpExportException) {
                HttpExportException httpError = (HttpExportException) e;
                userMessage = "The server is running, but CSV generation failed."
                        + "\n\nHTTP " + httpError.statusCode + ": " + httpError.getMessage()
                        + "\n\nCheck the MasakGramServer console for the SQL/database error.";
            } else if (e instanceof ConnectException) {
                userMessage = "Cannot connect to MasakGramServer on port 8080."
                        + "\n\nStart MasakGramServer.java and try again.";
            } else if (e instanceof SocketTimeoutException) {
                userMessage = "The CSV request timed out."
                        + "\n\nCheck whether MySQL and MasakGramServer are responding.";
            } else {
                userMessage = "Export failed: " + e.getMessage();
            }

            SwingUtilities.invokeLater(() -> {
                exportBtn.setEnabled(true);
                setStatus("CSV export failed");
                JOptionPane.showMessageDialog(
                        this,
                        userMessage,
                        "Export Error",
                        JOptionPane.ERROR_MESSAGE);
            });
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readHttpMessage(InputStream stream) throws IOException {
        if (stream == null) return "";

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                stream, java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (text.length() > 0) text.append(' ');
                text.append(line);
            }
            return text.toString();
        }
    }

    private String extractServerError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "";

        String error = val(responseBody, "error");
        return "—".equals(error) ? responseBody : error;
    }

    private static final class HttpExportException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int statusCode;

        private HttpExportException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    private String defaultExportFileName(String layer) {
        switch (layer.toUpperCase(Locale.ROOT)) {
            case "LAYER 1A": return "layer1a_exact_match.csv";
            case "LAYER 1B": return "layer1b_text_similarity.csv";
            case "LAYER 2A": return "layer2a_numeric_quantity.csv";
            case "LAYER 2B": return "layer2b_numeric_nutrition.csv";
            case "LAYER 2C": return "layer2c_nutrition_totals.csv";
            case "LAYER 3A": return "layer3a_json_validity.csv";
            case "LAYER 3B": return "layer3b_hallucination.csv";
            case "LAYER 3C": return "layer3c_ingredient_detection.csv";
            case "LAYER 4": return "layer4_human_evaluation.csv";
            case "LAYER 5": return "layer5_condition_scores.csv";
            case "EXECUTION TIME": return "execution_time_summary.csv";
            default:
                return layer.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "_") + ".csv";
        }
    }

    private void loadExecutionTimeSummary() {
        new Thread(() -> {
            try {
            	URL url = URI.create("http://localhost:8080/api/execution-summary").toURL();
                BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                parseAndShowExecutionTime(sb.toString());
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> setStatus("Execution time summary unavailable: " + e.getMessage()));
            }
        }, "load-execution-time").start();
    }

    private void parseAndShowExecutionTime(String json) {
        SwingUtilities.invokeLater(() -> {
            executionTableModel.setRowCount(0);
            String[] objs = json.split("\\{");
            for (String obj : objs) {
                if (!obj.contains("model_name")) continue;
                executionTableModel.addRow(new Object[]{
                    shorten(val(obj, "model_name"), 14),
                    shorten(val(obj, "technique_name"), 12),
                    val(obj, "runs"),
                    fmtNum(val(obj, "avg_seconds")),
                    fmtNum(val(obj, "min_seconds")),
                    fmtNum(val(obj, "max_seconds"))
                });
            }
        });
    }

    // ================================================================
    // STATUS BAR
    // ================================================================
    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BG_HEADER);
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER));
        bar.setPreferredSize(new Dimension(0, 26));
        statusLabel = new JLabel("  Ready");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        statusLabel.setForeground(TEXT_MUTED);
        JLabel hint = new JLabel("Connected to MasakGram Server");
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        hint.setForeground(TEXT_MUTED);
        bar.add(statusLabel, BorderLayout.WEST);
        bar.add(hint,        BorderLayout.EAST);
        return bar;
    }

    // ================================================================
    // TCP CONNECTION
    // ================================================================
    private void connectToServer() {
        new Thread(() -> {
            try {
                setStatus("Connecting to server…");

                socket = new Socket(SERVER_HOST, SERVER_PORT);
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream())
                );

                // Only this listener reads messages from the TCP stream.
                // The batch loop now runs inside the server.
                startServerMessageListener();

                SwingUtilities.invokeLater(() -> {
                    connLabel.setText("● Connected");
                    connLabel.setForeground(ACCENT_GREEN);
                    setStatus("Connected to MasakGramServer (port " + SERVER_PORT + ")");
                });

                loadStats();
                loadComparisonData();

            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    connLabel.setText("● Disconnected");
                    connLabel.setForeground(ACCENT_RED);
                    setStatus("Cannot connect — start MasakGramServer first.");
                    JOptionPane.showMessageDialog(
                            this,
                            "Cannot connect to MasakGramServer on port "
                                    + SERVER_PORT
                                    + ".\n\nPlease start MasakGramServer first.",
                            "Connection Failed",
                            JOptionPane.WARNING_MESSAGE
                    );
                });
            }
        }, "tcp-connect").start();
    }

    private void startServerMessageListener() {
        Thread listener = new Thread(() -> {
            try {
                String message;

                while ((message = in.readLine()) != null) {
                    handleServerMessage(message);
                }

                throw new EOFException("Server closed the connection");

            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    batchRunning = false;
                    runBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                    modelCombo.setEnabled(true);
                    connLabel.setText("● Disconnected");
                    connLabel.setForeground(ACCENT_RED);
                    setStatus("Server connection lost: " + e.getMessage());
                });
            }
        }, "server-message-listener");

        listener.setDaemon(true);
        listener.start();
    }

    private void handleServerMessage(String message) {
        String[] parts = message.split("\\|", -1);
        String type = parts.length > 0 ? parts[0] : "";

        switch (type) {
            case "BATCH_STARTED": {
                int total = parseIntSafe(parts, 1, 0);
                int transcriptCount = parseIntSafe(parts, 2, 0);
                int techniqueCount = parseIntSafe(parts, 3, 0);

                SwingUtilities.invokeLater(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setMinimum(0);
                    progressBar.setMaximum(Math.max(total, 1));
                    progressBar.setValue(0);
                    progressBar.setString("0 / " + total);
                    progressLabel.setText("Server batch started");
                    setStatus("Server loaded " + transcriptCount
                            + " transcripts and " + techniqueCount + " techniques.");
                    log("=== SERVER BATCH STARTED ===");
                    log("Transcripts : " + transcriptCount);
                    log("Techniques  : " + techniqueCount);
                    log("Total runs  : " + total);
                });
                break;
            }

            case "BATCH_PROGRESS": {
                int done = parseIntSafe(parts, 1, 0);
                int total = parseIntSafe(parts, 2, 0);
                String transcriptId = part(parts, 3);
                String technique = part(parts, 4);
                String runStatus = part(parts, 5);
                String errorMessage = part(parts, 6);

                SwingUtilities.invokeLater(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setMaximum(Math.max(total, 1));
                    progressBar.setValue(Math.min(done, Math.max(total, 1)));
                    progressBar.setString(done + " / " + total);
                    progressLabel.setText(
                            "Transcript #" + transcriptId + " • "
                                    + technique + " • " + runStatus
                    );
                    setStatus(
                            "Server processing transcript #" + transcriptId
                                    + " | " + technique + " | " + runStatus
                    );

                    if ("COMPLETED".equalsIgnoreCase(runStatus)) {
                        log("[OK]   #" + transcriptId + " | " + technique);
                    } else if ("FAILED".equalsIgnoreCase(runStatus)) {
                        log("[FAIL] #" + transcriptId + " | " + technique
                                + (errorMessage.isBlank() ? "" : " — " + errorMessage));
                    }
                });

                if (done > 0 && done % 10 == 0
                        && !"RUNNING".equalsIgnoreCase(runStatus)) {
                    loadStats();
                    loadComparisonData();
                }
                break;
            }

            case "BATCH_FINISHED": {
                int successful = parseIntSafe(parts, 1, 0);
                int failed = parseIntSafe(parts, 2, 0);
                int completed = parseIntSafe(parts, 3, 0);
                int total = parseIntSafe(parts, 4, 0);

                SwingUtilities.invokeLater(() -> {
                    batchRunning = false;
                    runBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                    modelCombo.setEnabled(true);
                    progressBar.setIndeterminate(false);
                    progressBar.setMaximum(Math.max(total, 1));
                    progressBar.setValue(Math.min(completed, Math.max(total, 1)));
                    progressBar.setString(completed + " / " + total);
                    progressLabel.setText(
                            "Finished — " + successful + " ok, " + failed + " failed"
                    );
                    setStatus("Server batch complete. " + successful
                            + " saved, " + failed + " failed.");
                    log("=== SERVER BATCH FINISHED ===");
                    log("Completed : " + successful);
                    log("Failed    : " + failed);
                    log("Total done: " + completed + " / " + total);
                    loadStats();
                    loadExecutionTimeSummary();
                    loadComparisonData();
                    JOptionPane.showMessageDialog(
                            MasakGramClient.this,
                            "Server batch complete!\n\nSaved: " + successful
                                    + "\nFailed: " + failed,
                            "Batch Finished",
                            JOptionPane.INFORMATION_MESSAGE
                    );
                });
                break;
            }

            case "BATCH_STOPPED": {
                int completed = parseIntSafe(parts, 1, 0);
                int total = parseIntSafe(parts, 2, 0);
                int successful = parseIntSafe(parts, 3, 0);
                int failed = parseIntSafe(parts, 4, 0);

                SwingUtilities.invokeLater(() -> {
                    batchRunning = false;
                    runBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                    modelCombo.setEnabled(true);
                    progressBar.setIndeterminate(false);
                    progressBar.setMaximum(Math.max(total, 1));
                    progressBar.setValue(Math.min(completed, Math.max(total, 1)));
                    progressBar.setString(completed + " / " + total);
                    progressLabel.setText("Batch stopped by user");
                    setStatus("Server batch stopped after " + completed + " runs.");
                    log("=== SERVER BATCH STOPPED ===");
                    log("Successful: " + successful + " | Failed: " + failed);
                    loadStats();
                    loadComparisonData();
                });
                break;
            }

            case "STOP_REQUESTED":
                log("Server accepted the stop request.");
                break;

            case "ERROR":
                SwingUtilities.invokeLater(() -> {
                    batchRunning = false;
                    runBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                    modelCombo.setEnabled(true);
                    progressBar.setIndeterminate(false);
                    String error = parts.length > 1 ? parts[1] : message;
                    log("[SERVER ERROR] " + error);
                    setStatus("Server error: " + error);
                });
                break;

            default:
                // Retains compatibility with the older single RUN command.
                log(message);
                break;
        }
    }

    private int parseIntSafe(String[] parts, int index, int fallback) {
        try {
            return Integer.parseInt(part(parts, index));
        } catch (Exception e) {
            return fallback;
        }
    }

    private String part(String[] parts, int index) {
        return index >= 0 && index < parts.length ? parts[index] : "";
    }

    // ================================================================
    // LOAD STATS
    // ================================================================
    private void loadStats() {
        new Thread(() -> {
            try {
            	URL url = URI.create("http://localhost:8080/api/stats").toURL();
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(url.openStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                String json = sb.toString();
                SwingUtilities.invokeLater(() -> {
                    statExperiments.setText(val(json, "experiments"));
                    statCompleted.setText(  val(json, "completed"));
                    statJsonValid.setText(  val(json, "json_valid"));
                    statTranscripts.setText(val(json, "transcripts"));
                    try { totalTranscriptCount = Integer.parseInt(val(json, "transcripts")); }
                    catch (Exception ignored) {}
                    loadExecutionTimeSummary();
                });
            } catch (Exception ignored) {}
        }, "load-stats").start();
    }

    // ================================================================
    // LOAD COMPARISON DATA
    // ================================================================
    private void loadComparisonData() {
        new Thread(() -> {
            try {
            	URL url = URI.create("http://localhost:8080/api/recent-experiments").toURL();
                BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                parseComparisonData(sb.toString());
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    setStatus("Error loading data: " + e.getMessage());
                    updateCountLabel("Error");
                });
            }
        }, "load-comparison").start();
    }

    private String formatNutritionDisplay(
            Map<String, Double> values) {

        if (values == null || values.isEmpty()) {
            return "—";
        }

        return "<html>"
            + "Calories: "
            + formatNutritionValue(
                values,
                "calories"
            )
            + " kcal<br>"

            + "Protein: "
            + formatNutritionValue(
                values,
                "protein_g"
            )
            + " g<br>"

            + "Carbs: "
            + formatNutritionValue(
                values,
                "total_carbohydrate_g"
            )
            + " g<br>"

            + "Fat: "
            + formatNutritionValue(
                values,
                "total_fat_g"
            )
            + " g"
            + "</html>";
    }

    private String formatNutritionValue(
            Map<String, Double> values,
            String key) {

        Double value = values.get(key);

        if (value == null) {
            return "—";
        }

        return String.format("%.1f", value);
    }

    private void parseComparisonData(String json) {
        try {
            JSONArray experiments = new JSONArray(json);

            SwingUtilities.invokeLater(() -> {
                comparisonTableModel.setRowCount(0);

                int rowCount = 0;

                for (int i = 0;
                     i < experiments.length();
                     i++) {

                    JSONObject obj =
                        experiments.optJSONObject(i);

                    if (obj == null) {
                        continue;
                    }

                    String experimentId =
                        obj.optString(
                            "experiment_id",
                            "—"
                        );

                    String reelId =
                        obj.optString(
                            "reel_id_instagram",
                            "—"
                        );

                    if (reelId.isBlank()
                            || "—".equals(reelId)) {

                        reelId =
                            "R"
                            + obj.optInt(
                                "transcript_id",
                                0
                            );
                    }

                    String modelId =
                        obj.optString(
                            "model_tag",
                            "—"
                        );

                    String techniqueId =
                        obj.optString(
                            "technique_name",
                            "—"
                        );

                    String status =
                        obj.optString(
                            "status",
                            "unknown"
                        );

                    boolean failed =
                        "failed".equalsIgnoreCase(
                            status
                        );

                    Map<String, Double> predicted =
                        new HashMap<>();

                    putNutritionValue(
                        predicted,
                        "calories",
                        obj,
                        "model_total_calories"
                    );

                    putNutritionValue(
                        predicted,
                        "protein_g",
                        obj,
                        "model_total_protein_g"
                    );

                    putNutritionValue(
                        predicted,
                        "total_fat_g",
                        obj,
                        "model_total_fat_g"
                    );

                    putNutritionValue(
                        predicted,
                        "total_carbohydrate_g",
                        obj,
                        "model_total_carbohydrate_g"
                    );

                    Map<String, Double> groundTruth =
                        new HashMap<>();

                    putNutritionValue(
                        groundTruth,
                        "calories",
                        obj,
                        "gt_total_calories"
                    );

                    putNutritionValue(
                        groundTruth,
                        "protein_g",
                        obj,
                        "gt_total_protein_g"
                    );

                    putNutritionValue(
                        groundTruth,
                        "total_fat_g",
                        obj,
                        "gt_total_fat_g"
                    );

                    putNutritionValue(
                        groundTruth,
                        "total_carbohydrate_g",
                        obj,
                        "gt_total_carbohydrate_g"
                    );

                    String nutritionDisplay;

                    if (failed) {
                        nutritionDisplay = "—";
                    } else {
                        nutritionDisplay =
                            formatNutritionDisplay(
                                predicted
                            );
                    }

                    String groundTruthDisplay =
                        groundTruth.isEmpty()
                            ? "—"
                            : formatNutritionDisplay(
                                groundTruth
                            );

                    String hallucinationDisplay;

                    if (failed) {
                        hallucinationDisplay = "N/A";

                    } else if (
                        !obj.has(
                            "ingredient_hallucination_rate"
                        )
                        || obj.isNull(
                            "ingredient_hallucination_rate"
                        )
                    ) {
                        hallucinationDisplay = "N/A";

                    } else {
                        hallucinationDisplay =
                            String.format(
                                "%.1f%%",
                                obj.optDouble(
                                    "ingredient_hallucination_rate",
                                    0.0
                                )
                            );
                    }

                    String statusDisplay;

                    if ("completed".equalsIgnoreCase(status)) {
                        statusDisplay = "COMPLETED";

                    } else if (
                        "failed".equalsIgnoreCase(status)
                    ) {
                        statusDisplay = "FAILED";

                    } else if (
                        "running".equalsIgnoreCase(status)
                    ) {
                        statusDisplay = "RUNNING";

                    } else {
                        statusDisplay =
                            status.toUpperCase(
                                Locale.ROOT
                            );
                    }

                    comparisonTableModel.addRow(
                        new Object[]{
                            experimentId,
                            reelId,
                            modelId,
                            techniqueId,
                            nutritionDisplay,
                            groundTruthDisplay,
                            hallucinationDisplay,
                            statusDisplay
                        }
                    );

                    rowCount++;
                }

                updateCountLabel(
                    rowCount + " records"
                );

                setStatus(
                    "Loaded "
                    + rowCount
                    + " experiments"
                );
            });

        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> {
                setStatus(
                    "Invalid dashboard data: "
                    + e.getMessage()
                );
                updateCountLabel("Error");
            });
        }
    }
    
    private void putNutritionValue(
            Map<String, Double> target,
            String targetKey,
            JSONObject source,
            String sourceKey) {

        if (!source.has(sourceKey)
                || source.isNull(sourceKey)) {
            return;
        }

        try {
            target.put(
                targetKey,
                source.getDouble(sourceKey)
            );
        } catch (Exception ignored) {
            // Do not convert missing values to zero.
        }
    }

    private void updateCountLabel(String text) {
        Component[] comps = comparisonTable.getParent().getParent().getComponents();
        for (Component comp : comps) {
            if (comp instanceof JPanel) {
                for (Component child : ((JPanel) comp).getComponents()) {
                    if (child instanceof JLabel && ((JLabel) child).getName() != null &&
                        ((JLabel) child).getName().equals("countLabel")) {
                        ((JLabel) child).setText(text);
                        return;
                    }
                }
            }
        }
    }

    // ================================================================
    // START BATCH
    // ================================================================
    private void startBatch() {
        if (socket == null || socket.isClosed() || out == null) {
            JOptionPane.showMessageDialog(
                    this,
                    "Not connected. Start MasakGramServer first.",
                    "Not Connected",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        String model = (String) modelCombo.getSelectedItem();
        java.util.List<String> techniques = new java.util.ArrayList<>();

        if (zeroShot.isSelected()) {
            techniques.add("zero-shot");
        }
        if (fewShot.isSelected()) {
            techniques.add("few-shot");
        }
        if (chainOfThought.isSelected()) {
            techniques.add("chain-of-thought");
        }
        if (structuredOutput.isSelected()) {
            techniques.add("structured-output");
        }

        if (techniques.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Select at least one technique.",
                    "No Technique",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Start a server-side batch?\n\n"
                        + "Model: " + model + "\n"
                        + "Techniques: " + String.join(", ", techniques) + "\n\n"
                        + "The server will retrieve every transcript from MySQL, "
                        + "process it, and save the results.",
                "Confirm Server Batch",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        batchRunning = true;
        runBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        modelCombo.setEnabled(false);
        progressBar.setIndeterminate(true);
        progressBar.setString("Starting server batch...");
        progressLabel.setText("Waiting for server...");
        logArea.setText("");
        log("Sending one RUN_BATCH request to the server...");

        out.println(
                "RUN_BATCH|" + model + "|" + String.join(",", techniques)
        );
        out.flush();
    }

    private void requestStop() {
        if (!batchRunning || out == null) {
            return;
        }

        out.println("STOP_BATCH");
        out.flush();
        stopBtn.setEnabled(false);
        progressLabel.setText("Stop requested...");
        setStatus("Waiting for the server to stop after the current run...");
        log("Stop request sent to the server.");
    }

    // ================================================================
    // HELPERS
    // ================================================================
    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void setStatus(String msg) {
        SwingUtilities.invokeLater(() -> statusLabel.setText("  " + msg));
    }

    private String val(String json, String key) {
        String pat = "\"" + key + "\"";
        int idx = json.indexOf(pat);
        if (idx < 0) return "—";
        int colon = json.indexOf(":", idx);
        if (colon < 0) return "—";
        String rest = json.substring(colon + 1).trim();
        if (rest.startsWith("\"")) {
            int end = rest.indexOf("\"", 1);
            return end > 0 ? rest.substring(1, end) : "—";
        }
        int end = rest.indexOf(",");
        if (end < 0) end = rest.indexOf("}");
        if (end < 0) end = rest.length();
        String v = rest.substring(0, end).trim();
        return v.isEmpty() ? "—" : v;
    }

    private String fmtNum(String s) {
        if (s == null || s.equals("—") || s.isBlank()) return "—";
        try { return String.format("%.1f", Double.parseDouble(s)); }
        catch (Exception e) { return s; }
    }

    private String shorten(String s, int max) {
        if (s == null || s.equals("—")) return "—";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ================================================================
    // STYLING METHODS (Simple & Clean)
    // ================================================================

    private void styleComboCentered(JComboBox<String> combo) {
        combo.setBackground(BG_PANEL);
        combo.setForeground(TEXT);
        combo.setFont(FONT_BODY);
        combo.setBorder(BorderFactory.createLineBorder(BORDER));
        combo.setMaximumSize(new Dimension(300, 30));
        combo.setAlignmentX(Component.CENTER_ALIGNMENT);
    }

    private JCheckBox makeTechCheck(String label) {
        JCheckBox cb = new JCheckBox(label);
        cb.setOpaque(true);
        cb.setBackground(BG_PANEL);
        cb.setForeground(TEXT);
        cb.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        cb.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(4, 8, 4, 8)));
        cb.setFocusPainted(false);
        return cb;
    }

    private JButton makeButton(String text) {
        JButton btn = new JButton(text);
        btn.setBackground(new Color(220, 215, 210));
        btn.setForeground(TEXT);
        btn.setFont(FONT_BUTTON);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(6, 12, 6, 12)));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setOpaque(true);
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { 
                if (btn.isEnabled()) btn.setBackground(new Color(200, 195, 190)); 
            }
            public void mouseExited(MouseEvent e) { 
                btn.setBackground(new Color(220, 215, 210)); 
            }
        });
        btn.addPropertyChangeListener("enabled", evt -> {
            boolean enabled = (boolean) evt.getNewValue();
            btn.setBackground(enabled ? new Color(220, 215, 210) : new Color(235, 232, 230));
        });
        return btn;
    }

    // ================================================================
    // COMPARISON TABLE STYLING
    // ================================================================
    private void styleComparisonTable(JTable table) {
        table.setBackground(BG_PANEL);
        table.setForeground(TEXT);
        table.setGridColor(BORDER);
        table.setFont(FONT_TABLE);
        table.setRowHeight(95);
        table.setSelectionBackground(new Color(220, 215, 210));
        table.setSelectionForeground(TEXT);
        table.setShowGrid(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setResizingAllowed(true);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(true);
        
        table.setFillsViewportHeight(true);

        JTableHeader h = table.getTableHeader();
        h.setBackground(BG_HEADER);
        h.setForeground(TEXT);
        h.setFont(new Font("Segoe UI", Font.BOLD, 11));
        h.setBorder(BorderFactory.createMatteBorder(0, 0, 2, 0, BORDER));
        
        DefaultTableCellRenderer headerRenderer =
                (DefaultTableCellRenderer)
                h.getDefaultRenderer();

        headerRenderer.setHorizontalAlignment(
                SwingConstants.CENTER
        );

        h.setReorderingAllowed(false);

        // Custom renderer for nutrition columns
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                
                if (!isSelected) {
                    c.setBackground(row % 2 == 0 ? BG_PANEL : new Color(248, 245, 240));
                }
                
                setForeground(TEXT);
                setBorder(new EmptyBorder(4, 8, 4, 8));
                
                // Check if this is a failed experiment (Status column index 6)
                boolean isFailed = false;
                Object statusObj = table.getValueAt(row, 6);
                if (statusObj instanceof String) {
                    isFailed = "FAILED".equalsIgnoreCase((String) statusObj);
                }
                
                // Nutrition Result column (index 3) - grey out for failed
                if (column == 3 && isFailed) {
                    setForeground(TEXT_GHOST);
                    setBackground(BG_GHOST);
                }
                
                // Ground Truth column (index 4)
                if (column == 4 && value != null && value.toString().equals("—")) {
                    setForeground(TEXT_GHOST);
                }
                
                // Hallucination column (index 5)
                if (column == 5) {
                    if (isFailed || value == null || value.toString().equals("N/A")) {
                        setForeground(TEXT_GHOST);
                        setBackground(BG_GHOST);
                    } else {
                        String val = value.toString();
                        try {
                            double score = Double.parseDouble(val.replace("%", ""));
                            if (score > 75) {
                                setBackground(new Color(235, 200, 200));
                                setForeground(ACCENT_RED);
                            } else if (score > 25) {
                                setBackground(new Color(240, 225, 190));
                                setForeground(ACCENT_ORANGE);
                            } else {
                                setBackground(new Color(200, 235, 210));
                                setForeground(ACCENT_GREEN);
                            }
                            if (isSelected) {
                                setBackground(table.getSelectionBackground());
                                setForeground(table.getSelectionForeground());
                            }
                        } catch (NumberFormatException e) {
                            // Use default
                        }
                    }
                }
                
                // Status column (index 6)
                if (column == 6 && value instanceof String) {
                    String val = (String) value;
                    if ("COMPLETED".equalsIgnoreCase(val)) {
                        setForeground(ACCENT_GREEN);
                    } else if ("FAILED".equalsIgnoreCase(val)) {
                        setForeground(ACCENT_RED);
                    } else if ("RUNNING".equalsIgnoreCase(val)) {
                        setForeground(ACCENT_ORANGE);
                    }
                    if (isSelected) {
                        setForeground(table.getSelectionForeground());
                    }
                }
                
                // Center align all columns except nutrition
                setHorizontalAlignment(SwingConstants.CENTER);
                
                // Allow HTML rendering for nutrition columns
                if ((column == 3 || column == 4) && value instanceof String) {
                    setText((String) value);
                }
                
                return c;
            }
        });
    }

    private void configureComparisonColumns(JTable table) {
        TableColumnModel columns = table.getColumnModel();

        int[] preferredWidths = {
            90,   // Reel ID
            130,  // Model
            130,  // Technique
            280,  // Nutrition Result
            280,  // Ground Truth
            130,  // Hallucination
            110   // Status
        };

        int[] minimumWidths = {
            70,
            90,
            100,
            180,
            180,
            100,
            90
        };

        for (int i = 0;
             i < columns.getColumnCount()
             && i < preferredWidths.length;
             i++) {

            TableColumn column = columns.getColumn(i);

            column.setPreferredWidth(preferredWidths[i]);
            column.setMinWidth(minimumWidths[i]);
        }

        table.getTableHeader().setReorderingAllowed(false);
    }

    private void styleTableSmall(JTable table) {
        table.setBackground(BG_PANEL);
        table.setForeground(TEXT);
        table.setGridColor(BORDER);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        table.setRowHeight(24);
        table.setSelectionBackground(new Color(220, 215, 210));
        table.setSelectionForeground(TEXT);
        table.setShowGrid(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setResizingAllowed(true);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(true);
        
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected) {
                    c.setBackground(row % 2 == 0 ? BG_PANEL : new Color(248, 245, 240));
                }
                return c;
            }
        });
        
        JTableHeader h = table.getTableHeader();
        h.setBackground(BG_HEADER);
        h.setForeground(TEXT);
        h.setFont(new Font("Segoe UI", Font.BOLD, 10));
        h.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
    }
    
    private JPanel buildAnnotatorIngredientsSection(JSONArray ingredients) {

        JPanel outer = createSectionPanel(
        		"5. ALL ORIGINAL INGREDIENTS (GROUND TRUTH)"
        );

        String[] columns = {
            "Name (Original)",
            "Name (EN)",
            "Quantity",
            "Unit"
        };

        DefaultTableModel model =
                new DefaultTableModel(columns, 0) {

            @Override
            public boolean isCellEditable(
                    int row,
                    int column) {
                return false;
            }
        };

        for (int i = 0;
             i < ingredients.length();
             i++) {

            JSONObject ingredient =
                    ingredients.optJSONObject(i);

            if (ingredient == null) {
                continue;
            }

            model.addRow(new Object[]{
                ingredient.optString(
                        "name_original",
                        "—"
                ),

                ingredient.optString(
                        "name_en",
                        "—"
                ),

                nullableJsonValue(
                        ingredient,
                        "quantity_value"
                ),

                ingredient.optString(
                        "unit",
                        "—"
                )
            });
        }

        JTable table = new JTable(model);
        table.setRowHeight(30);
        table.setFont(FONT_TABLE);
        table.setGridColor(BORDER);
        table.setAutoResizeMode(
                JTable.AUTO_RESIZE_ALL_COLUMNS
        );
        
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setResizingAllowed(true);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(true);

        DefaultTableCellRenderer renderer =
                new DefaultTableCellRenderer();

        renderer.setHorizontalAlignment(
                SwingConstants.CENTER
        );

        table.setDefaultRenderer(
                Object.class,
                renderer
        );

        DefaultTableCellRenderer headerRenderer =
                (DefaultTableCellRenderer)
                table.getTableHeader()
                     .getDefaultRenderer();

        headerRenderer.setHorizontalAlignment(
                SwingConstants.CENTER
        );

        JScrollPane scroll =
                new JScrollPane(table);

        scroll.setPreferredSize(
                new Dimension(1100, 160)
        );

        scroll.setBorder(
                BorderFactory.createLineBorder(
                        BORDER
                )
        );

        outer.add(scroll, BorderLayout.CENTER);

        return outer;
    }

    private Object nullableJsonValue(
            JSONObject object,
            String key) {

        if (!object.has(key)
                || object.isNull(key)) {
            return "—";
        }

        return object.get(key);
    }
}