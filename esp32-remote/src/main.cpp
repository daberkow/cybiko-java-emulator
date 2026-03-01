#include <Arduino.h>
#include <WiFi.h>
#include <Wire.h>
#include <TFT_eSPI.h>
#include "config.h"

// WiFi credentials — set via build flags or override here
#ifndef WIFI_SSID
#define WIFI_SSID "your_ssid"
#endif
#ifndef WIFI_PASS
#define WIFI_PASS "your_password"
#endif
#ifndef SERVER_IP
#define SERVER_IP "192.168.1.100"
#endif
#ifndef SERVER_PORT
#define SERVER_PORT 6502
#endif

static TFT_eSPI tft;
static WiFiClient client;

// VRAM buffer (4000 bytes = 160x100 at 2bpp)
static uint8_t vram_buf[4000];

// Display buffer in PSRAM (320x200 pixels, RGB565)
static uint16_t *display_buf;

// Grayscale LUT: 4 shades -> RGB565
// Matching the Cybiko's 2-bit palette (0=white, 3=black)
static const uint16_t gray_lut[4] = {
    0xFFFF, // 0: white
    0xAD55, // 1: light gray
    0x5AAB, // 2: dark gray
    0x0000  // 3: black
};

// Keyboard ASCII -> Cybiko matrix mapping (from C emulator)
typedef struct {
    uint8_t  ascii;
    uint8_t  col;
    uint16_t mask;
    bool     is_fn_combo;  // true = needs Fn held
} key_map_entry_t;

static const key_map_entry_t key_map[] = {
    // Letters
    {'a', 3, 0x0004, false}, {'b', 1, 0x0004, false}, {'c', 2, 0x0200, false},
    {'d', 2, 0x0100, false}, {'e', 3, 0x0080, false}, {'f', 2, 0x1000, false},
    {'g', 1, 0x0002, false}, {'h', 1, 0x0010, false}, {'i', 0, 0x1000, false},
    {'j', 1, 0x0080, false}, {'k', 0, 0x0800, false}, {'l', 0, 0x4000, false},
    {'m', 0, 0x0100, false}, {'n', 1, 0x0008, false}, {'o', 0, 0x2000, false},
    {'p', 9, 0x0010, false}, {'q', 3, 0x0002, false}, {'r', 2, 0x2000, false},
    {'s', 3, 0x0020, false}, {'t', 2, 0x4000, false}, {'u', 1, 0x0040, false},
    {'v', 2, 0x0800, false}, {'w', 3, 0x0040, false}, {'x', 3, 0x0010, false},
    {'y', 1, 0x0020, false}, {'z', 3, 0x0008, false},
    // Numbers (Fn+letter combos on XT)
    {'1', 3, 0x0002, true},  {'2', 3, 0x0040, true},  {'3', 3, 0x0080, true},
    {'4', 2, 0x2000, true},  {'5', 2, 0x4000, true},  {'6', 1, 0x0020, true},
    {'7', 1, 0x0040, true},  {'8', 0, 0x1000, true},  {'9', 0, 0x2000, true},
    {'0', 9, 0x0010, true},
    // Control keys
    {'\n', 4, 0x0008, false},  // Enter
    {'\b', 5, 0x0100, false},  // Backspace/Del
    {' ',  4, 0x0040, false},  // Space
    {'\t', 5, 0x0080, false},  // Tab
    {0x1B, 5, 0x0400, false},  // Escape
    {'.', 9, 0x0002, false},   // Period
    {';', 9, 0x0008, false},   // Semicolon
};
static const int KEY_MAP_SIZE = sizeof(key_map) / sizeof(key_map[0]);

// Key hold state
static uint8_t  held_col = 0;
static uint16_t held_mask = 0;
static int      held_frames = 0;
static bool     fn_held = false;

// --- Network ---

static void send_key_event(uint8_t msg_type, uint8_t col, uint16_t mask) {
    if (!client.connected()) return;
    uint8_t buf[4] = {msg_type, col, (uint8_t)(mask & 0xFF), (uint8_t)(mask >> 8)};
    client.write(buf, 4);
}

static bool connect_to_server() {
    Serial.printf("Connecting to %s:%d...\n", SERVER_IP, SERVER_PORT);
    if (client.connect(SERVER_IP, SERVER_PORT)) {
        client.setNoDelay(true);
        Serial.println("Connected!");
        return true;
    }
    Serial.println("Connection failed");
    return false;
}

// --- Display ---

static void render_vram() {
    // Convert 2-bit packed VRAM to RGB565 with 2x upscaling
    // VRAM is bottom-to-top, left-to-right (byte 0 = row 99)
    for (int vy = 0; vy < CYBIKO_H; vy++) {
        int screen_y = vy; // VRAM row 0 = screen bottom (row 99), but we invert
        int vram_row = (CYBIKO_H - 1 - vy);
        for (int vx = 0; vx < CYBIKO_W / 4; vx++) {
            uint8_t byte_val = vram_buf[vram_row * (CYBIKO_W / 4) + vx];
            for (int px = 0; px < 4; px++) {
                int shade = (byte_val >> (6 - px * 2)) & 0x03;
                uint16_t color = gray_lut[shade];
                int sx = (vx * 4 + px) * SCALE;
                int sy = screen_y * SCALE;
                // 2x2 pixel block
                for (int dy = 0; dy < SCALE; dy++) {
                    for (int dx = 0; dx < SCALE; dx++) {
                        display_buf[(sy + dy) * SCALED_W + sx + dx] = color;
                    }
                }
            }
        }
    }
    tft.pushImage(0, 20, SCALED_W, SCALED_H, display_buf); // 20px top border
}

