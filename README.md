# OmniNote AI

OmniNote AI is a native Android application built in Kotlin and Jetpack Compose, inspired by NotebookLM. It provides local Room Database storage, pure-Android PDF document text extraction, and grounded AI chat powered by Google Gemini.

## Features

- **Document & Note Storage (Room Database)**:
  - Complete Room Database (`NoteEntity`, `NoteDao`, `AppDatabase`) with Flow-based reactive queries.
  - CRUD operations with instant search filtering across note titles and content.
- **Pure-Android PDF Import & Stream Extraction**:
  - `rememberLauncherForActivityResult` file picker.
  - Native `PdfRenderer` page count inspection and pure-Android content stream text extraction (handling FlateDecode decompression, Tj/TJ text operators, and hex encoding).
- **Grounded AI Assistant (NotebookLM Inspired)**:
  - Powered by Google Gemini (with graceful offline grounded analysis fallback).
  - Strict grounding prompt ensuring answers are cited strictly from notebook text without external hallucinations.
  - Quick action chips (*Summarize*, *Key Takeaways*, *Action Items*, *Quiz Me*).
- **Material 3 Design System**:
  - Dark and Light theme support with custom Indigo/Slate/Cyan color palette.
  - Grid and List layout switcher, responsive cards, and expandable Floating Action Button.

## GitHub Actions & Building the APK

This repository includes a preconfigured GitHub Actions workflow in `.github/workflows/build-apk.yml`. Whenever code is pushed to the `main` branch, GitHub Actions builds `app-debug.apk` and uploads it as an artifact named `OmniNote-AI-APK`.

### Local Build:
```bash
gradle assembleDebug
```
The resulting APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

## GitHub Pages

This repository includes `index.html` and `.nojekyll` to serve as a showcase landing page and APK distribution portal when deployed to GitHub Pages.
