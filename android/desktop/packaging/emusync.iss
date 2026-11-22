; emusync（Windows 版）のインストーラー（Inno Setup 6）
; 使い方: iscc /DAppVersion=1.0.0 /DAppDir=<createDistributable の出力>\emusync emusync.iss
; 管理者の権限は要らない（ユーザーごとに %LOCALAPPDATA%\Programs\emusync に入れる）

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#ifndef AppDir
  #define AppDir "..\build\compose\binaries\main\app\emusync"
#endif

[Setup]
AppId={{6B1F3A52-8A0E-4C3E-9A55-3F8F2A7F5E10}
AppName=emusync
AppVersion={#AppVersion}
AppPublisher=emusync
DefaultDirName={localappdata}\Programs\emusync
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=output
OutputBaseFilename=emusync-setup-{#AppVersion}
SetupIconFile=emusync.ico
UninstallDisplayIcon={app}\emusync.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; 動いている emusync を閉じてから上書きする
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "japanese"; MessagesFile: "compiler:Languages\Japanese.isl"

[Tasks]
Name: "autostart"; Description: "Windows の起動時に emusync を起動する（タスクトレイに常駐）"; GroupDescription: "起動:"
Name: "desktopicon"; Description: "デスクトップにアイコンを作る"; GroupDescription: "アイコン:"

[Files]
Source: "{#AppDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\emusync"; Filename: "{app}\emusync.exe"
Name: "{autodesktop}\emusync"; Filename: "{app}\emusync.exe"; Tasks: desktopicon

[Registry]
; アプリの設定画面からも切り替えられる（同じ値を使う）
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "emusync"; \
  ValueData: """{app}\emusync.exe"" --minimized"; Tasks: autostart

[Run]
Filename: "{app}\emusync.exe"; Description: "emusync を起動する"; Flags: nowait postinstall skipifsilent

[Code]
// アンインストールしたら、Windows の起動時の登録も消す（アプリの設定画面で登録した場合も）
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
    RegDeleteValue(HKEY_CURRENT_USER, 'Software\Microsoft\Windows\CurrentVersion\Run', 'emusync');
end;
