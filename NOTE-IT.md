# ROM Bench — note per l'uso

App per provare l'hardware dopo ogni nuova ROM (950 XL, 1520, 930 e qualsiasi altro telefono con Android 11–17).
Non modifica la ROM, non serve rete né Google Play Services.

## Uso tipico dopo un flash

1. Installa l'APK (release: `app/build/outputs/apk/release/app-release.apk`, firmato con la chiave di debug
   locale se manca `keystore.properties`).
2. Dal PC, il modo più rapido:

   ```bash
   tools/autotest.sh -i app/build/outputs/apk/release/app-release.apk
   ```

   installa con tutti i permessi, concede `DUMP` e `READ_LOGS` (servono solo per i dettagli da dumpsys/logcat),
   lancia tutte le prove automatiche e scarica il report in `reports/<device>-<data>/` (txt, md, bbcode, json).
   Schermo acceso e sbloccato: display, fotocamere e audio ne hanno bisogno.
3. Sul telefono: apri le aree che richiedono una persona (audio, capsula, microfoni, touch, tasti, LED,
   chiamata in entrata/uscita, SMS, sospensione, ricarica) e rispondi **Funziona / Problema / N/D**. Con
   "Problema" puoi scrivere una nota che finisce nel report.
4. **Report** → Testo, Markdown, BBCode (per XDA) o JSON → Condividi / Salva / Copia. Gli identificativi
   (MAC, IMEI, ICCID, seriale, SSID, numeri, celle, UID dei tag) sono nascosti di default.
5. **Report salvati** (icona orologio): seleziona due report per vedere regressioni, correzioni e
   proprietà di build/kernel cambiate tra una build e l'altra.

Altri comandi adb utili:

```bash
adb shell am start -n org.rombench/org.rombench.MainActivity --ez autorun true --es areas telephony,gnss
adb shell am start -n org.rombench/org.rombench.MainActivity --es open area/camera
```

La build debug ha pacchetto `org.rombench.debug` (si installa accanto alla release).

## Cosa guarda, in breve

- **Telefonia**: SIM, registrazione voce/dati per dominio e trasporto (WWAN/IWLAN), tipo di rete, segnale con
  controllo dei valori assurdi, celle, indizi IMS/VoLTE (carrier config, phh IMS installato, dumpsys se c'è
  `DUMP`), prova dei dati mobili sulla rete cellulare anche con il Wi-Fi acceso, chiamata guidata con stato e
  tipo di rete durante la chiamata (LTE = VoLTE), SMS verso il proprio numero con invio/consegna/ricezione.
- **GPS**: satelliti con C/N0 = 0 → errore "RF/LNA" (il caso del 950 XL), TTFF, NMEA, comandi di aiuto.
- **Audio/microfoni**: verifica che l'HAL consumi davvero i campioni (testina che avanza) e che la rotta sia
  quella chiesta; microfoni a zero digitale → percorso del mixer chiuso.
- **Fotocamere**: apre una fotocamera alla volta (il demone del 950 XL non regge due sessioni), misura il primo
  fotogramma e gli fps, riconosce fotogrammi neri o verdi.
- **CPU**: core che restano spenti anche sotto carico, frequenze che non salgono, zone termiche, HAL termico,
  driver GPU software; prova di carico con limitazione termica.
- **Memoria**: combinazioni `ro.lmk.*` che fanno uscire lmkd (`use_psi=false` + `use_minfree_levels=true` senza
  `use_new_strategy`), PSI mancante, zram, velocità dell'eMMC.
- **Sospensione**: prova guidata con sveglia esatta dalla sospensione profonda e percentuale di deep sleep.

## Sicurezza

- Non legge mai debugfs (`/sys/kernel/debug`, `/d`): sul 950 XL leggere `/sys/kernel/debug/shrinker` manda in
  panic il kernel.
- Luminosità, modalità audio, modalità del display, torcia e cattura dei tasti tornano come prima quando esci
  dalla schermata.
- Le prove di rete sono facoltative e usano gli host delle Impostazioni.

## Compilazione

```bash
JAVA_HOME=~/.local/share/mise/installs/java/17.0.2 ./gradlew assembleDebug assembleRelease testDebugUnitTest
```
