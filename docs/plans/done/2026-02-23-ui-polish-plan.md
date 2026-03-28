# UI Polish Implementation Plan — DONE

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Transform the NVRAM Manager UI from prototype-quality to a professional VS Code/JetBrains-style dark theme with proper panel hierarchy, discoverable search, hex viewer find, and clear button states.

**Architecture:** CSS-first approach — rewrite the theme to establish visual hierarchy, then make targeted Java changes for search bar UX, hex find, sidebar indicators, and button styling. All changes are in existing files.

**Tech Stack:** JavaFX CSS, Java 21, existing Gradle build (`./gradlew :manager:build`)

**Design doc:** `docs/plans/2026-02-23-ui-polish-design.md`

---

### Task 1: CSS Theme Rewrite

**Files:**
- Modify: `manager/src/main/resources/com/github/daberkow/cybiko/manager/css/dark-theme.css` (full rewrite)

**Step 1: Rewrite the CSS file**

Replace the entire contents of `dark-theme.css` with the new theme. Key design decisions:

- **3-depth backgrounds:** `#1e1e1e` (deep/content), `#252526` (panel/sidebar), `#2d2d2d` (elevated/headers)
- **Selection:** `#1a3a2a` green tint with `#3fb950` left border, NOT solid green. Text stays `#e6edf3`.
- **Hover:** `#2a2d2e` on all interactive rows
- **Buttons:** 3 tiers — `.action-button` (green fill), `.action-button-secondary` (outlined), `.action-button-danger` (red fill)
- **Typography:** section headers 10px uppercase `#6e7681`, primary 13px `#e6edf3`, secondary 12px `#8b949e`, muted 11px `#6e7681`
- **Scrollbars:** thin rounded thumb `#555`, hidden track arrows