// --- Keyboard ---

static void poll_keyboard() {
    Wire.requestFrom(KB_I2C_ADDR, 1);
    if (Wire.available()) {
        uint8_t ch = Wire.read();
        if (ch == 0) return; // no key

        // Find in map
        for (int i = 0; i < KEY_MAP_SIZE; i++) {
            if (key_map[i].ascii == ch) {
                // Release previous key if held
                if (held_mask != 0) {
                    send_key_event(MSG_KEY_UP, held_col, held_mask);
                    if (fn_held) {
                        send_key_event(MSG_KEY_UP, 7, 0x8000); // release Fn
                        fn_held = false;
                    }
                }
                // Press new key
                if (key_map[i].is_fn_combo) {
                    send_key_event(MSG_KEY_DOWN, 7, 0x8000); // press Fn
                    fn_held = true;
                    delay(50); // brief delay for CyOS to see Fn
                }
                send_key_event(MSG_KEY_DOWN, key_map[i].col, key_map[i].mask);
                held_col = key_map[i].col;
                held_mask = key_map[i].mask;
                held_frames = 4; // hold for ~67ms
                return;
            }
        }
    }
}

static void poll_trackball() {
    // Trackball pins are active LOW
    struct { int pin; uint8_t col; uint16_t mask; } dirs[] = {
        {PIN_TBALL_UP,    6, 0x0800},
        {PIN_TBALL_DOWN,  6, 0x2000},
        {PIN_TBALL_LEFT,  6, 0x4000},
        {PIN_TBALL_RIGHT, 6, 0x1000},
        {PIN_TBALL_CLICK, 4, 0x0010}, // Select
    };
    for (auto &d : dirs) {
        if (digitalRead(d.pin) == LOW) {
            send_key_event(MSG_KEY_DOWN, d.col, d.mask);
            delay(100);
            send_key_event(MSG_KEY_UP, d.col, d.mask);
        }
    }
}

// --- Arduino setup/loop ---

void setup() {
    Serial.begin(115200);
    Serial.println("Cybiko Remote Display");

    // Allocate display buffer in PSRAM
    display_buf = (uint16_t *)ps_malloc(SCALED_W * SCALED_H * sizeof(uint16_t));
    if (!display_buf) {
        Serial.println("FATAL: PSRAM alloc failed");
        while (1) delay(1000);
    }

    // Init I2C for keyboard
    Wire.begin(PIN_KB_SDA, PIN_KB_SCL);
    delay(500); // keyboard startup time

    // Init trackball
    pinMode(PIN_TBALL_UP, INPUT_PULLUP);
    pinMode(PIN_TBALL_DOWN, INPUT_PULLUP);
    pinMode(PIN_TBALL_LEFT, INPUT_PULLUP);
    pinMode(PIN_TBALL_RIGHT, INPUT_PULLUP);
    pinMode(PIN_TBALL_CLICK, INPUT_PULLUP);

    // Init TFT
    tft.init();
    tft.setRotation(1);
    tft.fillScreen(TFT_BLACK);
    tft.setSwapBytes(true);
    pinMode(PIN_TFT_BL, OUTPUT);
    digitalWrite(PIN_TFT_BL, HIGH);

    // Connect WiFi
    WiFi.begin(WIFI_SSID, WIFI_PASS);
    tft.setCursor(10, 10);
    tft.setTextColor(TFT_WHITE);
    tft.printf("Connecting to WiFi...");
    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
    }
    Serial.printf("\nWiFi connected: %s\n", WiFi.localIP().toString().c_str());
    tft.fillScreen(TFT_BLACK);
    tft.setCursor(10, 10);
    tft.printf("IP: %s", WiFi.localIP().toString().c_str());
    tft.setCursor(10, 30);
    tft.printf("Connecting to %s:%d", SERVER_IP, SERVER_PORT);
}

void loop() {
    // Reconnect if needed
    if (!client.connected()) {
        if (!connect_to_server()) {
            delay(2000);
            return;
        }
    }

    // Read frames from server
    if (client.available() >= 1) {
        uint8_t msg_type = client.read();
        if (msg_type == MSG_FRAME) {
            // Read 4000 bytes of VRAM
            int remaining = 4000;
            int offset = 0;
            while (remaining > 0 && client.connected()) {
                int n = client.read(vram_buf + offset, remaining);
                if (n > 0) {
                    offset += n;
                    remaining -= n;
                } else if (n < 0) {
                    break;
                }
            }
            if (remaining == 0) {
                render_vram();
            }
        }
    }

    // Poll keyboard and trackball
    poll_keyboard();
    poll_trackball();

    // Release held key after timeout
    if (held_frames > 0 && --held_frames == 0 && held_mask != 0) {
        send_key_event(MSG_KEY_UP, held_col, held_mask);
        held_mask = 0;
        if (fn_held) {
            send_key_event(MSG_KEY_UP, 7, 0x8000);
            fn_held = false;
        }
    }
}
