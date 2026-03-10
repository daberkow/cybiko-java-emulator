package com.github.daberkow.cybiko.manager;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import com.github.daberkow.cybiko.manager.cfs.*;
import com.github.daberkow.cybiko.manager.io.EmulatorConfig;
import com.github.daberkow.cybiko.manager.io.EmulatorLauncher;
import com.github.daberkow.cybiko.manager.io.LibraryConfig;
import com.github.daberkow.cybiko.manager.io.LibraryScanner;
import com.github.daberkow.cybiko.manager.io.RecentFiles;
import com.github.daberkow.cybiko.manager.model.*;
import com.github.daberkow.cybiko.manager.model.ContentItem.LibraryItem;
import com.github.daberkow.cybiko.manager.model.ContentItem.NvramItem;
import com.github.daberkow.cybiko.manager.ui.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Callback;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Main application window layout.
 */
public class MainWindow extends StackPane {

    private static final DateTimeFormatter CSV_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Stage stage;
    private final BorderPane layout = new BorderPane();
    private final SidebarPane sidebar = new SidebarPane();
    private final ContentListPane contentList = new ContentListPane();
    private final DetailPane detail = new DetailPane();
    private final CapacityBar capacityBar = new CapacityBar();

    private CfsImage currentImage;
    private Path currentPath;

    private enum ViewMode { NVRAM, LIBRARY, SMART_LIST }
    private ViewMode currentViewMode = ViewMode.NVRAM;

    // Library state
    private List<LibraryFolder> libraryFolders = new ArrayList<>();
    private final Map<LibraryFolder, List<AppEntry>> libraryCache = new HashMap<>();
    private LibraryFolder currentLibraryFolder;
    private SidebarPane.SmartListType currentSmartList;

    // Recent files
    private List<Path> recentPaths = new ArrayList<>();
    private Menu recentMenu;

    // Emulator
    private EmulatorConfig emulatorConfig;

    // Theme
    private final String initialTheme;

    public MainWindow(Stage stage, String initialTheme) {
        this.stage = stage;
        this.initialTheme = initialTheme;

        layout.setTop(createMenuBar());
        layout.setLeft(sidebar);
        layout.setCenter(contentList);
        layout.setRight(detail);
        layout.setBottom(capacityBar);
        getChildren().add(layout);

        // Wire NVRAM selection
        sidebar.setOnSelectionChanged(entry -> {
            currentImage = entry.image();
            currentPath = entry.path();
            currentViewMode = ViewMode.NVRAM;
            currentLibraryFolder = null;
            currentSmartList = null;
            refreshFileList();
            detail.setNvramAvailable(true);
            updateTitle();
        });

        // Wire library selection
        sidebar.setOnLibrarySelected(folder -> {
            currentLibraryFolder = folder;
            currentSmartList = null;
            currentViewMode = ViewMode.LIBRARY;
            refreshLibraryView();
        });

        // Wire smart list selection
        sidebar.setOnSmartListSelected(this::showSmartList);

        // Wire drag-and-drop
        sidebar.setOnDropLibraryItems(this::handleDropOnNvram);

        sidebar.setOnAddLibraryFolder(this::addLibraryFolder);
        sidebar.setOnRemoveLibraryFolder(this::removeLibraryFolder);
        sidebar.setOnCloseNvram(this::closeNvram);
        sidebar.setOnLaunchEmulator(this::launchEmulator);

        // Wire content list selection
        contentList.setOnItemSelected(item -> detail.showItem(item));

        // Wire detail pane actions
        detail.setOnAddToNvram(this::addLibraryItemToNvram);
        detail.setOnRemoveFromNvram(this::removeNvramItem);
        detail.setOnViewHex(this::viewHex);
        detail.setOnLaunchEmulator(this::launchEmulator);

        // Load library config
        libraryFolders = LibraryConfig.load();
        sidebar.setLibraryFolders(libraryFolders);

        // Load recent files
        recentPaths = RecentFiles.load();
        rebuildRecentMenu();

        // Load emulator config
        emulatorConfig = EmulatorConfig.load();
    }