```css
/* ===== ROOT ===== */
.root {
    -fx-base: #1e1e1e;
    -fx-background: #1e1e1e;
    -fx-control-inner-background: #1e1e1e;
    -fx-text-fill: #e6edf3;
    -fx-accent: #3fb950;
    -fx-focus-color: #3fb95044;
    -fx-faint-focus-color: transparent;
}

.label {
    -fx-text-fill: #e6edf3;
}

/* ===== MENU BAR ===== */
.menu-bar {
    -fx-background-color: #2d2d2d;
    -fx-border-color: transparent transparent #3c3c3c transparent;
}
.menu-bar .label {
    -fx-text-fill: #cccccc;
}
.menu-item {
    -fx-background-color: #2d2d2d;
}
.menu-item .label {
    -fx-text-fill: #cccccc;
}
.menu-item:focused {
    -fx-background-color: #094771;
}
.menu-item:focused .label {
    -fx-text-fill: #ffffff;
}
.context-menu {
    -fx-background-color: #2d2d2d;
    -fx-border-color: #3c3c3c;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 8, 0, 0, 2);
}

/* ===== TABLE VIEW ===== */
.table-view {
    -fx-background-color: #1e1e1e;
    -fx-table-header-border-color: #3c3c3c;
    -fx-border-color: transparent;
}
.table-view .column-header {
    -fx-background-color: #2d2d2d;
    -fx-border-color: transparent transparent #3c3c3c transparent;
}
.table-view .column-header .label {
    -fx-text-fill: #8b949e;
    -fx-font-weight: bold;
    -fx-font-size: 11px;
}
.table-view .filler {
    -fx-background-color: #2d2d2d;
}

.table-row-cell {
    -fx-background-color: #1e1e1e;
    -fx-text-fill: #e6edf3;
    -fx-border-color: transparent;
}
.table-row-cell:odd {
    -fx-background-color: #222222;
}
.table-row-cell:hover {
    -fx-background-color: #2a2d2e;
}
.table-row-cell:selected {
    -fx-background-color: #1a3a2a;
    -fx-text-fill: #e6edf3;
    -fx-border-color: transparent transparent transparent #3fb950;
    -fx-border-width: 0 0 0 2;
}
.table-row-cell:selected:odd {
    -fx-background-color: #1a3a2a;
}
.table-row-cell:selected .text {
    -fx-fill: #e6edf3;
}
.table-row-cell:empty {
    -fx-background-color: #1e1e1e;
}
.table-cell {
    -fx-text-fill: #e6edf3;
}
.table-cell .text {
    -fx-fill: #e6edf3;
}
.table-row-cell:selected .table-cell {
    -fx-text-fill: #e6edf3;
}
.table-row-cell:selected .table-cell .text {
    -fx-fill: #e6edf3;
}

/* ===== LIST VIEW ===== */
.list-view {
    -fx-background-color: #252526;
    -fx-border-color: transparent;
}
.list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #e6edf3;
}
.list-cell:filled:hover {
    -fx-background-color: #2a2d2e;
}
.list-cell:selected {
    -fx-background-color: #1a3a2a;
    -fx-text-fill: #e6edf3;
    -fx-border-color: transparent transparent transparent #3fb950;
    -fx-border-width: 0 0 0 2;
}

/* ===== SCROLL BARS ===== */
.scroll-bar {
    -fx-background-color: transparent;
}
.scroll-bar .track {
    -fx-background-color: transparent;
}
.scroll-bar .thumb {
    -fx-background-color: #555555;
    -fx-background-radius: 4;
    -fx-background-insets: 1;
}
.scroll-bar .thumb:hover {
    -fx-background-color: #777777;
}
.scroll-bar .increment-button,
.scroll-bar .decrement-button {
    -fx-background-color: transparent;
    -fx-padding: 0;
}
.scroll-bar .increment-arrow,
.scroll-bar .decrement-arrow {
    -fx-shape: "";
    -fx-padding: 0;
}

/* ===== PROGRESS BAR ===== */
.progress-bar .track {
    -fx-background-color: #3c3c3c;
    -fx-background-radius: 3;
}
.progress-bar .bar {
    -fx-background-color: #3fb950;
    -fx-background-insets: 0;
    -fx-background-radius: 3;
    -fx-padding: 3px;
}

/* ===== SPLIT PANE ===== */
.split-pane {
    -fx-background-color: #1e1e1e;
}
.split-pane-divider {
    -fx-background-color: #3c3c3c;
}

/* ===== TEXT FIELDS ===== */
.text-field {
    -fx-background-color: #1e1e1e;
    -fx-text-fill: #e6edf3;
    -fx-border-color: #3c3c3c;
    -fx-border-radius: 3;
    -fx-background-radius: 3;
    -fx-prompt-text-fill: #6e7681;
}
.text-field:focused {
    -fx-border-color: #3fb950;
}

/* ===== APP-SPECIFIC STYLES ===== */

/* Section headers (sidebar) */
.section-header {
    -fx-font-size: 10px;
    -fx-font-weight: bold;
    -fx-text-fill: #6e7681;
    -fx-padding: 12 12 6 12;
    -fx-text-transform: uppercase;
    -fx-border-color: transparent transparent #3c3c3c transparent;
}

/* Detail pane labels and values */
.detail-label {
    -fx-text-fill: #8b949e;
    -fx-font-size: 11px;
}
.detail-value {
    -fx-text-fill: #e6edf3;
    -fx-font-size: 13px;
}

/* Capacity bar */
.capacity-label {
    -fx-text-fill: #8b949e;
    -fx-font-size: 12px;
}

/* Breadcrumb header */
.breadcrumb {
    -fx-text-fill: #8b949e;
    -fx-font-size: 12px;
    -fx-padding: 8 12 8 12;
    -fx-border-color: transparent transparent #3c3c3c transparent;
    -fx-background-color: #252526;
}

/* Search field */
.search-field {
    -fx-background-color: #1e1e1e;
    -fx-text-fill: #e6edf3;
    -fx-border-color: #3c3c3c;
    -fx-border-radius: 3;
    -fx-background-radius: 3;
    -fx-prompt-text-fill: #6e7681;
    -fx-padding: 4 8 4 8;
}
.search-field:focused {
    -fx-border-color: #3fb950;
}

/* In-NVRAM badge */
.badge-in-nvram {
    -fx-background-color: #238636;
    -fx-text-fill: #ffffff;
    -fx-font-size: 10px;
    -fx-padding: 2 8 2 8;
    -fx-background-radius: 10;
}

/* Modified indicator dot */
.dot-saved {
    -fx-background-color: #3fb950;
    -fx-background-radius: 4;
    -fx-min-width: 8;
    -fx-min-height: 8;
    -fx-max-width: 8;
    -fx-max-height: 8;
}
.dot-modified {
    -fx-background-color: #d29922;
    -fx-background-radius: 4;
    -fx-min-width: 8;
    -fx-min-height: 8;
    -fx-max-width: 8;
    -fx-max-height: 8;
}

/* ===== BUTTONS ===== */

/* Primary (green fill) */
.action-button {
    -fx-background-color: #238636;
    -fx-text-fill: #ffffff;
    -fx-padding: 6 14 6 14;
    -fx-background-radius: 4;
    -fx-cursor: hand;
    -fx-font-size: 12px;
}
.action-button:hover {
    -fx-background-color: #2ea043;
}
.action-button:pressed {
    -fx-background-color: #1a7f37;
}
.action-button:disabled {
    -fx-background-color: #3c3c3c;
    -fx-text-fill: #6e7681;
}

/* Secondary (outlined) */
.action-button-secondary {
    -fx-background-color: transparent;
    -fx-text-fill: #cccccc;
    -fx-border-color: #3c3c3c;
    -fx-border-radius: 4;
    -fx-background-radius: 4;
    -fx-padding: 6 14 6 14;
    -fx-cursor: hand;
    -fx-font-size: 12px;
}
.action-button-secondary:hover {
    -fx-background-color: #2d2d2d;
    -fx-border-color: #555555;
}
.action-button-secondary:pressed {
    -fx-background-color: #3c3c3c;
}

/* Danger (red fill) */
.action-button-danger {
    -fx-background-color: #da3633;
    -fx-text-fill: #ffffff;
    -fx-padding: 6 14 6 14;
    -fx-background-radius: 4;
    -fx-cursor: hand;
    -fx-font-size: 12px;
}
.action-button-danger:hover {
    -fx-background-color: #f85149;
}
.action-button-danger:pressed {
    -fx-background-color: #b62324;
}

/* Sidebar "+" button */
.add-button {
    -fx-background-color: transparent;
    -fx-text-fill: #8b949e;
    -fx-font-size: 14px;
    -fx-font-weight: bold;
    -fx-padding: 4 8 4 8;
    -fx-cursor: hand;
}
.add-button:hover {
    -fx-text-fill: #e6edf3;
}

/* Search toggle button */
.search-toggle {
    -fx-background-color: transparent;
    -fx-text-fill: #8b949e;
    -fx-padding: 4 8 4 8;
    -fx-cursor: hand;
    -fx-font-size: 12px;
}
.search-toggle:hover {
    -fx-text-fill: #e6edf3;
}

/* Hex find bar */
.find-bar {
    -fx-background-color: #252526;
    -fx-padding: 6 8 6 8;
    -fx-border-color: transparent transparent #3c3c3c transparent;
}

/* Hex viewer cells */
.hex-viewer-cell {
    -fx-font-family: "monospace";
    -fx-font-size: 12px;
    -fx-text-fill: #e6edf3;
    -fx-background-color: #1e1e1e;
}
.hex-viewer-cell:selected {
    -fx-background-color: #1a3a2a;
    -fx-text-fill: #e6edf3;
}
.hex-viewer-cell:hover {
    -fx-background-color: #2a2d2e;
}
.hex-match {
    -fx-background-color: #5a3a1a;
    -fx-text-fill: #e6edf3;
}

/* ===== DIALOGS ===== */
.dialog-pane {
    -fx-background-color: #2d2d2d;
}
.dialog-pane .header-panel {
    -fx-background-color: #252526;
}
.dialog-pane .button-bar .container {
    -fx-background-color: #2d2d2d;
}
.dialog-pane .button {
    -fx-text-fill: #cccccc;
    -fx-background-color: #3c3c3c;
    -fx-background-radius: 4;
}
.dialog-pane .button:hover {
    -fx-background-color: #555555;
}

/* Default button in all contexts */
.button {
    -fx-text-fill: #cccccc;
}

/* ===== SIDEBAR PANEL ===== */
.sidebar {
    -fx-background-color: #252526;
    -fx-border-color: transparent #3c3c3c transparent transparent;
}

/* ===== DETAIL PANEL ===== */
.detail-panel {
    -fx-background-color: #252526;
    -fx-border-color: transparent transparent transparent #3c3c3c;
}

/* ===== CAPACITY BAR PANEL ===== */
.capacity-bar {
    -fx-background-color: #252526;
    -fx-border-color: #3c3c3c transparent transparent transparent;
}

/* ===== TOOLBAR ===== */
.hex-toolbar {
    -fx-background-color: #2d2d2d;
    -fx-border-color: transparent transparent #3c3c3c transparent;
    -fx-padding: 8;
}
```

