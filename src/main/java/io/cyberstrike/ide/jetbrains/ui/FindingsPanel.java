package io.cyberstrike.ide.jetbrains.ui;

import io.cyberstrike.ide.jetbrains.model.Vulnerability;
import io.cyberstrike.ide.jetbrains.service.CyberStrikeService;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Findings table for the current CyberStrike session.
 *
 * <p>Shows every vulnerability the agents recorded, colour-coded by severity,
 * with quick actions to open the affected file, open the endpoint, copy the
 * report and ask the agent to prove exploitability.
 */
public final class FindingsPanel extends JPanel implements Disposable {

    private static final String[] COLUMNS = {"Sev", "Title", "Status", "Location", "CWE"};

    private final Project project;
    private final CyberStrikeService service;
    private final FindingsTableModel model = new FindingsTableModel();
    private final JBTable table = new JBTable(model);
    private final JBLabel summary = new JBLabel();
    private final JBLabel status = new JBLabel();
    private final JComboBox<String> severityFilter = new JComboBox<>(
            new String[]{"All severities", "Critical", "High", "Medium", "Low", "Info"});
    private final JBCheckBox hideClosed = new JBCheckBox("Hide closed", true);
    private final TableRowSorter<FindingsTableModel> sorter = new TableRowSorter<>(model);
    private final Runnable listener = this::reload;

    public FindingsPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.service = ServiceManager.getService(project, CyberStrikeService.class);