    private MenuBar createMenuBar() {
        // --- File menu ---
        Menu fileMenu = new Menu("File");

        MenuItem openItem = new MenuItem("Open...");
        openItem.setOnAction(e -> openFile());

        MenuItem newXtItem = new MenuItem("New Xtreme Image");
        newXtItem.setOnAction(e -> newImage(FlashGeometry.XTREME));

        MenuItem newV1Item = new MenuItem("New Classic V1 Image (AT45DB041)");
        newV1Item.setOnAction(e -> newImage(FlashGeometry.AT45DB041));

        MenuItem newV2Item = new MenuItem("New Classic V2 Image (AT45DB081)");
        newV2Item.setOnAction(e -> newImage(FlashGeometry.AT45DB081));

        MenuItem saveItem = new MenuItem("Save");
        saveItem.setOnAction(e -> saveFile());

        MenuItem saveAsItem = new MenuItem("Save As...");
        saveAsItem.setOnAction(e -> saveFileAs());

        recentMenu = new Menu("Open Recent");

        MenuItem closeItem = new MenuItem("Close");
        closeItem.setOnAction(e -> {
            SidebarPane.ImageEntry entry = sidebar.getSelectedEntry();
            if (entry != null) closeNvram(entry);
        });

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setOnAction(e -> stage.close());

        fileMenu.getItems().addAll(
            openItem,
            recentMenu,
            new SeparatorMenuItem(),
            newXtItem, newV1Item, newV2Item,
            new SeparatorMenuItem(),
            saveItem, saveAsItem,
            new SeparatorMenuItem(),
            closeItem,
            new SeparatorMenuItem(),
            exitItem
        );

        // --- Library menu ---
        Menu libraryMenu = new Menu("Library");

        MenuItem addFolderItem = new MenuItem("Add Folder...");
        addFolderItem.setOnAction(e -> addLibraryFolder());

        MenuItem removeFolderItem = new MenuItem("Remove Folder");
        removeFolderItem.setOnAction(e -> {
            LibraryFolder selected = sidebar.getSelectedLibraryFolder();
            if (selected != null) removeLibraryFolder(selected);
        });

        MenuItem refreshItem = new MenuItem("Refresh");
        refreshItem.setOnAction(e -> refreshLibrary());

        libraryMenu.getItems().addAll(addFolderItem, removeFolderItem, new SeparatorMenuItem(), refreshItem);

        // --- View menu ---
        Menu viewMenu = new Menu("View");

        CheckMenuItem lightModeItem = new CheckMenuItem("Light Mode");
        lightModeItem.setSelected("light".equals(initialTheme));
        lightModeItem.setOnAction(e -> {
            String theme = lightModeItem.isSelected() ? "light" : "dark";
            if ("light".equals(theme)) {
                Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
            } else {
                Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
            }
            try { LibraryConfig.saveTheme(theme); } catch (java.io.IOException ignored) {}
        });

        viewMenu.getItems().add(lightModeItem);

        // --- NVRAM menu ---
        Menu nvramMenu = new Menu("NVRAM");

        MenuItem addFileItem = new MenuItem("Add File...");
        addFileItem.setOnAction(e -> addFileToNvram());

        MenuItem removeSelectedItem = new MenuItem("Remove Selected");
        removeSelectedItem.setOnAction(e -> removeSelectedFromNvram());

        MenuItem validateItem = new MenuItem("Validate Integrity");
        validateItem.setOnAction(e -> validateIntegrity());

        MenuItem propertiesItem = new MenuItem("Properties...");
        propertiesItem.setOnAction(e -> showProperties());

        MenuItem exportCsvItem = new MenuItem("Export as CSV...");
        exportCsvItem.setOnAction(e -> exportCsv());

        MenuItem launchItem = new MenuItem("Launch Emulator...");
        launchItem.setOnAction(e -> launchEmulator());

        nvramMenu.getItems().addAll(
            addFileItem, removeSelectedItem,
            new SeparatorMenuItem(),
            validateItem, propertiesItem,
            new SeparatorMenuItem(),
            exportCsvItem,
            new SeparatorMenuItem(),
            launchItem
        );

        // --- Help menu ---
        Menu helpMenu = new Menu("Help");

        MenuItem aboutItem = new MenuItem("About");
        aboutItem.setOnAction(e -> showDialog(new AboutDialog()));

        helpMenu.getItems().add(aboutItem);

        return new MenuBar(fileMenu, libraryMenu, viewMenu, nvramMenu, helpMenu);
    }

    // ---- File operations ----

    private void openFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open CFS / NVRAM Image");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("All Supported", "*.bin", "*.nv", "*.nvram", "*"),
            new FileChooser.ExtensionFilter("NVRAM Images", "*.nvram"),
            new FileChooser.ExtensionFilter("Binary Files", "*.bin"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;

        try {
            Path path = file.toPath();
            CfsImage image = CfsReader.read(path);
            sidebar.addImage(path, image);
            currentImage = image;
            currentPath = path;
            currentViewMode = ViewMode.NVRAM;
            detail.setNvramAvailable(true);
            refreshFileList();
            addToRecentFiles(path);
            updateTitle();
        } catch (Exception ex) {
            showError("Failed to open image", ex.getMessage());
        }
    }