**Step 2: Build to verify CSS compiles cleanly**

Run: `./gradlew :manager:build`
Expected: BUILD SUCCESSFUL (CSS is loaded at runtime, build just confirms no Java compilation errors)

**Step 3: Commit**

```bash
git add manager/src/main/resources/com/github/daberkow/cybiko/manager/css/dark-theme.css
git commit -m "Rewrite dark theme CSS: 3-depth panel hierarchy, VS Code-style selection, button tiers"
```

---

### Task 2: SidebarPane — Panel Background, Modified Dot, Section Borders

**Files:**
- Modify: `manager/src/main/java/com/github/daberkow/cybiko/manager/ui/SidebarPane.java`

**Step 1: Add sidebar style class and modified dot to NVRAM cells**

In `SidebarPane()` constructor:

1. Add `getStyleClass().add("sidebar")` at the top of constructor (after `setMinWidth(150)`, line 63)
2. In the NVRAM cell factory (line 70-111), add a status dot (Region node) to each cell. The dot shows green when `image.isModified()` is false, orange when true. Place it in an HBox with the existing VBox.

Replace the cell factory's `updateItem` body (lines 74-95) with code that:
- Creates an HBox with spacing 8
- Adds a Region dot (8x8) with style class `dot-saved` or `dot-modified` based on `item.image().isModified()`
- Adds the existing VBox (name + info) to the HBox
- Sets the HBox as the graphic

