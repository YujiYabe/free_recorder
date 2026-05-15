# Free Recorder

会議音声の録音に特化したAndroidアプリです。文字起こしやクラウド送信は行わず、端末内に音声ファイルだけを保存します。

## 機能

- Kotlin製のAndroidアプリ
- `MediaRecorder` によるm4a/AAC録音
- AI文字起こしツールで扱いやすい64kbps録音
- 60分ごとに自動で別ファイルへ分割
- 録音中はフォアグラウンドサービスで継続
- 通知から録音停止
- Android 10以降は `Music/Meeting Recorder/` に保存
- アプリ内で直近の録音ファイルを一覧表示

## 開発環境

- Android Studio
- JDK 17
- Android SDK 35

このリポジトリにはGradle Wrapperをまだ含めていません。Android Studioでプロジェクトを開くか、ローカルにGradleを入れて次を実行してください。

```bash
gradle :app:assembleDebug
```

## 録音ファイル

録音ファイルは `meeting_yyyyMMdd_HHmmss.m4a` という名前で保存されます。長時間の会議では60分ごとに自動分割されるため、AIツールへアップロードしやすいサイズになります。

64kbps設定のため、ファイルサイズの目安は1時間あたり約29MBです。

## 権限

初回録音時にマイク権限を要求します。Android 13以降では録音中通知のため通知権限も要求します。
