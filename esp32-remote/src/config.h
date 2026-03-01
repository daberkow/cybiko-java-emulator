#pragma once

// T-Deck Plus pin definitions
#define PIN_TFT_CS    12
#define PIN_TFT_DC    11
#define PIN_TFT_BL    42
#define PIN_TFT_SCLK  40
#define PIN_TFT_MOSI  41
#define PIN_TFT_MISO  38

// Keyboard (I2C slave at 0x55)
#define PIN_KB_SDA    18
#define PIN_KB_SCL    8
#define KB_I2C_ADDR   0x55

// Trackball
#define PIN_TBALL_UP    3
#define PIN_TBALL_DOWN  15
#define PIN_TBALL_LEFT  1
#define PIN_TBALL_RIGHT 2
#define PIN_TBALL_CLICK 0

// Display dimensions
#define CYBIKO_W 160
#define CYBIKO_H 100
#define SCALE    2
#define SCALED_W (CYBIKO_W * SCALE)
#define SCALED_H (CYBIKO_H * SCALE)

// Protocol message types
#define MSG_FRAME    0x01
#define MSG_KEY_DOWN 0x10
#define MSG_KEY_UP   0x11
