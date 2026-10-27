# Cassa Android (Standalone)

> **App nativa di finanza personale 100% offline, local-first, privata e open source.**  
> Sviluppata in **Kotlin** con interfaccia reattiva in **Jetpack Compose (Material Design 3)**.

---

## 🌟 Caratteristiche Principali

* **🔒 100% Offline & Massima Privacy**:
  * Nessun dato finanziario esce mai dal tuo dispositivo.
  * Zero server, zero account da registrare, nessun tracciamento o analytics.
  * Funziona completamente offline in locale sul database SQLite/Room dello smartphone.

* **🧾 Smart OCR Scontrini on-Device**:
  * Riconoscimento ottico del testo e dei totali tramite **Google ML Kit Text Recognition** eseguito interamente dal processore locale dello smartphone.
  * Algoritmo specializzato per scontrini fiscali italiani (Registratori Telematici RT).
  * Controllo algebrico automatico Pagato/Resto e chip interattivi per la selezione rapida dell'importo rilevato.
  * Salvataggio protetto delle foto degli scontrini con visualizzatore full-screen integrato.

* **💳 Cattura Notifiche Push Bancarie**:
  * Integrazione con `NotificationListenerService` di Android per rilevare automaticamente pagamenti ed entrate in background.
  * Supporto preconfigurato per: **Google Wallet**, **Banca Monte dei Paschi di Siena (BMPS)**, **Fineco** e **PayPal**.
  * Creazione automatica di notifiche interattive che aprono l'editor di spesa con importo, data e categoria già precompilati.

* **📈 Motore Predittivo di Cash Flow**:
  * Proiezione analitica locale del saldo a fine mese basata sul ritmo di spesa giornaliero effettivo (*burn rate*).
  * Stima del risparmio mensile netto e calcolo dell'autonomia liquida (*runway*).

* **🔐 Protezione Biometrica**:
  * Blocco app all'avvio con impronta digitale tramite le API di sistema `BiometricPrompt`.

* **💾 Backup & Esportazione**:
  * **Backup JSON Completo**: esportazione e ripristino di tutti i conti, categorie e movimenti con un clic per cambiare telefono in sicurezza.
  * **Esportazione CSV**: per consultare o analizzare i dati con Excel, LibreOffice Calc o Google Sheets.

---

## 🏗️ Architettura & Stack Tecnologico

* **Linguaggio**: Kotlin 2.0+
* **UI Toolkit**: Jetpack Compose con Material 3 Design
* **Database Locale**: Room Database (SQLite) con migrazioni versionate
* **Computer Vision**: Google ML Kit Vision Text Recognition (on-device)
* **Requisiti di Sistema**: Android 10.0+ (API Level 29 o superiore)

---

## 🛠️ Compilazione da Sorgenti

Per compilare l'app in locale tramite Gradle:

### Prerequisiti
* JDK 17 (Java Development Kit)
* Android SDK (con Build-Tools 36 o compatibile)

### Comandi di Build

1. **Compilazione Debug**:
   ```bash
   ./gradlew assembleDebug
   ```
   L'APK generato si troverà in `app/build/outputs/apk/debug/app-debug.apk`.

2. **Compilazione Release**:
   ```bash
   ./gradlew assembleRelease
   ```
   L'APK ottimizzato si troverà in `app/build/outputs/apk/release/app-release.apk`.

---

## 📄 Licenza

Distribuito sotto licenza **Apache 2.0** (o **GPLv3**). Vedi il file `LICENSE` per maggiori dettagli.