```java
// Inside updateItem, after the null check:
HBox row = new HBox(8);
row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

Region dot = new Region();
dot.getStyleClass().add(item.image().isModified() ? "dot-modified" : "dot-saved");
dot.setMinSize(8, 8);
dot.setMaxSize(8, 8);

VBox box = new VBox(2);
Label name = new Label(item.displayName());
name.getStyleClass().add("detail-value");

StringBuilder infoText = new StringBuilder(item.sizeInfo());
int[] counts = changeCounts.get(item.image());
if (counts != null && (counts[0] > 0 || counts[1] > 0)) {
    infoText.append("  ");
    if (counts[0] > 0) infoText.append("+").append(counts[0]);
    if (counts[0] > 0 && counts[1] > 0) infoText.append(" ");
    if (counts[1] > 0) infoText.append("-").append(counts[1]);
}
Label info = new Label(infoText.toString());
info.getStyleClass().add("detail-label");
box.getChildren().addAll(name, info);

row.getChildren().addAll(dot, box);
setGraphic(row);
```

Add import for `javafx.scene.layout.Region` at the top of the file.

**Step 2: Build and verify**

Run: `./gradlew :manager:build`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add manager/src/main/java/com/github/daberkow/cybiko/manager/ui/SidebarPane.java
git commit -m "SidebarPane: add sidebar style class, modified indicator dot"
```

---

### Task 3: DetailPane — Button Style Classes

**Files:**
- Modify: `manager/src/main/java/com/github/daberkow/cybiko/manager/ui/DetailPane.java`

**Step 1: Update button style classes and add detail-panel class**

1. Add `getStyleClass().add("detail-panel")` at top of constructor (after `setPadding`, line 51)
2. Change `viewHexBtn` style class from `"action-button"` to `"action-button-secondary"` (line 85)

That's it — `addToNvramBtn` already uses `action-button` (primary green) and `removeFromNvramBtn` already uses `action-button-danger`. The CSS handles the rest.

**Step 2: Build and verify**

Run: `./gradlew :manager:build`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add manager/src/main/java/com/github/daberkow/cybiko/manager/ui/DetailPane.java
git commit -m "DetailPane: secondary button style for View Hex, add detail-panel class"
```

---

### Task 4: CapacityBar — File Count, Panel Style

**Files:**
- Modify: `manager/src/main/java/com/github/daberkow/cybiko/manager/ui/CapacityBar.java`

**Step 1: Add panel style class and file count to label**

1. Add `getStyleClass().add("capacity-bar")` at top of constructor (after `setPadding`, line 23)
2. In `update(CfsImage image)`, change the usage label format (line 50-51) to include file count:

