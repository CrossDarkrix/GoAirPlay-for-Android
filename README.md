# GoAirPlay for Android

iPhone / Mac から AirPlay で送った音楽を、Android スマホ本体のスピーカーで再生する受信アプリです。受信プロトコル本体は Go 製の [GoAirPlay](../airplay-go) をそのまま内蔵し、このアプリは Android 側の都合（マルチキャストロック、音声出力、常駐サービス）を担当します。

An AirPlay receiver app for Android. The protocol core is the Go receiver (embedded as a native binary); this app provides the Android-side pieces: the Wi-Fi multicast lock, AudioTrack playback, and a foreground service.

## 仕組み / How it works

- Go 受信機を `jniLibs/arm64-v8a/libgoairplay.so` として同梱し、起動時に `nativeLibraryDir` から実行
- Go 側は `-pcm-stdout` モードで動き、復号した PCM を標準出力へ書き出す
- Kotlin 側はそれを AudioTrack に流して再生。ログ（標準エラー）はアプリ画面に表示
- 端末 ID の鍵ファイルは `-data-dir` でアプリの内部ストレージに保存

## ビルド / Build

先に Go 本体をビルドして jniLibs に配置してから、通常の Gradle ビルドを行います。

```
# 1. Go receiver -> jniLibs
(cd ../airplay-go && CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -o ../goairplay-android/app/src/main/jniLibs/arm64-v8a/libgoairplay.so .)

# 2. APK
gradle assembleDebug      # debug
gradle assembleRelease    # release (see signing below)
```

## 署名 / Signing

`keystore.properties` がプロジェクト直下にある場合だけ、それを使ってリリース署名します（無ければデバッグ鍵にフォールバック）。書式は storeFile / storePassword / keyAlias / keyPassword の4行で、このファイル自体は公開ツリーに含めません。

## ライセンス / License

MIT License. Copyright (c) CrossDarkrix.
