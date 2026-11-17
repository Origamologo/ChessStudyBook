# 📖♟️ ChessStudyBook

**ChessStudyBook** is a free and open-source (FOSS) Android application designed for reading chess books side-by-side with a legal-move-validated interactive chessboard, a tree-based PGN variation & annotation studio, multi-game PGN collection management, and local Stockfish engine integration.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-brown.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-green.svg)](https://developer.android.com)
[![UI: Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg)](https://developer.android.com/jetpack/compose)

---

## ✨ Features

* **📚 Multi-Format Chess Book Reader:**
  * Open and read **PDF**, **EPUB**, **TXT**, and **PGN** files alongside the board.
  * High-DPI (`3x`) PDF page rendering with smooth **pinch-to-zoom**, two-finger panning, and quick zoom controls (`🔍-` / `🔍+`).
  * Automatic chapter extraction and adjustable font scaling for EPUB and plain-text books.

* **♟️ Interactive Wooden Chessboard & Position Setup:**
  * Warm walnut & maple wood theme with soft parchment reading cards for eye comfort during long study sessions.
  * Solid-filled, high-contrast chess pieces (`\uFE0E` non-emoji rendering with crisp outlines)[cite: 12].
  * **Strict Legal Move & Turn Enforcement:** Enforces alternating turns (White then Black), legal piece geometry, castling (`O-O` / `O-O-O`), en passant captures, and king check safety, with subtle target dots indicating legal destination squares.
  * **Pawn Promotion Picker Popup:** Automatically opens a piece selector (**Queen, Rook, Bishop, Knight**) whenever a pawn reaches the 8th/1st rank.
  * **Tap-Frame Coordinate Toggle:** Tap the outer wooden frame at the side of the board at any time to show or hide the `a–h` and `1–8` coordinates.
  * **Configure Position Mode (`🛠️ Configure position`):** Place or erase any White/Black pieces freely on the board, choose whose turn it is to move, clear the board, or reset to the starting position (automatically exported with `[SetUp "1"]` and `[FEN "..."]` tags).

* **🌿 Tree-Based PGN Variations, Symbols & Written Notes:**
  * **True Standard Algebraic Notation (SAN):** Both board moves and engine lines use real algebraic notation (`1. e4 e5 2. Nf3 Nc6 3. Bb5+`) with proper disambiguation and check indicators.
  * **Branch Variations Automatically:** Step back with `◀` (or tap any earlier move in the notation box) and play a different move on the board to create a variation inside parentheses `( ... )`[cite: 12].
  * **Clickable Move Tree:** Tap any move in the main line or inside a variation to jump the board directly to that position[cite: 12].
  * **Notation & Commentary Editor (`✍️ Annotate`):** Add standard chess evaluation symbols (`!`, `?`, `!?`, `?!`, `!!`, `??`, `+/=`, `+/-`, `+-`, `=`, `∞`, `=/∞`, `=/+`, `-/+`, `-+`) and written notes inside curly brackets `{ ... }`.
  * **Prune & Erase (`⌫`):** Delete mistakes or entire variation branches with a single tap[cite: 12].

* **📑 Multi-Game PGN Collections & Smart Metadata Saver:**
  * **Multi-Game PGN Support (`♟️ Load PGN` & `📑 1/N`):** Open `.pgn` files containing multiple games, switch between games via the top-bar collection selector, or add new games (`➕ Add new game to current PGN`) into the same PGN collection.
  * **Two-Step, Scroll-Free Save Flow (`💾 Save PGN`):**
    * **Step 1 (Game Metadata):** Enter or edit **White Player, White Elo, Black Player, Black Elo, Tournament (`Event`), Place (`Site`), ECO Code, Round, Date, and Result (`1-0`, `0-1`, `½-½`, `*`)** in a compact, scroll-free layout.
    * **Step 2 (Save Options):** Automatically remembers metadata for already-configured games and lets you save the single game, save all games in the collection into one `.pgn` file, or keep the current game and start the next game in the same PGN.

* **⚡ Lightweight Local Stockfish Integration (Multi-PV in SAN):**
  * **External & Local Engine Discovery:** Automatically detects installed Stockfish / OEX chess engine apps on your device (keeping the APK size tiny at ~4 MB!), user-imported Stockfish binaries, or an optional embedded `libstockfish.so`[cite: 12].
  * **One-Tap Installer Prompt:** If Stockfish is not installed on the device when toggled on, the app prompts the user with direct one-tap links to install Stockfish via **F-Droid (FOSS)**, **Google Play**, **GitHub Releases**, or local file import.
  * **Multi-PV Selector (`1x` – `5x`):** Streams live evaluation scores and principal variations formatted in real SAN notation (`⚡ Engine ▾`)[cite: 12].

* **🔄 Clean Unfolding UI & Adaptive Layout:**
  * Unfolding bottom menus (**`♟️ Board ▾`**, **`✍️ Annotate`**, **`⚡ Engine ▾`**) keep the interface uncluttered on all screen sizes.
  * Automatically switches between a stacked vertical view in Portrait mode and a 50/50 split-screen view in Landscape and on Tablets (`sw600dp+`)[cite: 12].

---

## 🛠️ Building from Source

### 1. Clone the Repository
```bash
git clone [https://github.com/YOUR_USERNAME/ChessStudyBook.git](https://github.com/YOUR_USERNAME/ChessStudyBook.git)
cd ChessStudyBookIf **ChessStudyBook** helps you with your chess study, you can support its development here:

[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-ffdd00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://buymeacoffee.com/origamologo)