```java
int fileCount = image.listFiles().size();
usageLabel.setText(String.format("%d files | %d KB / %d KB (%d%%)",
    fileCount, used / 1024, total / 1024, Math.round(ratio * 100)));
```

Add import for nothing new — `CfsImage.listFiles()` already returns `List<CfsFile>`.

**Step 2: Build and verify**

Run: `./gradlew :manager:build`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add manager/src/main/java/com/github/daberkow/cybiko/manager/ui/CapacityBar.java
git commit -m "CapacityBar: add file count to label, capacity-bar style class"
```

---

### Task 5: ContentListPane — Collapsible Search Bar

**Files:**
- Modify: `manager/src/main/java/com/github/daberkow/cybiko/manager/ui/ContentListPane.java`

**Step 1: Restructure search into collapsible bar with toggle**

Replace the current breadcrumb + always-visible searchField with:
- An HBox `headerBar` containing: breadcrumb label (left, grows) + search toggle button (right)
- When the search toggle is clicked (or Ctrl+F), a search HBox slides in below the header containing: search TextField + close "X" button
- Escape on the search field or clicking X hides the search bar and clears the filter

Key changes:

1. Replace the `searchField` field with a full search bar setup:

```java
private final Label breadcrumb = new Label("No image loaded");
private final TextField searchField = new TextField();
private final Button searchToggle = new Button("Search");
private final Button searchClose = new Button("X");
private final HBox headerBar = new HBox(8);
private final HBox searchBar = new HBox(8);
private boolean searchVisible = false;
```

2. In constructor, build the header bar:

```java
breadcrumb.getStyleClass().add("breadcrumb");
breadcrumb.setMaxWidth(Double.MAX_VALUE);
HBox.setHgrow(breadcrumb, Priority.ALWAYS);

searchToggle.getStyleClass().add("search-toggle");
searchToggle.setOnAction(e -> toggleSearch());

headerBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
headerBar.getChildren().addAll(breadcrumb, searchToggle);
```

3. Build the search bar (initially not added to children):

```java
searchField.setPromptText("Search...");
searchField.getStyleClass().add("search-field");
searchField.textProperty().addListener((obs, oldVal, newVal) -> {
    String filter = newVal == null ? "" : newVal.toLowerCase();
    filteredItems.setPredicate(item ->
        filter.isEmpty() || item.name().toLowerCase().contains(filter)
    );
});
searchField.setOnKeyPressed(e -> {
    if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) toggleSearch();
});
HBox.setHgrow(searchField, Priority.ALWAYS);

searchClose.getStyleClass().add("search-toggle");
searchClose.setOnAction(e -> toggleSearch());

searchBar.setPadding(new javafx.geometry.Insets(4, 12, 4, 12));
searchBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
searchBar.getChildren().addAll(searchField, searchClose);
```

4. Change `getChildren().addAll(breadcrumb, searchField, table)` to:

```java
getChildren().addAll(headerBar, table);
```

5. Add `toggleSearch()` method:

```java
private void toggleSearch() {
    searchVisible = !searchVisible;
    if (searchVisible) {
        if (!getChildren().contains(searchBar)) {
            getChildren().add(1, searchBar); // between header and table
        }
        searchField.requestFocus();
    } else {
        getChildren().remove(searchBar);
        searchField.clear();
    }
}
```

6. Add public `showSearch()` method for Ctrl+F from MainWindow:

```java
public void showSearch() {
    if (!searchVisible) toggleSearch();
    else searchField.requestFocus();
}
```

7. In `clear()`, also hide search:

```java
public void clear() {
    items.clear();
    breadcrumb.setText("No image loaded");
    if (searchVisible) toggleSearch();
}
```

Add imports: `javafx.scene.layout.HBox`, `javafx.geometry.Pos`, `javafx.geometry.Insets` (some may already be imported via wildcard).

**Step 2: Build and verify**

Run: `./gradlew :manager:build`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add manager/src/main/java/com/github/daberkow/cybiko/manager/ui/ContentListPane.java
git commit -m "ContentListPane: collapsible search bar with toggle button and Escape dismiss"
```

---

### Task 6: MainWindow — Wire Ctrl+F to Search