    private void openFilePath(Path path) {
        try {
            CfsImage image = CfsReader.read(path);
            sidebar.addImage(path, image);
            currentImage = image;
            currentPath = path;
            currentViewMode = ViewMode.NVRAM;
            currentSmartList = null;
            detail.setNvramAvailable(true);
            refreshFileList();
            addToRecentFiles(path);
            updateTitle();
        } catch (Exception ex) {
            showError("Failed to open image", ex.getMessage());
        }
    }

    private void closeNvram(SidebarPane.ImageEntry entry) {
        if (entry.image().isModified()) {
            String name = entry.path() != null ? entry.path().getFileName().toString() : entry.displayName();
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Unsaved Changes");
            alert.setHeaderText("Save changes to " + name + "?");
            alert.setContentText("Your changes will be lost if you don't save them.");

            ButtonType saveBtn = new ButtonType("Save");
            ButtonType dontSaveBtn = new ButtonType("Don't Save");
            ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(saveBtn, dontSaveBtn, cancelBtn);

            Optional<ButtonType> result = showDialog(alert);
            if (result.isEmpty() || result.get() == cancelBtn) return;
            if (result.get() == saveBtn) {
                currentImage = entry.image();
                currentPath = entry.path();
                sidebar.selectNvramEntry(entry.image());
                saveFile();
                if (entry.image().isModified()) return; // save failed or cancelled
            }
        }

        sidebar.removeImage(entry.image());
        if (currentImage == entry.image()) {
            currentImage = null;
            currentPath = null;
            contentList.clear();
            capacityBar.update(null, null);
            detail.showItem(null);
        }
        if (currentViewMode == ViewMode.SMART_LIST && currentSmartList != null) {
            showSmartList(currentSmartList);
        }
        updateTitle();
    }

    private void addToRecentFiles(Path path) {
        recentPaths = RecentFiles.addRecent(recentPaths, path);
        try {
            RecentFiles.save(recentPaths);
        } catch (Exception ignored) {}
        rebuildRecentMenu();
    }

    private void rebuildRecentMenu() {
        recentMenu.getItems().clear();
        if (recentPaths.isEmpty()) {
            recentMenu.setDisable(true);
            return;
        }
        recentMenu.setDisable(false);
        for (Path p : recentPaths) {
            MenuItem item = new MenuItem(p.toString());
            item.setOnAction(e -> openFilePath(p));
            recentMenu.getItems().add(item);
        }
        recentMenu.getItems().add(new SeparatorMenuItem());
        MenuItem clearItem = new MenuItem("Clear Recent");
        clearItem.setOnAction(e -> {
            recentPaths = new ArrayList<>();
            try {
                RecentFiles.save(recentPaths);
            } catch (Exception ignored) {}
            rebuildRecentMenu();
        });
        recentMenu.getItems().add(clearItem);
    }

    private void newImage(FlashGeometry geom) {
        TextInputDialog nameDialog = new TextInputDialog("Untitled.nvram");
        nameDialog.setTitle("New NVRAM Image");
        nameDialog.setHeaderText("Create new " + geom.name() + " image");
        nameDialog.setContentText("Image name:");
        Optional<String> nameResult = showDialog(nameDialog);
        if (nameResult.isEmpty()) return;

        String name = nameResult.get().strip();
        if (name.isEmpty()) name = "Untitled.nvram";

        CfsImage image = new CfsImage(geom);
        sidebar.addImage(null, image, name);
        currentImage = image;
        currentPath = null;
        currentViewMode = ViewMode.NVRAM;
        detail.setNvramAvailable(true);
        refreshFileList();
        updateTitle();
    }

    private void saveFile() {
        if (currentImage == null) return;
        if (currentPath == null) {
            saveFileAs();
            return;
        }
        try {
            CfsWriter.write(currentImage, currentPath);
            currentImage.clearModified();
            sidebar.clearChangeCounts(currentImage);
            updateTitle();
        } catch (Exception ex) {
            showError("Failed to save image", ex.getMessage());
        }
    }

