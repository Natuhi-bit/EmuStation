; EmuStation（Windows 版）のインストーラー（Inno Setup 6）
; 使い方: iscc /DAppVersion=1.0.0 /DAppDir=<createDistributable の出力>\EmuStation emusync.iss
; 管理者の権限は要らない（ユーザーごとに %LOCALAPPDATA%\Programs\EmuStation に入れる）
; 名前を emusync から EmuStation に変えた。AppId は同じなので、前の版の上からそのまま入れ替わる
; （入れてあったフォルダはそのまま使う。前の exe・ショートカット・起動時の登録は消す）

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#ifndef AppDir
  #define AppDir "..\build\compose\binaries\main\app\EmuStation"
#endif

[Setup]
AppId={{6B1F3A52-8A0E-4C3E-9A55-3F8F2A7F5E10}
AppName=EmuStation
AppVersion={#AppVersion}
AppPublisher=EmuStation
DefaultDirName={localappdata}\Programs\EmuStation
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=output
OutputBaseFilename=EmuStation-setup-{#AppVersion}
SetupIconFile=emusync.ico
UninstallDisplayIcon={app}\EmuStation.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; 動いている EmuStation（前の版の emusync も）を閉じてから上書きする
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "japanese"; MessagesFile: "compiler:Languages\Japanese.isl"

[Tasks]
Name: "autostart"; Description: "Windows の起動時に EmuStation を起動する（タスクトレイに常駐）"; GroupDescription: "起動:"
Name: "desktopicon"; Description: "デスクトップにアイコンを作る"; GroupDescription: "アイコン:"

[InstallDelete]
; 名前を変える前（emusync）の exe とショートカット
Type: files; Name: "{app}\emusync.exe"
Type: files; Name: "{app}\emusync.ico"
Type: files; Name: "{autoprograms}\emusync.lnk"
Type: files; Name: "{autodesktop}\emusync.lnk"

[Files]
Source: "{#AppDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\EmuStation"; Filename: "{app}\EmuStation.exe"
Name: "{autodesktop}\EmuStation"; Filename: "{app}\EmuStation.exe"; Tasks: desktopicon

[Registry]
; 名前を変える前の起動時の登録は消す
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueName: "emusync"; Flags: deletevalue
; アプリの設定画面からも切り替えられる（同じ値を使う）
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "EmuStation"; \
  ValueData: """{app}\EmuStation.exe"" --minimized"; Tasks: autostart

[Run]
Filename: "{app}\EmuStation.exe"; Description: "EmuStation を起動する"; Flags: nowait postinstall skipifsilent

[Code]
// アンインストールしたら、Windows の起動時の登録も消す（アプリの設定画面で登録した場合も、前の名前のものも）
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
  begin
    RegDeleteValue(HKEY_CURRENT_USER, 'Software\Microsoft\Windows\CurrentVersion\Run', 'EmuStation');
    RegDeleteValue(HKEY_CURRENT_USER, 'Software\Microsoft\Windows\CurrentVersion\Run', 'emusync');
  end;
end;