**Files:**
- Modify: `manager/src/main/java/com/github/daberkow/cybiko/manager/MainWindow.java`
- Modify: `manager/src/main/java/com/github/daberkow/cybiko/manager/App.java`

**Step 1: Add Ctrl+F accelerator in App.java**

In `App.java`, after `primaryStage.setScene(scene)` (line 22), add a keyboard shortcut:

```java
scene.getAccelerators().put(
    new javafx.scene.input.KeyCodeCombination(
        javafx.scene.input.KeyCode.F,
        javafx.scene.input.KeyCombination.SHORTCUT_DOWN),
    mainWindow::showSearch
);
```

**Step 2: Add showSearch() to MainWindow**

Add a public method to MainWindow:

```java
public void showSearch() {
    contentList.showSearch();
}
```

**Step 3: Build and verify**

Run: `./gradlew :manager:build`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add manager/src/main/java/com/github/daberkow/cybiko/manager/App.java \
       manager/src/main/java/com/github/daberkow/cybiko/manager/MainWindow.java
git commit -m "Wire Ctrl+F to collapsible search bar"
```

---

### Task 7: HexViewerDialog — Find Bar with Hex/ASCII Search

**Files:**
- Modify: `manager/src/main/java/com/github/daberkow/cybiko/manager/ui/HexViewerDialog.java`

**Step 1: Add find bar with hex/ASCII search, next/previous navigation**

Add these fields to the class:

```java
private final HBox findBar = new HBox(8);
private final TextField findField = new TextField();
private final ToggleButton hexModeBtn = new ToggleButton("Hex");
private final Label matchLabel = new Label();
private final List<Integer> matchRows = new ArrayList<>();
private int matchIndex = -1;
private boolean findBarVisible = false;
```

Build the find bar in the constructor (after the existing toolbar setup, before creating the VBox root):

```java
// Find bar
findField.setPromptText("Search hex (A0 FF) or text...");
findField.getStyleClass().add("search-field");
HBox.setHgrow(findField, Priority.ALWAYS);
findField.setOnAction(e -> findNext());
findField.setOnKeyPressed(e -> {
    if (e.getCode() == KeyCode.ESCAPE) toggleFindBar();
});

hexModeBtn.setSelected(true);
hexModeBtn.getStyleClass().add("action-button-secondary");

Button findPrevBtn = new Button("<");
findPrevBtn.getStyleClass().add("action-button-secondary");
findPrevBtn.setOnAction(e -> findPrev());

Button findNextBtn = new Button(">");
findNextBtn.getStyleClass().add("action-button-secondary");
findNextBtn.setOnAction(e -> findNext());

Button findCloseBtn = new Button("X");
findCloseBtn.getStyleClass().add("search-toggle");
findCloseBtn.setOnAction(e -> toggleFindBar());

matchLabel.getStyleClass().add("detail-label");

findBar.getStyleClass().add("find-bar");
findBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
findBar.getChildren().addAll(findField, hexModeBtn, findPrevBtn, findNextBtn, matchLabel, findCloseBtn);
```

Change the VBox root to NOT include findBar by default — just `toolbar` and `listView`:

```java
VBox root = new VBox(toolbar, listView);
```

Add Ctrl+F accelerator (alongside existing Ctrl+C):

```java
scene.getAccelerators().put(
    new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN),
    this::toggleFindBar
);
```

Add the find methods:

```java
private void toggleFindBar() {
    findBarVisible = !findBarVisible;
    VBox root = (VBox) getScene().getRoot();
    if (findBarVisible) {
        if (!root.getChildren().contains(findBar)) {
            root.getChildren().add(1, findBar); // between toolbar and list
        }
        findField.requestFocus();
    } else {
        root.getChildren().remove(findBar);
        findField.clear();
        matchRows.clear();
        matchIndex = -1;
        matchLabel.setText("");
        listView.refresh(); // clear highlights
    }
}

private void findNext() {
    performSearch();
    if (matchRows.isEmpty()) return;
    matchIndex = (matchIndex + 1) % matchRows.size();
    goToMatch();
}

private void findPrev() {
    performSearch();
    if (matchRows.isEmpty()) return;
    matchIndex = matchIndex <= 0 ? matchRows.size() - 1 : matchIndex - 1;
    goToMatch();
}