        add(buildHeader(), BorderLayout.NORTH);
        add(buildTable(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        service.addFindingsListener(listener);
        service.addListener(listener);
        reload();
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(JBUI.Borders.empty(6, 8, 4, 8));

        summary.setBorder(JBUI.Borders.emptyRight(8));
        header.add(summary, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        severityFilter.addActionListener(e -> applyFilter());
        right.add(severityFilter);
        hideClosed.addActionListener(e -> applyFilter());
        right.add(hideClosed);
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> service.refreshFindingsAsync());
        right.add(refresh);
        JButton verify = new JButton("Verify with CyberStrike");
        verify.addActionListener(e -> verifySelected());
        right.add(verify);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JBScrollPane buildTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(22);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setMaxWidth(58);
        table.getColumnModel().getColumn(0).setPreferredWidth(52);
        table.getColumnModel().getColumn(1).setPreferredWidth(420);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);
        table.getColumnModel().getColumn(3).setPreferredWidth(220);
        table.getColumnModel().getColumn(4).setPreferredWidth(90);

        table.getColumnModel().getColumn(0).setCellRenderer(new SeverityRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new StatusRenderer());

        TableRowSorter<FindingsTableModel> tableSorter = sorter;
        tableSorter.setComparator(0, Comparator.comparingInt(o -> ((Vulnerability) o).severityRank()));
        table.setRowSorter(tableSorter);

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) openSelected();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }
        });

        JBScrollPane scroll = new JBScrollPane(table);
        scroll.setBorder(JBUI.Borders.empty());
        return scroll;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(JBUI.Borders.empty(4, 8, 6, 8));
        status.setForeground(Theme.MUTED);
        footer.add(status, BorderLayout.WEST);
        JBLabel hint = new JBLabel("Double-click a row to open the affected file");
        hint.setForeground(Theme.MUTED);
        footer.add(hint, BorderLayout.EAST);
        return footer;
    }

    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0) return;
        table.setRowSelectionInterval(row, row);
        Vulnerability v = selected();
        if (v == null) return;

        JPopupMenu menu = new JPopupMenu();
        JMenuItem verify = new JMenuItem("Verify with CyberStrike");
        verify.addActionListener(a -> service.askAbout(v));
        menu.add(verify);

        JMenuItem openFile = new JMenuItem("Open file");
        openFile.setEnabled(!v.file.isBlank());
        openFile.addActionListener(a -> openFile(v));
        menu.add(openFile);

        JMenuItem openEndpoint = new JMenuItem("Open endpoint in browser");
        openEndpoint.setEnabled(v.endpoint.startsWith("http"));
        openEndpoint.addActionListener(a -> BrowserUtil.browse(v.endpoint));
        menu.add(openEndpoint);

        menu.addSeparator();

        JMenuItem copyTitle = new JMenuItem("Copy title");
        copyTitle.addActionListener(a -> copy(v.displayTitle()));
        menu.add(copyTitle);

        JMenuItem copyDetails = new JMenuItem("Copy finding as Markdown");
        copyDetails.addActionListener(a -> copy(asMarkdown(v)));
        menu.add(copyDetails);

        JMenuItem copyPoc = new JMenuItem("Copy proof of concept");
        copyPoc.setEnabled(!v.poc.isBlank());
        copyPoc.addActionListener(a -> copy(v.poc));
        menu.add(copyPoc);

        menu.show(table, e.getX(), e.getY());
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private Vulnerability selected() {
        int row = table.getSelectedRow();
        if (row < 0) return null;
        int modelRow = table.convertRowIndexToModel(row);
        return modelRow >= 0 && modelRow < model.getRowCount() ? model.get(modelRow) : null;
    }

    private void verifySelected() {
        Vulnerability v = selected();
        if (v == null) {
            status.setText("Select a finding first");
            return;
        }
        service.askAbout(v);
    }

    private void openSelected() {
        Vulnerability v = selected();
        if (v == null) return;
        if (!v.file.isBlank()) openFile(v);
        else if (v.endpoint.startsWith("http")) BrowserUtil.browse(v.endpoint);
    }

    private void openFile(Vulnerability v) {
        if (v.file.isBlank()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            VirtualFile file = resolve(v.file);
            if (file == null) {
                status.setText("File not found in this project: " + v.file);
                return;
            }
            int line = Math.max(v.lineStart - 1, 0);
            new OpenFileDescriptor(project, file, line, 0).navigate(true);
        });
    }

    private VirtualFile resolve(String path) {
        String p = path.replace('\\', '/');
        LocalFileSystem lfs = LocalFileSystem.getInstance();
        if (p.startsWith("/") || (p.length() > 1 && p.charAt(1) == ':')) return lfs.findFileByPath(p);
        String base = project.getBasePath();
        if (base == null) return null;
        return lfs.findFileByPath(base.replace('\\', '/') + "/" + p);
    }

    private void copy(String text) {
        if (text == null || text.isBlank()) return;
        CopyPasteManager.getInstance().setContents(new StringSelection(text));
        status.setText("Copied to clipboard");
    }

    private static String asMarkdown(Vulnerability v) {
        StringBuilder sb = new StringBuilder();
        sb.append("### [").append(v.severity.toUpperCase()).append("] ").append(v.displayTitle()).append("\n\n");
        if (!v.cwe.isBlank()) sb.append("- **CWE:** ").append(v.cwe).append('\n');
        if (!v.endpoint.isBlank()) sb.append("- **Endpoint:** ").append(v.endpoint).append('\n');
        if (!v.file.isBlank()) sb.append("- **Location:** ").append(v.location()).append('\n');
        sb.append("- **Status:** ").append(v.status).append('\n');
        if (!v.attackVector.isBlank()) sb.append("- **Attack vector:** ").append(v.attackVector).append('\n');
        sb.append('\n').append(v.description).append('\n');
        if (!v.stepsToReproduce.isBlank()) sb.append("\n**Steps to reproduce**\n\n").append(v.stepsToReproduce).append('\n');
        if (!v.poc.isBlank()) sb.append("\n**Proof of concept**\n\n```\n").append(v.poc).append("\n```\n");
        if (!v.businessImpact.isBlank()) sb.append("\n**Business impact**\n\n").append(v.businessImpact).append('\n');
        if (!v.recommendation.isBlank()) sb.append("\n**Recommendation**\n\n").append(v.recommendation).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private void reload() {
        List<Vulnerability> all = service.getFindings();
        model.setRows(all);
        applyFilter();
        updateSummary(all);
    }

    private void updateSummary(List<Vulnerability> all) {
        int[] counts = service.findingCounts();
        int open = 0;
        for (Vulnerability v : all) if (v.isOpen()) open++;
        StringBuilder html = new StringBuilder("<html><b>Findings</b> ");
        if (all.isEmpty()) {
            html.append("<font color='").append(hex(Theme.MUTED)).append("'>none yet</font>");
        } else {
            html.append("<font color='").append(hex(Theme.MUTED)).append("'>")
                    .append(open).append(" open / ").append(all.size()).append(" total</font>");
            String[] names = {"critical", "high", "medium", "low", "info"};
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] == 0) continue;
                html.append(" &nbsp;<font color='").append(hex(Theme.severity(names[i])))
                        .append("'><b>").append(counts[i]).append(Theme.letter(names[i])).append("</b></font>");
            }
        }
        html.append("</html>");
        summary.setText(html.toString());

        String sid = service.getSessionId();
        if (!service.isConnected()) {
            status.setText("Not connected \u2014 start the server with: cyberstrike web");
        } else if (sid == null || sid.isBlank()) {
            status.setText("No session yet");
        } else {
            status.setText("Session " + sid);
        }
    }

    private void applyFilter() {
        final int sevIndex = severityFilter.getSelectedIndex();
        final boolean hide = hideClosed.isSelected();
        sorter.setRowFilter(new RowFilter<FindingsTableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends FindingsTableModel, ? extends Integer> entry) {
                Vulnerability v = model.get(entry.getIdentifier());
                if (hide && !v.isOpen()) return false;
                if (sevIndex <= 0) return true;
                String[] order = {"critical", "high", "medium", "low", "info"};
                return v.severity.equals(order[sevIndex - 1]);
            }
        });
    }

    private static String hex(java.awt.Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    @Override
    public void dispose() {
        service.removeFindingsListener(listener);
        service.removeListener(listener);
    }

    // ------------------------------------------------------------------
    // Table model / renderers
    // ------------------------------------------------------------------

    private static final class FindingsTableModel extends AbstractTableModel {
        private final List<Vulnerability> rows = new ArrayList<>();

        void setRows(List<Vulnerability> newRows) {
            rows.clear();
            rows.addAll(newRows);
            fireTableDataChanged();
        }

        Vulnerability get(int index) {
            return rows.get(index);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Vulnerability v = rows.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return v.severity;
                case 1:
                    return v.displayTitle();
                case 2:
                    return v.status;
                case 3:
                    return v.location();
                case 4:
                    return v.cwe;
                default:
                    return "";
            }
        }
    }

    private static final class SeverityRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String sev = value == null ? "info" : value.toString();
            setText(Theme.letter(sev));
            setHorizontalAlignment(SwingConstants.CENTER);
            if (!isSelected) c.setForeground(Theme.severity(sev));
            setFont(getFont().deriveFont(java.awt.Font.BOLD));
            return c;
        }
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String st = value == null ? "" : value.toString();
            if (!isSelected) {
                if ("approved".equals(st) || "open".equals(st)) c.setForeground(Theme.OK);
                else if ("duplicate".equals(st) || "ignored".equals(st)) c.setForeground(Theme.MUTED);
                else if ("fixed".equals(st)) c.setForeground(Theme.MUTED);
                else c.setForeground(Theme.ACCENT);
            }
            return c;
        }
    }
}
