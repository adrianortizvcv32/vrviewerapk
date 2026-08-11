# AndroidVRViewer

🇪🇸 [Español](#-español) | 🇬🇧 [English](#-english)

---

## 🇪🇸 Español

### Instrucciones

#### PASO 1: Descargar modelo MediaPipe (OBLIGATORIO)

Descarga este archivo (~30MB):

```
https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task
```

Ponlo en:

```
app/src/main/assets/hand_landmarker.task
```

**SIN ESTE ARCHIVO LA APP CRASHEA AL ABRIR.**

#### PASO 2: Abrir en Android Studio

1. File → Open → selecciona la carpeta AndroidVRViewer
2. Espera que Gradle sincronice (puede tardar 2-3 minutos la primera vez)
3. Si pide actualizar Gradle, acepta

#### PASO 3: Build y instalar

1. Build → Clean Project
2. Build → Rebuild Project
3. Run → Run 'app' (con S8+ conectado por USB)

#### PASO 4: Usar la app

1. Abre la app en el S8+
2. Ingresa la IP de tu PC
3. Presiona CONECTAR
4. La app enviará:
   - Orientación de cabeza (giroscopio) → SteamVR HMD
   - Posición de manos (cámara) → SteamVR Controllers
   - Recibe stream de video del PC

### Requisitos del PC

- SteamVR corriendo con el driver C++ instalado
- ffmpeg transmitiendo en: `http://[IP_PC]:8554/stream.ts`
- Puertos UDP abiertos: 6299 (datos) y 6300 (auth)

### Estructura de archivos

```
app/src/main/
├── java/com/example/vrviewer/
│   ├── MainActivity.kt      ← Pantalla principal
│   ├── VrUdpSender.kt       ← Envío UDP + autenticación
│   └── HandTracker.kt       ← Cámara + MediaPipe
├── res/
│   ├── layout/activity_main.xml
│   └── values/themes.xml
├── assets/
│   └── hand_landmarker.task  ← DESCARGAR MANUALMENTE
└── AndroidManifest.xml
```

[⬆ Volver arriba](#androidvrviewer)

---

## 🇬🇧 English

### Instructions

#### STEP 1: Download the MediaPipe model (REQUIRED)

Download this file (~30MB):

```
https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task
```

Put it here:

```
app/src/main/assets/hand_landmarker.task
```

**WITHOUT THIS FILE THE APP CRASHES ON LAUNCH.**

#### STEP 2: Open in Android Studio

1. File → Open → select the AndroidVRViewer folder
2. Wait for Gradle to sync (can take 2-3 minutes the first time)
3. If it asks to update Gradle, accept

#### STEP 3: Build and install

1. Build → Clean Project
2. Build → Rebuild Project
3. Run → Run 'app' (with the S8+ connected via USB)

#### STEP 4: Using the app

1. Open the app on the S8+
2. Enter your PC's IP address
3. Press CONNECT
4. The app will send:
   - Head orientation (gyroscope) → SteamVR HMD
   - Hand position (camera) → SteamVR Controllers
   - Receives video stream from the PC

### PC requirements

- SteamVR running with the C++ driver installed
- ffmpeg streaming at: `http://[PC_IP]:8554/stream.ts`
- UDP ports open: 6299 (data) and 6300 (auth)

### File structure

```
app/src/main/
├── java/com/example/vrviewer/
│   ├── MainActivity.kt      ← Main screen
│   ├── VrUdpSender.kt       ← UDP sending + authentication
│   └── HandTracker.kt       ← Camera + MediaPipe
├── res/
│   ├── layout/activity_main.xml
│   └── values/themes.xml
├── assets/
│   └── hand_landmarker.task  ← DOWNLOAD MANUALLY
└── AndroidManifest.xml
```

[⬆ Back to top](#androidvrviewer)
