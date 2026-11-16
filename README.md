# 🔥 Heatlogger

Logger GPS personale per Android, pensato per la [Heatmap](https://github.com/marchio11/heatmap) e per il progetto Quantified Self.

## Cosa fa
- **Intervallo adattivo** in base al modo di spostamento riconosciuto da Android: auto 15 s, bici e corsa 30 s, a piedi 60 s (modificabili).
- **Pause con GPS spento** sul Wi-Fi di casa e del lavoro e quando si è fermi a lungo, registrate come eventi (inizio, fine, motivo), con un **battito** leggero ogni 30 minuti.
- **Un punto subito a ogni cambio di stato** (partenza, arrivo, cambio di modo).
- **Nessun punto scartato**: ogni punto ha precisione, velocità, modo (`act`) e tipo (`fix`, `heartbeat`, `transition`).
- **GPX giornaliero su Google Drive** (cartella `Heatlogger`, permesso `drive.file`: l'app vede solo i file che crea).
- **Invio JSON a blocchi** al server Quantified Self, con coda offline e nuovi tentativi.

## Installazione
1. Dal telefono scarica l'ultimo APK: <https://github.com/marchio11/heatlogger/releases/download/latest/heatlogger.apk>
2. Aprilo e consenti l'installazione (Play Protect mostrerà un avviso: è normale per un'app personale).
3. Gli aggiornamenti si installano sopra la versione precedente, senza perdere dati e impostazioni.

## Collegamento a Google Drive (una volta sola)
Nella Google Cloud Console, nello stesso progetto della Heatmap: **Google Auth Platform → Client → Crea client → Android**
- Nome pacchetto: `io.github.marchio11.heatlogger`
- Impronta certificato SHA-1: `F1:D0:55:17:F3:A6:97:5B:D4:A8:1D:12:A5:4F:73:2C:54:3F:8B:3C`

## Formato verso il server QS
`POST` JSON con `device`, `source`, `points[]` (`time` ms UTC, `lat`, `lon`, `acc`, `alt`, `spd` m/s, `dir`, `prov`, `batt`, `act`, `kind`), `activities[]` (`type`, `start`, `end`, `confidence`), `pauses[]` (`reason`, `ssid`, `start`, `end`). Le voci in corso hanno `end: null` e vengono reinviate finché non si chiudono.

## Compilazione
Automatica con GitHub Actions a ogni push su `main`; l'APK viene pubblicato nella release `latest`.
La chiave di firma è in `keystore/` (app personale): serve per installare gli aggiornamenti sopra le versioni precedenti e per il riconoscimento da parte di Google.
