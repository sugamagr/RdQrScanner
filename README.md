# RD Book QR Scanner

A specialized Android application designed for scanning and managing RD (Recurring Deposit) book QR codes. Built with modern Android development practices using Kotlin and Jetpack Compose.

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)

## Features

### 📷 QR Code Scanning
- Real-time QR code scanning using CameraX and ML Kit
- Full-screen camera preview with modern overlay design
- Automatic validation for RD account numbers (9-15 digits)
- Duplicate detection within LOTs and sessions
- Undo last scanned number
- Haptic and audio feedback on successful scan

### 📦 LOT & Session Management
- Group scanned RD numbers into LOTs
- Multiple LOTs per scanning session
- Live count display for current LOT and session
- Confirmation dialog before ending a session (with unsaved data warning)
- Session history with detailed statistics

### 🔍 Session History
- Search sessions by session number or RD count
- Filter chips: All / Today / This Week / This Month
- Swipe left to delete a session (with confirmation dialog)
- Long-press to enter multi-select mode; bulk-delete selected sessions
- Spring-animated selection UI with haptic feedback

### 📤 Export Options
- **XLSX Export**: Structured spreadsheet (OOXML) with LOT numbers, RD numbers, counts, and timestamps
- **TXT Export**: Human-readable format for easy sharing
- **Copy to Clipboard**: Quick comma-separated list per LOT
- **Share via Apps**: Direct sharing to WhatsApp, etc. with LOT image attachment

### 🖨️ QR Code Generator
- Generate QR codes for multiple RD account numbers
- A4 PDF output with 24 QR codes per page (6 columns × 4 rows)
- Account number printed below each QR code
- Open generated PDF directly from app

### 🎨 Modern UI/UX
- Light orange theme with Material 3 components
- Smooth spring animations throughout
- Intuitive navigation with Jetpack Compose
- Bilingual help section (Hindi & English)

## Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose + Material 3
- **Camera**: CameraX
- **QR Scanning**: Google ML Kit Barcode Scanning
- **QR Generation**: ZXing (for PDF output)
- **Database**: Room Persistence Library (with versioned migrations)
- **Architecture**: MVVM with Repository pattern
- **Async**: Kotlin Coroutines & Flow
- **PDF Generation**: Android PdfDocument API

## Screenshots

*Coming soon*

## Requirements

- Android 8.0 (API level 26) or higher
- Camera permission for QR scanning
- Storage permission for PDF generation

## Building the Project

1. Clone the repository
2. Open in Android Studio (Ladybug or newer recommended)
3. Sync Gradle files
4. Run on device or emulator

```bash
git clone https://github.com/sugamagrawal/rd-qr-scanner.git
```

## Project Structure

```
app/src/main/java/com/qrscanner/app/
├── data/                    # Room database entities and DAOs
│   ├── AppDatabase.kt       # DB setup, version management, migrations
│   ├── ScanSession.kt       # ScanSession + ScanLot entities
│   ├── ScanSessionDao.kt    # DAOs for ScanSession and ScanLot
│   ├── RdNumber.kt          # RdNumber entity (relational, indexed)
│   └── RdNumberDao.kt       # DAO for RdNumber
├── navigation/              # Navigation setup
│   └── Navigation.kt
├── ui/
│   ├── screens/             # Compose screens
│   │   ├── HomeScreen.kt
│   │   ├── RDScannerScreen.kt
│   │   ├── RDGeneratorScreen.kt
│   │   ├── SessionHistoryScreen.kt
│   │   ├── SessionDetailScreen.kt
│   │   ├── HowItWorksScreen.kt
│   │   └── AppInfoScreen.kt
│   └── theme/               # App theming
│       ├── Color.kt
│       ├── Theme.kt
│       └── Type.kt
└── util/                    # Utility classes
    ├── XlsxExporter.kt
    ├── PdfGenerator.kt
    └── LotImageGenerator.kt
```

## License

**© 2026 Sugam Agrawal. All Rights Reserved.**

This project is proprietary software. You may **not** copy, modify, distribute, or use this code for any purpose without explicit written permission from the author.

This repository is public for portfolio/showcase purposes only.

## Developer

**Sugam Agrawal**

---

*Built with ❤️ using Kotlin & Jetpack Compose*