    private void saveFileAs() {
        if (currentImage == null) return;

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save CFS Image As");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("NVRAM Image", "*.nvram"),
            new FileChooser.ExtensionFilter("Binary", "*.bin"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;

        // Auto-append selected extension if the filename has none
        String name = file.getName();
        if (!name.contains(".")) {
            FileChooser.ExtensionFilter selected = chooser.getSelectedExtensionFilter();
            if (selected != null && !selected.getExtensions().isEmpty()) {
                String ext = selected.getExtensions().get(0); // e.g. "*.nvram"
                if (ext.startsWith("*.") && !ext.equals("*.*")) {
                    file = new File(file.getParent(), name + ext.substring(1));
                }
            }
        }

        try {
            currentPath = file.toPath();
            CfsWriter.write(currentImage, currentPath);
            currentImage.clearModified();
            sidebar.clearChangeCounts(currentImage);
            sidebar.updateSelected(currentPath);
            addToRecentFiles(currentPath);
            updateTitle();
        } catch (Exception ex) {
            showError("Failed to save image", ex.getMessage());
        }
    }

    // ---- Library operations ----

    private void addLibraryFolder() {
        LibraryFolderDialog dialog = new LibraryFolderDialog(stage);
        showDialog(dialog).ifPresent(folder -> {
            libraryFolders.add(folder);
            sidebar.setLibraryFolders(libraryFolders);
            saveLibraryConfig();
            if (currentViewMode == ViewMode.SMART_LIST && currentSmartList != null) {
                showSmartList(currentSmartList);
            }
        });
    }

    private void removeLibraryFolder(LibraryFolder folder) {
        libraryFolders.remove(folder);
        libraryCache.remove(folder);
        sidebar.setLibraryFolders(libraryFolders);
        if (folder.equals(currentLibraryFolder)) {
            currentLibraryFolder = null;
            contentList.clear();
            detail.showItem(null);
        }
        saveLibraryConfig();
        if (currentViewMode == ViewMode.SMART_LIST && currentSmartList != null) {
            showSmartList(currentSmartList);
        }
    }

    private void refreshLibrary() {
        libraryCache.clear();
        if (currentViewMode == ViewMode.LIBRARY && currentLibraryFolder != null) {
            refreshLibraryView();
        } else if (currentViewMode == ViewMode.SMART_LIST && currentSmartList != null) {
            showSmartList(currentSmartList);
        }
    }

    private void refreshLibraryView() {
        if (currentLibraryFolder == null) return;

        List<AppEntry> entries = libraryCache.computeIfAbsent(
            currentLibraryFolder, LibraryScanner::scan
        );

        Map<String, List<String>> nvramFileMap = getNvramFileMap();
        contentList.setLibraryFiles(entries, currentLibraryFolder.label(), nvramFileMap);
        capacityBar.update(currentImage, currentImage != null ? currentImageName() : null);
        detail.showItem(null);
        detail.setNvramAvailable(currentImage != null);
    }

    private void saveLibraryConfig() {
        try {
            LibraryConfig.save(libraryFolders);
        } catch (Exception ex) {
            showError("Failed to save library config", ex.getMessage());
        }
    }

    /**
     * Pick which NVRAM image to target for an add operation.
     * Returns the image directly if only one is open, shows a picker if multiple,
     * or null if none are open or user cancelled.
     */
    private CfsImage pickTargetNvram() {
        List<SidebarPane.ImageEntry> entries = sidebar.getNvramEntries();
        if (entries.isEmpty()) {
            showError("No NVRAM Image", "Open or create an NVRAM image first.");
            return null;
        }
        if (entries.size() == 1) {
            return entries.get(0).image();
        }

        // Multiple NVRAMs open — use Dialog+ComboBox
        Dialog<SidebarPane.ImageEntry> dialog = new Dialog<>();
        dialog.setResizable(false);
        dialog.setTitle("Select NVRAM");
        dialog.setHeaderText("Multiple NVRAM images are open.");

        ComboBox<SidebarPane.ImageEntry> combo = new ComboBox<>();
        combo.getItems().addAll(entries);
        combo.setValue(entries.stream()
            .filter(e -> e.image() == currentImage).findFirst().orElse(entries.get(0)));
        combo.setMinWidth(Region.USE_PREF_SIZE);

        Label label = new Label("Add to:");
        javafx.scene.layout.HBox content = new javafx.scene.layout.HBox(8, label, combo);
        content.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        content.setPadding(new javafx.geometry.Insets(10));
        dialog.getDialogPane().setContent(content);

        ButtonType okBtn = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtn, cancelBtn);

        dialog.setResultConverter(btn -> btn == okBtn ? combo.getValue() : null);

        return showDialog(dialog).map(SidebarPane.ImageEntry::image).orElse(null);
    }

    // ---- NVRAM operations ----

