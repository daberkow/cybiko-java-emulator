package com.github.daberkow.cybiko.manager.io;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persists emulator launch settings to ~/.cybiko-manager/emulator.properties.
 */
public final class EmulatorConfig {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".cybiko-manager");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("emulator.properties");

    private String emulatorJarPath = "";
    private String xtBootRom = "";
    private String xtFlashRom = "";
    private String v1BootRom = "";
    private String v1FlashRom = "";
    private String v2BootRom = "";
    private String v2FlashRom = "";
    private String radioMode = "off";
    private String radioId = "";
    private String sdrHost = "localhost";
    private String sdrPort = "19201";
    private boolean mute = false;
    private boolean headless = false;
    private boolean trace = false;
    private String logging = "";

    public EmulatorConfig() {}

    public String getEmulatorJarPath() { return emulatorJarPath; }
    public void setEmulatorJarPath(String v) { emulatorJarPath = v != null ? v : ""; }

    public String getXtBootRom() { return xtBootRom; }
    public void setXtBootRom(String v) { xtBootRom = v != null ? v : ""; }

    public String getXtFlashRom() { return xtFlashRom; }
    public void setXtFlashRom(String v) { xtFlashRom = v != null ? v : ""; }

    public String getV1BootRom() { return v1BootRom; }
    public void setV1BootRom(String v) { v1BootRom = v != null ? v : ""; }

    public String getV1FlashRom() { return v1FlashRom; }
    public void setV1FlashRom(String v) { v1FlashRom = v != null ? v : ""; }

    public String getV2BootRom() { return v2BootRom; }
    public void setV2BootRom(String v) { v2BootRom = v != null ? v : ""; }

    public String getV2FlashRom() { return v2FlashRom; }
    public void setV2FlashRom(String v) { v2FlashRom = v != null ? v : ""; }

    public String getRadioMode() { return radioMode; }
    public void setRadioMode(String v) { radioMode = v != null ? v : "off"; }

    public String getRadioId() { return radioId; }
    public void setRadioId(String v) { radioId = v != null ? v : ""; }

    public String getSdrHost() { return sdrHost; }
    public void setSdrHost(String v) { sdrHost = v != null ? v : "localhost"; }

    public String getSdrPort() { return sdrPort; }
    public void setSdrPort(String v) { sdrPort = v != null ? v : "19201"; }

    public boolean isMute() { return mute; }
    public void setMute(boolean v) { mute = v; }

    public boolean isHeadless() { return headless; }
    public void setHeadless(boolean v) { headless = v; }

    public boolean isTrace() { return trace; }
    public void setTrace(boolean v) { trace = v; }

    public String getLogging() { return logging; }
    public void setLogging(String v) { logging = v != null ? v : ""; }

    public static EmulatorConfig load() { return load(CONFIG_FILE); }

    public static EmulatorConfig load(Path configFile) {
        EmulatorConfig cfg = new EmulatorConfig();
        if (!Files.exists(configFile)) return cfg;

        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(configFile)) {
            props.load(reader);
        } catch (IOException e) {
            return cfg;
        }

        cfg.emulatorJarPath = props.getProperty("emulator.jar", "");
        cfg.xtBootRom = props.getProperty("xt.bootrom", "");
        cfg.xtFlashRom = props.getProperty("xt.flash", "");
        cfg.v1BootRom = props.getProperty("v1.bootrom", "");
        cfg.v1FlashRom = props.getProperty("v1.flash", "");
        cfg.v2BootRom = props.getProperty("v2.bootrom", "");
        cfg.v2FlashRom = props.getProperty("v2.flash", "");
        cfg.radioMode = props.getProperty("radio.mode", "off");
        cfg.radioId = props.getProperty("radio.id", "");
        cfg.sdrHost = props.getProperty("sdr.host", "localhost");
        cfg.sdrPort = props.getProperty("sdr.port", "19201");
        cfg.mute = Boolean.parseBoolean(props.getProperty("mute", "false"));
        cfg.headless = Boolean.parseBoolean(props.getProperty("headless", "false"));
        cfg.trace = Boolean.parseBoolean(props.getProperty("trace", "false"));
        cfg.logging = props.getProperty("logging", "");
        return cfg;
    }

    public static void save(EmulatorConfig cfg) throws IOException { save(cfg, CONFIG_FILE); }

    public static void save(EmulatorConfig cfg, Path configFile) throws IOException {
        Files.createDirectories(configFile.getParent());
        Properties props = new Properties();
        props.setProperty("emulator.jar", cfg.emulatorJarPath);
        props.setProperty("xt.bootrom", cfg.xtBootRom);
        props.setProperty("xt.flash", cfg.xtFlashRom);
        props.setProperty("v1.bootrom", cfg.v1BootRom);
        props.setProperty("v1.flash", cfg.v1FlashRom);
        props.setProperty("v2.bootrom", cfg.v2BootRom);
        props.setProperty("v2.flash", cfg.v2FlashRom);
        props.setProperty("radio.mode", cfg.radioMode);
        props.setProperty("radio.id", cfg.radioId);
        props.setProperty("sdr.host", cfg.sdrHost);
        props.setProperty("sdr.port", cfg.sdrPort);
        props.setProperty("mute", String.valueOf(cfg.mute));
        props.setProperty("headless", String.valueOf(cfg.headless));
        props.setProperty("trace", String.valueOf(cfg.trace));
        props.setProperty("logging", cfg.logging);
        try (Writer writer = Files.newBufferedWriter(configFile)) {
            props.store(writer, "Cybiko NVRAM Manager - Emulator Settings");
        }
    }
}
