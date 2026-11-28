Target amd64-unicode
Unicode true
RequestExecutionLevel user
Name "Cellar Guard"
Caption "Cellar Guard"
Icon "guard.ico"
OutFile "${OUTPUT}"
SetCompressor /SOLID lzma
AutoCloseWindow true
ShowInstDetails nevershow
BrandingText "Cellar Studio"
Page instfiles

VIProductVersion "${VERSION}.0"
VIAddVersionKey "ProductName" "Cellar Guard"
VIAddVersionKey "FileDescription" "Cellar Guard portable desktop app"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "CompanyName" "Cellar Studio"
VIAddVersionKey "LegalCopyright" "Copyright © 2026 Cellar contributors"

!include "FileFunc.nsh"

Function .onInit
    ${GetParameters} $0
    ${GetOptions} $0 "--smoke-test" $2
    IfErrors +2
    SetSilent silent
FunctionEnd

Section
    ClearErrors
    InitPluginsDir
    SetDetailsPrint textonly
    DetailPrint "Preparing Cellar Guard..."
    SetOverwrite on
    SetOutPath "$PLUGINSDIR"
    File /oname=cellar-guard.jar "${APP_JAR}"
    File /oname=Cellar-Guard-LICENSE.txt "${APP_LICENSE}"
    SetOutPath "$PLUGINSDIR\runtime"
    File /r "${RUNTIME}/*"
    SetOutPath "$TEMP"
    HideWindow

    IfErrors launch_failed
    ${GetOptions} $0 "--smoke-test" $2
    IfErrors normal smoke

    normal:
    ClearErrors
    ExecWait '"$PLUGINSDIR\runtime\bin\javaw.exe" -cp "$PLUGINSDIR\cellar-guard.jar" io.cellar.desktop.DesktopApp' $1
    IfErrors launch_failed launch_complete

    smoke:
        ClearErrors
        ExecWait '"$PLUGINSDIR\runtime\bin\javaw.exe" -cp "$PLUGINSDIR\cellar-guard.jar" io.cellar.desktop.DesktopApp --smoke-test' $1
        IfErrors launch_failed launch_complete

    launch_complete:
        SetErrorLevel $1
        Goto done

    launch_failed:
        SetErrorLevel 2
        MessageBox MB_OK|MB_ICONSTOP "Cellar Guard could not open. Check that your Windows account can run apps and has free space in its temporary folder." /SD IDOK

    done:
SectionEnd
