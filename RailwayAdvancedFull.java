/*
 RailwayAdvancedFull.java
 Single-file Java Swing application with:
 - Material-like UI
 - Multi-passenger booking (dynamic forms)
 - Seat map with berth types (Lower/Upper/Side)
 - Class pricing + berth modifiers
 - Admin mode (simple password)
 - PNR search, cancel, export CSV
 - TXT ticket generation + optional PDF via reflection (PDFBox)
 - Java2D occupancy chart and improved UI polish

 Note: This is a demo educational program. For production, persist to DB and secure credentials.
*/
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class RailwayAdvancedFull extends JFrame {

    // ---------------- Models ----------------
    static class TrainClass {
        String name;         // "AC", "Sleeper", "General"
        int rows, cols;      // seat grid
        double baseFare;
        Map<Integer, String> berthMap; // seatIndex -> berth type (Lower/Upper/Side)
        TrainClass(String name, int rows, int cols, double baseFare) {
            this.name = name; this.rows = rows; this.cols = cols; this.baseFare = baseFare;
            this.berthMap = new HashMap<>();
        }
        int capacity() { return rows * cols; }
    }

    static class Train {
        int no;
        String name;
        String src, dest;
        Map<String, boolean[]> seatsByClass; // className -> seatTaken boolean array
        Map<String, TrainClass> classes;
        Train(int no, String name, String src, String dest, List<TrainClass> clsList) {
            this.no = no; this.name = name; this.src = src; this.dest = dest;
            classes = new LinkedHashMap<>();
            seatsByClass = new HashMap<>();
            for (TrainClass tc : clsList) {
                classes.put(tc.name, tc);
                seatsByClass.put(tc.name, new boolean[tc.capacity()]);
            }
        }
    }

    static class Passenger {
        String name;
        int age;
        String gender;
        Passenger(String name, int age, String gender) { this.name = name; this.age = age; this.gender = gender; }
        @Override public String toString() { return name + " (" + age + ")"; }
    }

    static class Booking {
        static int nextPNR = 7000;
        int pnr;
        List<Passenger> passengers;
        Train train;
        String trainClass;
        int[] seats; // indices
        LocalDate date;
        double totalFare;
        Booking(List<Passenger> ps, Train train, String trainClass, int[] seats, LocalDate date, double totalFare) {
            this.pnr = nextPNR++;
            this.passengers = ps; this.train = train; this.trainClass = trainClass; this.seats = seats; this.date = date; this.totalFare = totalFare;
        }
    }

    // ---------------- Data ----------------
    private final List<Train> trains = new ArrayList<>();
    private final List<Booking> bookings = new ArrayList<>();

    // UI components
    private CardLayout mainCards = new CardLayout();
    private JPanel cardPanel = new JPanel(mainCards);

    private JComboBox<String> trainSelector;
    private JComboBox<String> classSelector;
    private JPanel seatGridPanel;
    private JComboBox<String> dateSelector;

    private JPanel passengerListPanel; // dynamic passenger forms
    private JLabel fareSummaryLabel;
    private JButton bookButton;

    private DefaultTableModel bookingsModel;
    private JTable bookingsTable;

    private Train selectedTrain;
    private String selectedClass;
    private java.util.List<Integer> selectedSeats = new ArrayList<>();

    private final boolean pdfBoxAvailable;

    // Material-like colors and fonts
    private final Color MAT_BG = new Color(246, 248, 250);
    private final Color MAT_CARD = Color.white;
    private final Color MAT_ACCENT = new Color(0, 121, 107);
    private final Color MAT_TEXT = new Color(33, 33, 33);
    private final Font HEAD = new Font("SansSerif", Font.BOLD, 18);
    private final Font SUB = new Font("SansSerif", Font.PLAIN, 13);

    private boolean isAdminMode = false;

    private final Map<String, Double> BERTH_PRICE_MOD = new HashMap<String, Double>() {{
        put("Lower", 0.0);
        put("Upper", 0.10);
        put("Side", -0.20);
    }};

    private final Map<String, Double> CLASS_PRICE_MULT = new HashMap<String, Double>() {{
        put("AC", 1.0);
        put("Sleeper", 0.7);
        put("General", 0.35);
    }};

    // ---------------- Constructor ----------------
    public RailwayAdvancedFull() {
        super("Railway — Full Single File");
        setSize(1100, 760);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        getContentPane().setBackground(MAT_BG);

        pdfBoxAvailable = detectPDFBox();
        initSampleData();
        initUI();
    }

    private boolean detectPDFBox() {
        try { Class.forName("org.apache.pdfbox.pdmodel.PDDocument"); return true; }
        catch (ClassNotFoundException e) { return false; }
    }

    private void initSampleData() {
        TrainClass ac = new TrainClass("AC", 4, 4, 1200.0);
        TrainClass sl = new TrainClass("Sleeper", 6, 4, 700.0);
        TrainClass gn = new TrainClass("General", 8, 6, 350.0);
        fillBerths(ac); fillBerths(sl); fillBerths(gn);
        List<TrainClass> cls = Arrays.asList(ac, sl, gn);
        trains.add(new Train(101, "InterCity Express", "CityA", "CityB", cls));
        trains.add(new Train(102, "Coastal Mail", "CityB", "CityC", cls));
        trains.add(new Train(303, "Mountain Special", "CityC", "CityD", cls));
    }

    private void fillBerths(TrainClass tc) {
        for (int r = 0; r < tc.rows; r++) {
            for (int c = 0; c < tc.cols; c++) {
                int idx = r * tc.cols + c;
                String berth;
                if (tc.cols >= 4 && c == tc.cols - 1) berth = "Side";
                else berth = (c % 2 == 0) ? "Lower" : "Upper";
                tc.berthMap.put(idx, berth);
            }
        }
    }

    private void initUI() {
        // top header with gradient and controls
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        JLabel title = new JLabel("🚆 Railway — Full Demo", JLabel.LEFT);
        title.setFont(new Font("SansSerif", Font.BOLD, 20));
        title.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        top.add(title, BorderLayout.WEST);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        controls.setOpaque(false);
        JButton toggleAdmin = new JButton("Switch to Admin");
        JButton aboutBtn = new JButton("About");
        controls.add(toggleAdmin); controls.add(aboutBtn);
        top.add(controls, BorderLayout.EAST);

        toggleAdmin.addActionListener(e -> {
            if (!isAdminMode) {
                String pwd = JOptionPane.showInputDialog(this, "Enter admin password (demo):");
                if ("admin123".equals(pwd)) {
                    isAdminMode = true; toggleAdmin.setText("Switch to User");
                    JOptionPane.showMessageDialog(this, "Admin mode enabled.");
                } else JOptionPane.showMessageDialog(this, "Wrong password.");
            } else {
                isAdminMode = false; toggleAdmin.setText("Switch to Admin");
                JOptionPane.showMessageDialog(this, "Admin mode disabled.");
            }
        });

        aboutBtn.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "RailwayAdvancedFull — Demo\nFeatures: Multi-passenger booking, berth types, admin, CSV, TXT/PDF tickets\nPDF requires Apache PDFBox on classpath."));

        // nav toolbar
        JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        nav.setOpaque(false);
        JButton bookNav = new JButton("Book Ticket");
        JButton viewNav = new JButton("View / Manage");
        JButton searchNav = new JButton("Search PNR");
        nav.add(bookNav); nav.add(viewNav); nav.add(searchNav);

        bookNav.addActionListener(e -> mainCards.show(cardPanel, "BOOK"));
        viewNav.addActionListener(e -> { refreshBookingsTable(); mainCards.show(cardPanel, "ADMIN"); });
        searchNav.addActionListener(e -> showSearchDialog());

        // cards
        cardPanel.add(buildBookingPanel(), "BOOK");
        cardPanel.add(buildAdminPanel(), "ADMIN");

        // layout main
        getContentPane().setLayout(new BorderLayout(8,8));
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(nav, BorderLayout.CENTER);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(cardPanel, BorderLayout.CENTER);
        wrapper.setBorder(new EmptyBorder(8,8,8,8));
        getContentPane().add(wrapper, BorderLayout.SOUTH);

        mainCards.show(cardPanel, "BOOK");
    }

    private JPanel buildBookingPanel() {
        JPanel p = createCardPanel();

        JPanel left = new JPanel(new BorderLayout(8,8));
        left.setOpaque(false);
        left.setPreferredSize(new Dimension(460, 580));
        left.setBorder(new EmptyBorder(12,12,12,12));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(MAT_CARD);
        form.setBorder(createCardBorder());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(8,8,8,8); g.anchor = GridBagConstraints.WEST;

        g.gridx = 0; g.gridy = 0; form.add(labeled("Train", SUB), g);
        g.gridx = 1; trainSelector = new JComboBox<>();
        for (Train t : trains) trainSelector.addItem(t.no + " - " + t.name + " (" + t.src + "→" + t.dest + ")");
        form.add(trainSelector, g);

        g.gridx = 0; g.gridy = 1; form.add(labeled("Class", SUB), g);
        g.gridx = 1; classSelector = new JComboBox<>(); form.add(classSelector, g);

        g.gridx = 0; g.gridy = 2; form.add(labeled("Date", SUB), g);
        g.gridx = 1; dateSelector = new JComboBox<>(); LocalDate today = LocalDate.now();
        dateSelector.addItem(today.toString()); dateSelector.addItem(today.plusDays(1).toString()); dateSelector.addItem(today.plusDays(2).toString());
        form.add(dateSelector, g);

        g.gridx = 0; g.gridy = 3; g.gridwidth = 2;
        passengerListPanel = new JPanel(); passengerListPanel.setLayout(new BoxLayout(passengerListPanel, BoxLayout.Y_AXIS));
        passengerListPanel.setBorder(new EmptyBorder(6,6,6,6));
        passengerListPanel.add(createPassengerRow()); // at least one
        JScrollPane passScroll = new JScrollPane(passengerListPanel); passScroll.setPreferredSize(new Dimension(420, 160));
        form.add(passScroll, g);

        g.gridy = 4; JPanel addRem = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addP = new JButton("Add Passenger"); JButton remP = new JButton("Remove Passenger");
        addRem.add(addP); addRem.add(remP); form.add(addRem, g);

        g.gridy = 5; fareSummaryLabel = new JLabel("Selected seats: 0 — Total ₹0.00"); fareSummaryLabel.setFont(SUB);
        form.add(fareSummaryLabel, g);

        left.add(form, BorderLayout.NORTH);

        seatGridPanel = new JPanel(); seatGridPanel.setPreferredSize(new Dimension(420, 320)); seatGridPanel.setBorder(createCardBorder()); seatGridPanel.setBackground(MAT_CARD);
        left.add(new JScrollPane(seatGridPanel), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT)); actions.setOpaque(false);
        JButton clear = new JButton("Clear Seats"); bookButton = new JButton("Book & Generate Ticket");
        bookButton.setBackground(MAT_ACCENT); bookButton.setForeground(Color.WHITE);
        actions.add(clear); actions.add(bookButton); left.add(actions, BorderLayout.SOUTH);

        // right info + chart
        JPanel right = new JPanel(new BorderLayout(8,8)); right.setOpaque(false); right.setPreferredSize(new Dimension(560, 580)); right.setBorder(new EmptyBorder(12,12,12,12));
        JPanel infoCard = new JPanel(new GridLayout(6,1)); infoCard.setBorder(createCardBorder()); infoCard.setBackground(MAT_CARD);
        infoCard.add(labeled("Train details", HEAD));
        infoCard.add(new JLabel("Select a train and class to view layout."));
        infoCard.add(new JLabel("Admin mode: switch using top-right."));
        infoCard.add(new JLabel("PDF tickets require PDFBox."));
        infoCard.add(new JLabel("Tip: Hover seat for berth type."));
        right.add(infoCard, BorderLayout.NORTH);
        JPanel chartCard = new JPanel(new BorderLayout()); chartCard.setBorder(createCardBorder()); chartCard.setBackground(MAT_CARD);
        chartCard.add(new ChartPanel(), BorderLayout.CENTER); right.add(chartCard, BorderLayout.CENTER);

        // wire events
        trainSelector.addActionListener(e -> populateClassesAndRebuild());
        classSelector.addActionListener(e -> rebuildSeatGrid());
        addP.addActionListener(e -> { passengerListPanel.add(createPassengerRow()); passengerListPanel.revalidate(); passengerListPanel.repaint(); });
        remP.addActionListener(e -> { if (passengerListPanel.getComponentCount() > 1) { passengerListPanel.remove(passengerListPanel.getComponentCount()-1); passengerListPanel.revalidate(); passengerListPanel.repaint(); } });
        clear.addActionListener(e -> { selectedSeats.clear(); updateSeatHighlights(); updateFareLabel(); });
        bookButton.addActionListener(e -> performBooking());

        populateClassesAndRebuild();

        JPanel container = new JPanel(new GridBagLayout()); container.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(6,6,6,6); c.gridx = 0; c.gridy = 0; container.add(left, c); c.gridx = 1; container.add(right, c);
        p.add(container, BorderLayout.CENTER);
        return p;
    }

    /**
     * createPassengerRow
     * - Creates a passenger input row with fields and stores their references
     *   in the panel's client properties: "name", "age", "gender".
     */
    private JPanel createPassengerRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row.setOpaque(false);

        JLabel nameLbl = new JLabel("Name:");
        JTextField nameField = new JTextField(10);

        JLabel ageLbl = new JLabel("Age:");
        JTextField ageField = new JTextField(3);

        JLabel genderLbl = new JLabel("Gender:");
        JComboBox<String> genderField = new JComboBox<>(new String[]{"M","F","Other"});

        row.add(nameLbl); row.add(nameField);
        row.add(ageLbl); row.add(ageField);
        row.add(genderLbl); row.add(genderField);

        // Store references so performBooking can access them reliably
        row.putClientProperty("name", nameField);
        row.putClientProperty("age", ageField);
        row.putClientProperty("gender", genderField);

        return row;
    }

    private void populateClassesAndRebuild() {
        int idx = trainSelector.getSelectedIndex();
        if (idx < 0) return;
        selectedTrain = trains.get(idx);
        classSelector.removeAllItems();
        for (String c : selectedTrain.classes.keySet()) classSelector.addItem(c);
        classSelector.setSelectedIndex(0);
        rebuildSeatGrid();
    }

    private void rebuildSeatGrid() {
        selectedSeats.clear();
        int tIdx = trainSelector.getSelectedIndex();
        if (tIdx < 0) return;
        selectedTrain = trains.get(tIdx);
        selectedClass = (String) classSelector.getSelectedItem();
        if (selectedClass == null) return;
        TrainClass tc = selectedTrain.classes.get(selectedClass);
        boolean[] taken = selectedTrain.seatsByClass.get(selectedClass);

        seatGridPanel.removeAll();
        seatGridPanel.setLayout(new GridLayout(tc.rows, tc.cols, 6, 6));
        for (int i = 0; i < tc.capacity(); i++) {
            int seatIndex = i;
            String berth = tc.berthMap.getOrDefault(i, "Seat");
            JButton b = new JButton((i+1) + "\n" + berth.charAt(0));
            b.setToolTipText("Seat " + (i+1) + " - " + berth);
            b.setFocusPainted(false);
            b.setOpaque(true);
            b.setBorder(new LineBorder(Color.LIGHT_GRAY));
            b.setBackground(taken[i] ? Color.DARK_GRAY : new Color(235, 252, 245));
            b.setEnabled(!taken[i]);
            b.addActionListener(ev -> {
                if (taken[seatIndex]) return;
                if (!isAdminMode && selectedSeats.size() >= 6) { JOptionPane.showMessageDialog(this, "Limit 6 seats for user. Use Admin for bulk bookings."); return; }
                if (selectedSeats.contains(seatIndex)) selectedSeats.remove(Integer.valueOf(seatIndex));
                else selectedSeats.add(seatIndex);
                updateSeatHighlights();
                updateFareLabel();
            });
            seatGridPanel.add(b);
        }
        seatGridPanel.revalidate(); seatGridPanel.repaint();
        updateFareLabel();
    }

    private void updateSeatHighlights() {
        if (selectedTrain == null || selectedClass == null) { rebuildSeatGrid(); return; }
        TrainClass tc = selectedTrain.classes.get(selectedClass);
        boolean[] taken = selectedTrain.seatsByClass.get(selectedClass);
        Component[] comps = seatGridPanel.getComponents();
        for (int i = 0; i < comps.length; i++) {
            JButton b = (JButton) comps[i];
            if (taken[i]) { b.setBackground(Color.DARK_GRAY); b.setEnabled(false); b.setForeground(Color.WHITE); }
            else if (selectedSeats.contains(i)) { b.setBackground(MAT_ACCENT); b.setEnabled(true); b.setForeground(Color.WHITE); }
            else { b.setBackground(new Color(235, 252, 245)); b.setEnabled(true); b.setForeground(Color.BLACK); }
        }
    }

    private void updateFareLabel() {
        if (selectedTrain == null || selectedClass == null) { fareSummaryLabel.setText("Select seats"); return; }
        TrainClass tc = selectedTrain.classes.get(selectedClass);
        double total = 0.0;
        for (int s : selectedSeats) {
            String berth = tc.berthMap.getOrDefault(s, "Lower");
            double berthMod = BERTH_PRICE_MOD.getOrDefault(berth, 0.0);
            double classMult = CLASS_PRICE_MULT.getOrDefault(tc.name, 1.0);
            total += tc.baseFare * classMult * (1.0 + berthMod);
        }
        fareSummaryLabel.setText("Selected seats: " + selectedSeats.size() + " — Total ₹" + new DecimalFormat("#.##").format(total));
    }

    /**
     * performBooking
     * - Reads passenger rows from passengerListPanel via client properties ("name","age","gender")
     * - Validates values for each passenger
     * - Ensures a clear confirmation dialog shows the PNR after booking
     */
    private void performBooking() {
        if (selectedSeats.isEmpty()) { JOptionPane.showMessageDialog(this, "Select seats."); return; }

        // collect passengers (from client properties)
        List<Passenger> passengers = new ArrayList<>();
        int rowIndex = 0;
        for (Component comp : passengerListPanel.getComponents()) {
            if (!(comp instanceof JPanel)) continue;
            JPanel row = (JPanel) comp;

            Object nameObj = row.getClientProperty("name");
            Object ageObj = row.getClientProperty("age");
            Object genderObj = row.getClientProperty("gender");

            if (!(nameObj instanceof JTextField) || !(ageObj instanceof JTextField) || !(genderObj instanceof JComboBox)) {
                JOptionPane.showMessageDialog(this, "Internal error reading passenger form at row " + (rowIndex+1));
                return;
            }

            JTextField nameField = (JTextField) nameObj;
            JTextField ageField = (JTextField) ageObj;
            @SuppressWarnings("unchecked")
            JComboBox<String> genderField = (JComboBox<String>) genderObj;

            String nm = nameField.getText().trim();
            String ag = ageField.getText().trim();

            if (nm.isEmpty() || ag.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please fill all details for passenger row " + (rowIndex+1));
                return;
            }
            int ageVal;
            try {
                ageVal = Integer.parseInt(ag);
                if (ageVal < 0 || ageVal > 120) { JOptionPane.showMessageDialog(this, "Enter a realistic age for passenger " + nm); return; }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Invalid age for passenger " + nm);
                return;
            }

            String gender = (String) genderField.getSelectedItem();
            passengers.add(new Passenger(nm, ageVal, gender));
            rowIndex++;
        }

        // If passenger count differs from seats, confirm with user
        if (passengers.size() != selectedSeats.size()) {
            int ok = JOptionPane.showConfirmDialog(this, "Passengers count (" + passengers.size() + ") differs from seats selected (" + selectedSeats.size() + "). Continue?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (ok != JOptionPane.YES_OPTION) return;
        }

        // compute fare
        double totalFare = 0.0;
        TrainClass tc = selectedTrain.classes.get(selectedClass);
        for (int s : selectedSeats) {
            String berth = tc.berthMap.getOrDefault(s, "Lower");
            double berthMod = BERTH_PRICE_MOD.getOrDefault(berth, 0.0);
            double classMult = CLASS_PRICE_MULT.getOrDefault(tc.name, 1.0);
            totalFare += tc.baseFare * classMult * (1.0 + berthMod);
        }

        String passengerSummary = passengers.stream().map(Object::toString).collect(Collectors.joining(", "));
        String confirmMsg = "Train: " + selectedTrain.no + " - " + selectedTrain.name + "\nClass: " + selectedClass + "\nSeats: " +
                selectedSeats.stream().map(i -> String.valueOf(i+1)).collect(Collectors.joining(",")) +
                "\nPassengers: " + passengerSummary + "\nTotal: ₹" + new DecimalFormat("#.##").format(totalFare) + "\nConfirm booking?";
        int res = JOptionPane.showConfirmDialog(this, confirmMsg, "Confirm", JOptionPane.YES_NO_OPTION);
        if (res != JOptionPane.YES_OPTION) return;

        // mark seats taken
        boolean[] taken = selectedTrain.seatsByClass.get(selectedClass);
        for (int s : selectedSeats) taken[s] = true;
        int[] arr = selectedSeats.stream().mapToInt(i->i).toArray();
        LocalDate date = LocalDate.parse((String) dateSelector.getSelectedItem());
        Booking b = new Booking(passengers, selectedTrain, selectedClass, arr, date, totalFare);
        bookings.add(b);

        // persist ticket (TXT)
        Path out = null;
        try {
            out = Paths.get("ticket_" + b.pnr + ".txt");
            List<String> lines = new ArrayList<>();
            lines.add("---- RAILWAY TICKET ----");
            lines.add("PNR: " + b.pnr);
            lines.add("Train: " + b.train.no + " - " + b.train.name);
            lines.add("Route: " + b.train.src + " -> " + b.train.dest);
            lines.add("Class: " + b.trainClass);
            TrainClass trainClass = b.train.classes.get(b.trainClass);
            String seatsWithBerth = Arrays.stream(b.seats).mapToObj(i -> (i+1) + "(" + trainClass.berthMap.getOrDefault(i,"Seat") + ")").collect(Collectors.joining(", "));
            lines.add("Seats: " + seatsWithBerth);
            lines.add("Passengers: " + b.passengers.stream().map(Object::toString).collect(Collectors.joining("; ")));
            lines.add("Date: " + b.date.toString());
            lines.add("Fare: ₹" + new DecimalFormat("#.##").format(b.totalFare));
            Files.write(out, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // if PDFBox exists, attempt PDF creation (best-effort)
            if (pdfBoxAvailable) {
                try {
                    createPdfTicketReflective(b, out);
                    JOptionPane.showMessageDialog(this, "Booking successful!\nPNR: " + b.pnr + "\nTicket saved: " + out.toAbsolutePath() + " and PDF.");
                } catch (Exception pdfEx) {
                    JOptionPane.showMessageDialog(this, "Booking successful!\nPNR: " + b.pnr + "\nTicket saved: " + out.toAbsolutePath() + "\n(But PDF generation failed: " + pdfEx.getMessage() + ")");
                }
            } else {
                JOptionPane.showMessageDialog(this, "Booking successful!\nPNR: " + b.pnr + "\nTicket saved: " + out.toAbsolutePath() + "\n(To enable PDF, add Apache PDFBox to classpath.)");
            }

        } catch (Exception ex) {
            String msg = "Failed to save ticket: " + ex.getMessage();
            if (out != null) msg += "\nAttempted path: " + out.toAbsolutePath();
            JOptionPane.showMessageDialog(this, msg);
        }

        // Post-booking cleanup
        selectedSeats.clear();
        rebuildSeatGrid();
        refreshBookingsTable();
        updateFareLabel();
    }

    private void createPdfTicketReflective(Booking b, Path outTxt) throws Exception {
        List<String> lines = Files.readAllLines(outTxt, StandardCharsets.UTF_8);
        Class<?> pdDoc = Class.forName("org.apache.pdfbox.pdmodel.PDDocument");
        Class<?> pdPageClass = Class.forName("org.apache.pdfbox.pdmodel.PDPage");
        Class<?> pdContent = Class.forName("org.apache.pdfbox.pdmodel.PDPageContentStream");
        Class<?> pdType1 = Class.forName("org.apache.pdfbox.pdmodel.font.PDType1Font");

        Object doc = pdDoc.getDeclaredConstructor().newInstance();
        Object page = pdPageClass.getDeclaredConstructor().newInstance();
        pdDoc.getMethod("addPage", pdPageClass).invoke(doc, page);
        Constructor<?> csCtor = pdContent.getConstructor(pdDoc, pdPageClass);
        Object cs = csCtor.newInstance(doc, page);

        pdContent.getMethod("beginText").invoke(cs);
        Object font = pdType1.getField("HELVETICA").get(null);
        pdContent.getMethod("setFont", Class.forName("org.apache.pdfbox.pdmodel.font.PDFont"), float.class).invoke(cs, font, 12f);
        pdContent.getMethod("newLineAtOffset", float.class, float.class).invoke(cs, 50f, 700f);
        for (String line : lines) {
            pdContent.getMethod("showText", String.class).invoke(cs, line);
            pdContent.getMethod("newLineAtOffset", float.class, float.class).invoke(cs, 0f, -14f);
        }
        pdContent.getMethod("endText").invoke(cs);
        pdContent.getMethod("close").invoke(cs);

        String pdfName = "ticket_" + b.pnr + ".pdf";
        pdDoc.getMethod("save", String.class).invoke(doc, pdfName);
        pdDoc.getMethod("close").invoke(doc);
    }

    private JPanel buildAdminPanel() {
        JPanel p = createCardPanel();
        p.setLayout(new BorderLayout(12,12));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.setOpaque(false);
        JButton refresh = new JButton("Refresh");
        JButton exportCsv = new JButton("Export Bookings CSV");
        JButton cancelBtn = new JButton("Cancel Booking (by PNR)");
        JTextField cancelField = new JTextField(8);
        JButton searchPnrBtn = new JButton("Search PNR");
        JTextField searchPnrField = new JTextField(8);
        JButton freeAllBtn = new JButton("Free All Seats (Admin)");
        top.add(refresh); top.add(exportCsv); top.add(new JLabel("Cancel PNR:")); top.add(cancelField); top.add(cancelBtn);
        top.add(new JLabel("Search PNR:")); top.add(searchPnrField); top.add(searchPnrBtn); top.add(freeAllBtn);
        p.add(top, BorderLayout.NORTH);

        bookingsModel = new DefaultTableModel(new Object[]{"PNR","Train","Class","Seats","Passengers","Date","Fare"}, 0);
        bookingsTable = new JTable(bookingsModel);
        p.add(new JScrollPane(bookingsTable), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setPreferredSize(new Dimension(100,220)); bottom.add(new ChartPanel(), BorderLayout.CENTER);
        p.add(bottom, BorderLayout.SOUTH);

        refresh.addActionListener(e -> refreshBookingsTable());
        exportCsv.addActionListener(e -> exportBookingsCsv());
        cancelBtn.addActionListener(e -> {
            String t = cancelField.getText().trim();
            if (t.isEmpty()) return;
            try {
                int pnr = Integer.parseInt(t);
                Optional<Booking> toRemove = bookings.stream().filter(b -> b.pnr == pnr).findFirst();
                if (!toRemove.isPresent()) { JOptionPane.showMessageDialog(this, "PNR not found."); return; }
                // free seats
                Booking b = toRemove.get();
                boolean[] arr = b.train.seatsByClass.get(b.trainClass);
                for (int s : b.seats) arr[s] = false;
                bookings.remove(b);
                JOptionPane.showMessageDialog(this, "Cancelled PNR " + pnr);
                refreshBookingsTable();
            } catch (Exception ex) { JOptionPane.showMessageDialog(this, "Invalid PNR."); }
        });

        searchPnrBtn.addActionListener(e -> {
            String t = searchPnrField.getText().trim();
            if (t.isEmpty()) return;
            try {
                int pnr = Integer.parseInt(t);
                Booking f = null; for (Booking b : bookings) if (b.pnr == pnr) { f = b; break; }
                if (f == null) JOptionPane.showMessageDialog(this, "PNR not found.");
                else JOptionPane.showMessageDialog(this, toDisplayString(f));
            } catch (Exception ex) { JOptionPane.showMessageDialog(this, "Invalid PNR."); }
        });

        freeAllBtn.addActionListener(e -> {
            if (!isAdminMode) { JOptionPane.showMessageDialog(this, "Admin only."); return; }
            int ok = JOptionPane.showConfirmDialog(this, "Free all seats across trains?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (ok != JOptionPane.YES_OPTION) return;
            for (Train t : trains) for (String cname : t.classes.keySet()) Arrays.fill(t.seatsByClass.get(cname), false);
            bookings.clear(); refreshBookingsTable(); rebuildSeatGrid();
            JOptionPane.showMessageDialog(this, "Cleared all bookings & seats.");
        });

        refreshBookingsTable();
        return p;
    }

    private String toDisplayString(Booking b) {
        String passengers = b.passengers.stream().map(Object::toString).collect(Collectors.joining(", "));
        String seats = Arrays.stream(b.seats).mapToObj(i -> String.valueOf(i+1)).collect(Collectors.joining(","));
        return "PNR: " + b.pnr + "\nTrain: " + b.train.no + " " + b.train.name + "\nClass: " + b.trainClass + "\nSeats: " + seats + "\nPassengers: " + passengers + "\nDate: " + b.date + "\nFare: ₹" + b.totalFare;
    }

    private void refreshBookingsTable() {
        bookingsModel.setRowCount(0);
        for (Booking b : bookings) {
            String seatsStr = Arrays.stream(b.seats).mapToObj(i -> String.valueOf(i+1)).collect(Collectors.joining(","));
            String passengers = b.passengers.stream().map(Object::toString).collect(Collectors.joining("; "));
            bookingsModel.addRow(new Object[]{b.pnr, b.train.no + " " + b.train.name, b.trainClass, seatsStr, passengers, b.date.toString(), "₹" + b.totalFare});
        }
    }

    private void exportBookingsCsv() {
        try {
            Path out = Paths.get("bookings_export.csv");
            List<String> lines = new ArrayList<>();
            lines.add("PNR,Train,Class,Seats,Passengers,Date,Fare");
            for (Booking b : bookings) {
                String seatsStr = Arrays.stream(b.seats).mapToObj(i -> String.valueOf(i+1)).collect(Collectors.joining(";"));
                String pass = b.passengers.stream().map(Object::toString).collect(Collectors.joining("|"));
                lines.add(b.pnr + "," + b.train.no + " " + b.train.name + "," + b.trainClass + "," + seatsStr + "," + pass + "," + b.date + "," + b.totalFare);
            }
            Files.write(out, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            JOptionPane.showMessageDialog(this, "Exported to " + out.toAbsolutePath());
        } catch (Exception ex) { JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage()); }
    }

    private void showSearchDialog() {
        JDialog dlg = new JDialog(this, "Search PNR", true);
        dlg.setSize(360,220); dlg.setLocationRelativeTo(this); dlg.setLayout(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints(); g.insets = new Insets(8,8,8,8);
        g.gridx=0; g.gridy=0; dlg.add(new JLabel("Enter PNR:"), g);
        JTextField p = new JTextField(10); g.gridx=1; dlg.add(p, g);
        JButton find = new JButton("Find"); g.gridy=1; g.gridx=0; g.gridwidth=2; dlg.add(find, g);
        JLabel res = new JLabel(""); g.gridy=2; dlg.add(res, g);
        find.addActionListener(e -> {
            try { int pnr = Integer.parseInt(p.getText().trim()); Booking f=null; for (Booking b:bookings) if (b.pnr==pnr) { f=b; break; } if (f==null) res.setText("PNR not found."); else res.setText("<html>"+toDisplayString(f).replace("\n","<br>")+"</html>"); }
            catch(Exception ex){ res.setText("Invalid PNR."); }
        });
        dlg.setVisible(true);
    }

    private JPanel createCardPanel() {
        JPanel p = new JPanel(new BorderLayout()); p.setOpaque(false); p.setBorder(new EmptyBorder(12,12,12,12)); return p;
    }
    private Border createCardBorder() { return new CompoundBorder(new LineBorder(Color.LIGHT_GRAY,1,true), new EmptyBorder(10,10,10,10)); }
    private JLabel labeled(String text, Font font) { JLabel l = new JLabel(text); l.setFont(font); l.setForeground(MAT_TEXT); return l; }

    private class ChartPanel extends JPanel {
        ChartPanel() { setPreferredSize(new Dimension(480,200)); setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int total=0,taken=0;
            for (Train t:trains) for (String cname:t.classes.keySet()) { TrainClass tc=t.classes.get(cname); total+=tc.capacity(); boolean[] arr=t.seatsByClass.get(cname); for(boolean b:arr) if (b) taken++; }
            int avail = total - taken;
            Graphics2D g2 = (Graphics2D) g; g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cx=140,cy=100,r=80;
            double takenAngle = total==0?0:(taken*360.0/total);
            double availAngle = 360-takenAngle;
            g2.setColor(MAT_ACCENT); g2.fill(new Arc2D.Double(cx-r,cy-r,2*r,2*r,90,-takenAngle,Arc2D.PIE));
            g2.setColor(new Color(210,210,210)); g2.fill(new Arc2D.Double(cx-r,cy-r,2*r,2*r,90-takenAngle,-availAngle,Arc2D.PIE));
            g2.setColor(MAT_TEXT); g2.setFont(new Font("SansSerif",Font.BOLD,14)); g2.drawString("Occupancy",10,20);
            g2.setFont(new Font("SansSerif",Font.PLAIN,12)); g2.drawString("Taken: " + taken, 280, 70); g2.drawString("Available: " + avail, 280, 95); g2.drawString("Total seats: " + total, 280, 120);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> { RailwayAdvancedFull app = new RailwayAdvancedFull(); app.setVisible(true); });
    }
}