    private void addFileToNvram() {
        CfsImage targetImage = pickTargetNvram();
        if (targetImage == null) return;

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add File to NVRAM");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("App Files", "*.app"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;

        try {
            byte[] data = Files.readAllBytes(file.toPath());
            String name = file.getName();
            if (!targetImage.addFile(name, data)) {
                showError("Add Failed", "Not enough space or filename too long.");
                return;
            }
            sidebar.recordAdd(targetImage);
            if (targetImage == currentImage) {
                afterNvramModification();
            } else {
                sidebar.refreshEntry(targetImage);
                updateTitle();
            }
        } catch (Exception ex) {
            showError("Failed to add file", ex.getMessage());
        }
    }

    private void removeSelectedFromNvram() {
        if (currentImage == null) return;

        List<ContentItem> selected = contentList.getSelectedItems();
        if (selected.isEmpty()) return;

        int count = 0;
        for (ContentItem item : selected) {
            if (item instanceof NvramItem ni) {
                currentImage.deleteFile(ni.file().name());
                count++;
            }
        }
        if (count > 0) {
            sidebar.recordRemove(currentImage, count);
            afterNvramModification();
        }
    }

    private void addLibraryItemToNvram(LibraryItem item) {
        CfsImage targetImage = pickTargetNvram();
        if (targetImage == null) return;
        addLibraryItemToImage(item, targetImage);
    }

    /** Core logic: add a library item to a specific NVRAM image. */
    private boolean addLibraryItemToImage(LibraryItem item, CfsImage targetImage) {
        try {
            byte[] data = Files.readAllBytes(item.entry().path());
            if (!targetImage.addFile(item.entry().name(), data)) {
                showError("Add Failed", "Not enough space or filename too long.");
                return false;
            }
            sidebar.recordAdd(targetImage);
            if (targetImage == currentImage) {
                afterNvramModification();
            } else {
                sidebar.refreshEntry(targetImage);
                updateTitle();
            }
            // Refresh library/smart list view to update "In NVRAM" status
            if (currentViewMode == ViewMode.LIBRARY) {
                refreshLibraryView();
            } else if (currentViewMode == ViewMode.SMART_LIST && currentSmartList != null) {
                showSmartList(currentSmartList);
            }
            return true;
        } catch (Exception ex) {
            showError("Failed to add file", ex.getMessage());
            return false;
        }
    }

    private void handleDropOnNvram(List<LibraryItem> items, SidebarPane.ImageEntry target) {
        CfsImage targetImage = target.image();
        int added = 0;
        for (LibraryItem item : items) {
            if (addLibraryItemToImage(item, targetImage)) added++;
        }
        if (added > 0) {
            sidebar.selectNvramEntry(targetImage);
        }
    }

    private void removeNvramItem(NvramItem item) {
        if (currentImage == null) return;

        currentImage.deleteFile(item.file().name());
        sidebar.recordRemove(currentImage, 1);
        afterNvramModification();
    }

    private void afterNvramModification() {
        if (currentViewMode == ViewMode.NVRAM) {
            refreshFileList();
        } else if (currentViewMode == ViewMode.SMART_LIST && currentSmartList != null) {
            showSmartList(currentSmartList);
        }
        sidebar.refreshSelectedEntry();
        capacityBar.update(currentImage, currentImageName());
        detail.showItem(null);
        updateTitle();
    }

    private void validateIntegrity() {
        if (currentImage == null) {
            showError("No NVRAM Image", "Open an NVRAM image first.");
            return;
        }

        CfsValidator.Result result = CfsValidator.validate(currentImage);

        if (result.isValid() && result.warningCount() == 0) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Integrity Check");
            alert.setHeaderText("Image Valid");
            alert.setContentText("All checks passed — checksums valid, block structure consistent, "
                + "no orphaned blocks. CyOS will accept this image without flash repair.");
            showDialog(alert);
            return;
        }

        // Build detailed report
        String report = buildValidationReport(result);

        Alert alert = new Alert(result.errorCount() > 0
            ? Alert.AlertType.ERROR : Alert.AlertType.WARNING);
        alert.setTitle("Integrity Check");
        alert.setHeaderText(result.errorCount() > 0
            ? "Errors Found — CyOS will trigger flash repair"
            : "Warnings Found");
        alert.setResizable(true);

        TextArea textArea = new TextArea(report);
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
        textArea.setPrefRowCount(20);
        textArea.setPrefColumnCount(60);

        alert.getDialogPane().setContent(textArea);

