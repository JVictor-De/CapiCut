# CapiCut

Editor de vídeo Android **offline**, fork MIT do [LibreCuts](https://github.com/tharunbirla/LibreCuts), com camada de inteligência de áudio (`com.capicut.audiointel`).

O `applicationId` permanece `com.tharunbirla.librecuts` para facilitar merges com o upstream. O nome exibido (`app_name`) é **CapiCut**. `versionName`: `1.0-beta7-capicut` (`minSdk` 26, `targetSdk` 35).

Base sincronizada com LibreCuts `main` em [`a510390`](https://github.com/tharunbirla/LibreCuts/commit/a510390371fae96f0b44b833c941213bba8f3570) (release `1.0-beta7` e traduções Weblate posteriores a `2a96eec`). O aviso de copyright do LibreCuts permanece. O produto publicado não usa o nome nem o logo do LibreCuts.

## O que o CapiCut adiciona

- Lista e autosave de projetos locais (`.lcprj` em `filesDir/projects`)
- Serviço em primeiro plano `MediaProcessingService` (`mediaProcessing`), fila thread-safe e notificação
- Cadeia de áudio FFmpeg **padrão**: highpass (~80 Hz) → compressor → de-esser (aproximação `equalizer` ~6,5 kHz) → loudnorm (−16 LUFS). Denoise (DeepFilterNet3) e dereverb ficam desligados por padrão
- Modelos sob demanda (`ModelStore`): Whisper ggml (Hugging Face), Silero VAD ONNX, pack DeepFilterNet3 ONNX
- Runtimes: whisper.cpp `v1.7.6` (JNI, `libcapicut_whisper.so`) e ONNX Runtime Android 1.20.0
- Corte automático por VAD (Silero se o modelo existir; caso contrário, energia). Tratar vídeo no cliente: crop 9:16 centrado e exposição (sem MediaPipe)
- Botões no editor: melhorar áudio, corte automático, transcrever, tratar vídeo, exportar WAV

Timeline, overlays, crop, transições, ducking e export MP4/MP3 continuam os do LibreCuts. A lista completa de recursos do upstream está no [README do LibreCuts](https://github.com/tharunbirla/LibreCuts/blob/main/README.md). Erros de export e de codec: [guia do LibreCuts](https://github.com/tharunbirla/LibreCuts/wiki/Error-Codes-&-Troubleshooting).

## Build

Requer **JDK 17**, **Android SDK 35**, **NDK 27.0.12077973** e **CMake 3.22.1**.

Crie `local.properties` na raiz deste repositório:

```
sdk.dir=C:/Users/SEU_USUARIO/AppData/Local/Android/Sdk
```

No Windows, use `gradlew.bat` no lugar de `./gradlew`.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

`assembleDebug` gera splits `arm64-v8a`, `armeabi-v7a` e `x86_64` (sem APK universal).

APK arm64: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.

No aparelho ou emulador: `./gradlew :app:connectedDebugAndroidTest` (`app/src/androidTest/java/com/capicut/audiointel/CapicutInstrumentedAudioTest.kt`).

A primeira build nativa baixa **whisper.cpp** `v1.7.6` via CMake FetchContent (rede e git).

## Permissões

Herdadas do editor, mais o que a camada CapiCut usa:

- Leitura de mídia (`READ_MEDIA_VIDEO`, `READ_MEDIA_IMAGES`, `READ_MEDIA_AUDIO`; armazenamento legado até o Android 12) e `RECORD_AUDIO`
- `POST_NOTIFICATIONS` para o progresso de export e de processamento
- `INTERNET` apenas para baixar modelos. Corte, transcrição e export rodam no aparelho depois do download
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` e `FOREGROUND_SERVICE_MEDIA_PROCESSING`
- `WAKE_LOCK` durante o processamento

## Créditos

LibreCuts © Tharun Birla, licença MIT. Ver [LICENSE](LICENSE).

Whisper / whisper.cpp, Silero VAD, DeepFilterNet, ONNX Runtime e FFmpeg mantêm as licenças dos respectivos projetos.