private void goToMatch() {
    int row = matchRows.get(matchIndex);
    listView.scrollTo(row);
    listView.getSelectionModel().clearSelection();
    listView.getSelectionModel().select(row);
    matchLabel.setText((matchIndex + 1) + " / " + matchRows.size());
}

private void performSearch() {
    String query = findField.getText();
    if (query == null || query.isBlank()) {
        matchRows.clear();
        matchLabel.setText("");
        return;
    }

    matchRows.clear();
    matchIndex = -1;

    if (hexModeBtn.isSelected()) {
        // Parse hex bytes
        byte[] pattern = parseHexPattern(query);
        if (pattern == null || pattern.length == 0) return;
        for (int i = 0; i <= data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) { match = false; break; }
            }
            if (match) matchRows.add(i / BYTES_PER_ROW);
        }
    } else {
        // ASCII search
        byte[] pattern = query.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i <= data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) { match = false; break; }
            }
            if (match) matchRows.add(i / BYTES_PER_ROW);
        }
    }

    // Deduplicate rows (multiple matches in same row)
    java.util.LinkedHashSet<Integer> unique = new java.util.LinkedHashSet<>(matchRows);
    matchRows.clear();
    matchRows.addAll(unique);

    matchLabel.setText(matchRows.isEmpty() ? "No matches" : matchRows.size() + " matches");
}

private byte[] parseHexPattern(String hex) {
    String cleaned = hex.replaceAll("[\\s,]+", "");
    if (cleaned.length() % 2 != 0) return null;
    byte[] result = new byte[cleaned.length() / 2];
    try {
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    } catch (NumberFormatException e) {
        return null;
    }
}
```

Add imports: `java.util.ArrayList`, `javafx.scene.control.ToggleButton`, `java.nio.charset.StandardCharsets` (some may already be present).

**Step 2: Build and verify**

Run: `./gradlew :manager:build`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add manager/src/main/java/com/github/daberkow/cybiko/manager/ui/HexViewerDialog.java
git commit -m "HexViewerDialog: add find bar with hex/ASCII search, next/prev navigation"
```

---

### Task 8: HexViewerDialog — Style Toolbar Buttons as Secondary

**Files:**
- Modify: `manager/src/main/java/com/github/daberkow/cybiko/manager/ui/HexViewerDialog.java`

**Step 1: Apply secondary button style and toolbar class**

1. Change `goBtn` (line 66-68 area) to use secondary style:
```java
goBtn.getStyleClass().add("action-button-secondary");
```

2. Change `copyBtn` style from `"action-button"` to `"action-button-secondary"` (line 71)

3. Add toolbar style class (line 77-79 area):
```java
toolbar.getStyleClass().add("hex-toolbar");
```

**Step 2: Build and verify**

Run: `./gradlew :manager:build`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add manager/src/main/java/com/github/daberkow/cybiko/manager/ui/HexViewerDialog.java
git commit -m "HexViewerDialog: secondary button styles, toolbar panel class"
```

---

### Task 9: Full Build Verification and Visual Test

**Step 1: Run full build**

Run: `./gradlew :manager:build`
Expected: BUILD SUCCESSFUL with all 198 tests passing

**Step 2: Launch and visually verify**

Run: `./gradlew :manager:run`

Check:
- [ ] Sidebar has distinct darker background with right border
- [ ] Content area has deepest background
- [ ] Detail pane has panel background with left border
- [ ] Capacity bar has top border
- [ ] Table row selection is subtle green tint, NOT solid green
- [ ] Table rows have hover state
- [ ] Section headers are small caps with bottom border
- [ ] "View Hex" button looks clearly clickable (outlined)
- [ ] "Add to NVRAM" is green filled, "Remove" is red filled
- [ ] Scrollbars are thin and rounded
- [ ] Search is hidden by default, click button or Ctrl+F to show
- [ ] Escape hides search and clears filter
- [ ] Hex viewer: buttons are clearly styled
- [ ] Hex viewer: Ctrl+F opens find bar
- [ ] Hex find: type hex bytes, next/prev works
- [ ] Hex find: toggle to ASCII mode works
- [ ] Hex find: Escape closes find bar
- [ ] NVRAM entries show green/orange dot for saved/modified state

**Step 3: Commit any final tweaks**

```bash
git add -A
git commit -m "UI polish: final visual tweaks from manual testing"
```