        if (result.errorCount() > 0) {
            ButtonType repairBtn = new ButtonType("Repair");
            ButtonType closeBtn = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(repairBtn, closeBtn);

            Optional<ButtonType> choice = showDialog(alert);
            if (choice.isPresent() && choice.get() == repairBtn) {
                performRepair();
            }
        } else {
            showDialog(alert);
        }
    }

    private void performRepair() {
        CfsValidator.RepairResult repairResult = CfsValidator.repair(currentImage);

        // Refresh UI
        afterNvramModification();

        // Re-validate to confirm
        CfsValidator.Result postCheck = CfsValidator.validate(currentImage);

        // Build repair report
        StringBuilder report = new StringBuilder();
        report.append("=== REPAIR COMPLETE ===\n\n");

        report.append("FILES PRESERVED (").append(repairResult.preserved().size()).append("):\n");
        if (repairResult.preserved().isEmpty()) {
            report.append("  (none)\n");
        } else {
            for (String f : repairResult.preserved()) {
                report.append("  ").append(f).append('\n');
            }
        }
        report.append('\n');

        if (!repairResult.removed().isEmpty()) {
            report.append("FILES REMOVED (").append(repairResult.removed().size()).append("):\n");
            for (String f : repairResult.removed()) {
                report.append("  ").append(f).append('\n');
            }
            report.append('\n');
        }

        report.append("ACTIONS TAKEN:\n");
        for (String a : repairResult.actions()) {
            report.append("  ").append(a).append('\n');
        }
        report.append('\n');

        report.append("POST-REPAIR VALIDATION: ");
        if (postCheck.isValid() && postCheck.warningCount() == 0) {
            report.append("PASSED — image is clean\n");
        } else {
            report.append(postCheck.errorCount()).append(" error(s), ")
                .append(postCheck.warningCount()).append(" warning(s) remaining\n");
        }

        report.append("\nNote: Changes are in memory. Use File > Save to write to disk.");

        Alert resultAlert = new Alert(Alert.AlertType.INFORMATION);
        resultAlert.setTitle("Repair Complete");
        resultAlert.setHeaderText("Flash image repaired");
        resultAlert.setResizable(true);

        TextArea textArea = new TextArea(report.toString());
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
        textArea.setPrefRowCount(20);
        textArea.setPrefColumnCount(60);

        resultAlert.getDialogPane().setContent(textArea);
        showDialog(resultAlert);
    }

    private String buildValidationReport(CfsValidator.Result result) {
        StringBuilder report = new StringBuilder();
        report.append(String.format("%d error(s), %d warning(s)\n\n",
            result.errorCount(), result.warningCount()));

        if (result.errorCount() > 0) {
            report.append("ERRORS (will trigger CyOS flash repair):\n");
            for (CfsValidator.Issue issue : result.issues()) {
                if (issue.severity() == CfsValidator.Severity.ERROR) {
                    report.append("  ").append(issue).append('\n');
                }
            }
            report.append('\n');
        }

        if (result.warningCount() > 0) {
            report.append("WARNINGS (suspicious but may not cause issues):\n");
            for (CfsValidator.Issue issue : result.issues()) {
                if (issue.severity() == CfsValidator.Severity.WARNING) {
                    report.append("  ").append(issue).append('\n');
                }
            }
        }

        return report.toString();
    }

    // ---- Emulator launch ----

    private void launchEmulator() {
        if (currentImage == null) {
            showError("No NVRAM Selected", "Please select an NVRAM image first.");
            return;
        }
        if (currentPath == null || currentImage.isModified()) {
            showError("Unsaved NVRAM",
                    "Please save the NVRAM image before launching the emulator.");
            return;
        }

        FlashGeometry geometry = currentImage.geometry();
        String machine = EmulatorLauncher.machineType(geometry);

        LaunchEmulatorDialog dialog = new LaunchEmulatorDialog(
                machine, currentPath, emulatorConfig, stage);
        Optional<LaunchEmulatorDialog.LaunchParams> result = showDialog(dialog);
        if (result.isEmpty()) return;

        LaunchEmulatorDialog.LaunchParams params = result.get();

        // Save all settings (ROM paths, JAR, options) for next time
        emulatorConfig = params.config();
        setRomPath(machine, true, params.bootRom().toString());
        setRomPath(machine, false, params.flashRom().toString());
        try { EmulatorConfig.save(emulatorConfig); } catch (IOException ignored) {}

        // Build command line and launch
        List<String> args = EmulatorLauncher.buildCommandLine(
                params.bootRom(), params.flashRom(), currentPath, geometry, emulatorConfig);
        try {
            EmulatorLauncher.launch(params.emulatorJar(), args);
        } catch (IOException ex) {
            showError("Failed to launch emulator", ex.getMessage());
        }
    }

    private void setRomPath(String machine, boolean isBootRom, String path) {
        if (isBootRom) {
            switch (machine) {
                case "v1" -> emulatorConfig.setV1BootRom(path);
                case "v2" -> emulatorConfig.setV2BootRom(path);
                default -> emulatorConfig.setXtBootRom(path);
            }
        } else {
            switch (machine) {
                case "v1" -> emulatorConfig.setV1FlashRom(path);
                case "v2" -> emulatorConfig.setV2FlashRom(path);
                default -> emulatorConfig.setXtFlashRom(path);
            }
        }
    }

    private void showProperties() {
        if (currentImage == null) {
            showError("No NVRAM Image", "Open an NVRAM image first.");
            return;
        }
        showDialog(new NvramPropertiesDialog(currentImage, currentPath));
    }

    private void exportCsv() {
        if (currentImage == null) {
            showError("No NVRAM Image", "Open an NVRAM image first.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export as CSV");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("CSV Files", "*.csv")
        );
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;

        try (PrintWriter pw = new PrintWriter(file)) {
            pw.println("Name,Extension,Size (bytes),Modified,Blocks,File ID");
            for (CfsFile f : currentImage.listFiles()) {
                String date;
                try {
                    date = f.lastModified().format(CSV_DATE_FMT);
                } catch (Exception e) {
                    date = "";
                }
                pw.printf("\"%s\",\"%s\",%d,\"%s\",%d,%d%n",
                    f.name().replace("\"", "\"\""),
                    f.extension(),
                    f.size(),
                    date,
                    f.blockCount(),
                    f.fileId()
                );
            }
        } catch (Exception ex) {
            showError("Failed to export CSV", ex.getMessage());
        }
    }

    // ---- Hex viewer ----

    private void viewHex(ContentItem item) {
        byte[] data;
        String title;
        if (item instanceof NvramItem ni) {
            data = ni.file().data();
            title = ni.file().name();
        } else if (item instanceof LibraryItem li) {
            try {
                data = Files.readAllBytes(li.entry().path());
                title = li.entry().name();
            } catch (Exception ex) {
                showError("Failed to read file", ex.getMessage());
                return;
            }
        } else {
            return;
        }

        HexViewerDialog hexViewer = new HexViewerDialog(title, data);
        hexViewer.show();
    }

    // ---- Smart lists ----

    private void showSmartList(SidebarPane.SmartListType type) {
        currentSmartList = type;
        currentViewMode = ViewMode.SMART_LIST;
        currentLibraryFolder = null;

        List<AppEntry> allEntries = getAllLibraryEntries();
        Map<String, List<String>> nvramFileMap = getNvramFileMap();

        List<AppEntry> filtered;
        switch (type) {
            case RECENTLY_ADDED -> {
                filtered = allEntries.stream()
                    .sorted(Comparator.comparing(AppEntry::lastModified).reversed())
                    .limit(20)
                    .toList();
            }
            case NOT_IN_NVRAM -> {
                Set<String> inNvram = nvramFileMap.keySet();
                filtered = allEntries.stream()
                    .filter(e -> !inNvram.contains(e.name()))
                    .toList();
            }
            default -> filtered = List.of();
        }

        contentList.setLibraryFiles(filtered, type.label(), nvramFileMap);
        capacityBar.update(currentImage, currentImage != null ? currentImageName() : null);
        detail.showItem(null);
        detail.setNvramAvailable(currentImage != null);
    }

    private List<AppEntry> getAllLibraryEntries() {
        List<AppEntry> all = new ArrayList<>();
        for (LibraryFolder folder : libraryFolders) {
            List<AppEntry> entries = libraryCache.computeIfAbsent(folder, LibraryScanner::scan);
            all.addAll(entries);
        }
        return all;
    }

    // ---- View refresh ----

    private void refreshFileList() {
        if (currentImage == null) {
            contentList.clear();
            capacityBar.update(null, null);
            detail.showItem(null);
            return;
        }

        List<CfsFile> files = currentImage.listFiles();
        String name = currentImageName();
        contentList.setNvramFiles(files, name);
        capacityBar.update(currentImage, name);
        detail.showItem(null);
    }

    /**
     * Returns a map from filename -> list of NVRAM display names that contain it.
     */
    private Map<String, List<String>> getNvramFileMap() {
        Map<String, List<String>> map = new HashMap<>();
        for (SidebarPane.ImageEntry entry : sidebar.getNvramEntries()) {
            String nvramName = entry.displayName();
            for (CfsFile f : entry.image().listFiles()) {
                map.computeIfAbsent(f.name(), k -> new ArrayList<>()).add(nvramName);
            }
        }
        return map;
    }

    // ---- Title and unsaved changes ----

    private void updateTitle() {
        StringBuilder title = new StringBuilder("Cybiko NVRAM Manager");
        if (currentPath != null) {
            title.append(" - ").append(currentPath.getFileName());
        } else if (currentImage != null) {
            SidebarPane.ImageEntry entry = sidebar.getSelectedEntry();
            String name = (entry != null) ? entry.displayName() : "Untitled";
            title.append(" - ").append(name);
        }
        if (currentImage != null && currentImage.isModified()) {
            title.append(" *");
        }
        stage.setTitle(title.toString());
    }

    /**
     * Check for unsaved changes and prompt user. Returns true if safe to close,
     * false if user cancelled.
     */
    public boolean confirmClose() {
        for (SidebarPane.ImageEntry entry : sidebar.getNvramEntries()) {
            if (entry.image().isModified()) {
                String name = entry.path() != null ? entry.path().getFileName().toString() : "Untitled";
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Unsaved Changes");
                alert.setHeaderText("Save changes to " + name + "?");
                alert.setContentText("Your changes will be lost if you don't save them.");

                ButtonType saveBtn = new ButtonType("Save");
                ButtonType dontSaveBtn = new ButtonType("Don't Save");
                ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(saveBtn, dontSaveBtn, cancelBtn);

                Optional<ButtonType> result = showDialog(alert);
                if (result.isEmpty() || result.get() == cancelBtn) {
                    return false; // User cancelled
                }
                if (result.get() == saveBtn) {
                    // Switch context to this image for saving
                    currentImage = entry.image();
                    currentPath = entry.path();
                    sidebar.selectNvramEntry(entry.image());
                    saveFile();
                    // If still modified (save failed or was cancelled), abort close
                    if (entry.image().isModified()) {
                        return false;
                    }
                }
                // Don't Save: continue to next
            }
        }
        return true;
    }

    public void showSearch() {
        contentList.showSearch();
    }

    private String currentImageName() {
        if (currentPath != null) return currentPath.getFileName().toString();
        SidebarPane.ImageEntry entry = sidebar.getSelectedEntry();
        return (entry != null) ? entry.displayName() : "Untitled";
    }

    /**
     * Show a dialog as an in-window overlay instead of a separate OS window.
     * On Wayland compositors (Cosmic, Mutter), opening a second top-level window
     * while the main window is maximized causes the compositor to un-maximize and
     * tile the windows. Since the compositor owns window placement, client-side
     * setMaximized() requests are ignored. The only fix is to never create a
     * second window: we render the DialogPane as a centered overlay inside the
     * main StackPane and use a nested event loop to preserve blocking semantics.
     */
    @SuppressWarnings("unchecked")
    private <T> Optional<T> showDialog(Dialog<T> dialog) {
        DialogPane pane = dialog.getDialogPane();

        // Create semi-transparent scrim overlay
        StackPane overlay = new StackPane();
        overlay.getStyleClass().add("dialog-overlay");

        // Size the dialog pane to its preferred size, centered in overlay
        pane.setMaxWidth(Region.USE_PREF_SIZE);
        pane.setMaxHeight(Region.USE_PREF_SIZE);
        overlay.getChildren().add(pane);

        Object loopKey = new Object();
        Callback<ButtonType, T> converter = dialog.getResultConverter();

        // Wire each button to exit the nested event loop with the converted result
        for (ButtonType bt : pane.getButtonTypes()) {
            Node btnNode = pane.lookupButton(bt);
            if (btnNode instanceof Button btn) {
                btn.setOnAction(e -> {
                    T result = converter != null ? converter.call(bt) : (T) bt;
                    Platform.exitNestedEventLoop(loopKey, result);
                });
            }
        }

        // ESC dismisses (same as cancel/close)
        pane.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                Platform.exitNestedEventLoop(loopKey, null);
                e.consume();
            }
        });

        // Show overlay on top of main content
        getChildren().add(overlay);
        pane.requestFocus();

        // Block until a button is clicked or ESC is pressed
        T result = (T) Platform.enterNestedEventLoop(loopKey);

        // Remove overlay
        getChildren().remove(overlay);

        return Optional.ofNullable(result);
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        showDialog(alert);
    }
}
