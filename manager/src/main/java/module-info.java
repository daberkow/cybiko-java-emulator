module com.github.daberkow.cybiko.manager {
    requires javafx.controls;

    exports com.github.daberkow.cybiko.manager;
    exports com.github.daberkow.cybiko.manager.cfs;
    exports com.github.daberkow.cybiko.manager.io;
    exports com.github.daberkow.cybiko.manager.model;
    exports com.github.daberkow.cybiko.manager.ui;

    opens com.github.daberkow.cybiko.manager to javafx.graphics;
}
